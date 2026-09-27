/*
 * Copyright 2026 SWARM
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.swarmwallet

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.signal.core.ui.compose.EventDrivenViewModel
import org.signal.core.util.logging.Log
import org.signal.libsignal.net.RequestResult
import org.signal.network.api.RegistrationApiV2.RegisterAccountError
import org.signal.network.api.SwarmWalletApi.SwarmWalletError
import org.signal.registration.RegistrationFlowEvent
import org.signal.registration.RegistrationRepository
import org.signal.registration.RestoreDecision
import org.signal.registration.swarm.Bip39
import org.signal.registration.swarm.SwarmWalletIdentity

/**
 * SWARM addition (wallet sign-in). Drives the one screen that replaces the phone number: create a SWARM wallet (24
 * words shown once, then "I have written them down") or restore one from its 24 words, then sign in with it.
 *
 * Signing in: derive the identity from the phrase ([SwarmWalletIdentity]), ask the chat server for a challenge,
 * sign it with the ACI identity key, trade the signature for the account identifier and a single-use registration
 * password, then run Signal's own recovery-password registration with the derived key pairs. There is no PIN
 * step: a SWARM account's recovery is its recovery phrase, and the SWARM server runs no secure value recovery.
 *
 * The phrase is never logged, never persisted, and dropped from memory as soon as the identity is derived.
 */
class WalletSignInViewModel(
  private val repository: RegistrationRepository,
  private val parentEventEmitter: (RegistrationFlowEvent) -> Unit
) : EventDrivenViewModel<WalletSignInEvents>(TAG) {

  companion object {
    private val TAG = Log.tag(WalletSignInViewModel::class)
  }

  private val _state = MutableStateFlow(WalletSignInState())
  val state: StateFlow<WalletSignInState> = _state.asStateFlow()

  /** Runs one event to completion, for tests; the screen goes through [onEvent]. */
  @VisibleForTesting
  suspend fun handle(event: WalletSignInEvents) = processEvent(event)

  override suspend fun processEvent(event: WalletSignInEvents) {
    val current = _state.value
    when (event) {
      WalletSignInEvents.CreateNewWallet -> {
        Log.i(TAG, "[CreateNewWallet] Generating a recovery phrase.")
        val words = withContext(Dispatchers.Default) { Bip39.generate() }.split(" ")
        _state.value = current.copy(stage = WalletSignInState.Stage.WRITE_DOWN, generatedWords = words, writtenDownConfirmed = false, error = null)
      }

      is WalletSignInEvents.WrittenDownChanged -> {
        _state.value = current.copy(writtenDownConfirmed = event.confirmed)
      }

      WalletSignInEvents.ContinueWithNewWallet -> {
        if (current.working || !current.writtenDownConfirmed || current.generatedWords.size != Bip39.WORDS) {
          return
        }
        signIn(current.generatedWords.joinToString(" "))
      }

      WalletSignInEvents.RestoreWallet -> {
        _state.value = current.copy(stage = WalletSignInState.Stage.RESTORE, typedPhrase = "", phraseProblem = null, phraseIsValid = false, error = null)
      }

      is WalletSignInEvents.PhraseChanged -> {
        _state.value = applyTypedPhrase(current, event.text)
      }

      WalletSignInEvents.ContinueWithRestoredWallet -> {
        if (current.working) {
          return
        }
        val check = Bip39.check(current.typedPhrase)
        if (check !is Bip39.Check.Valid) {
          _state.value = applyTypedPhrase(current, current.typedPhrase)
          return
        }
        signIn(check.phrase)
      }

      WalletSignInEvents.BackToChoice -> {
        if (!current.working) {
          _state.value = WalletSignInState()
        }
      }

      WalletSignInEvents.ErrorDismissed -> {
        _state.value = current.copy(error = null)
      }
    }
  }

  private fun applyTypedPhrase(state: WalletSignInState, text: String): WalletSignInState {
    val problem = when (val check = Bip39.check(text)) {
      is Bip39.Check.Valid -> null
      is Bip39.Check.WrongWordCount -> WalletSignInState.PhraseProblem.WordCount(check.words)
      is Bip39.Check.UnknownWord -> WalletSignInState.PhraseProblem.UnknownWord(check.index + 1)
      Bip39.Check.BadChecksum -> WalletSignInState.PhraseProblem.Checksum
    }
    return state.copy(typedPhrase = text, phraseProblem = problem, phraseIsValid = problem == null)
  }

  private suspend fun signIn(phrase: String) {
    _state.value = _state.value.copy(working = true, error = null)

    Log.i(TAG, "[signIn] Deriving the identity from the recovery phrase.")
    val identity = withContext(Dispatchers.Default) { SwarmWalletIdentity.derive(phrase) }

    val aci = identity.aciIdentityKeyPair

    Log.i(TAG, "[signIn] Asking for a challenge.")
    val challenge = when (val result = repository.requestSwarmWalletChallenge(aci.publicKey)) {
      is RequestResult.Success -> result.result
      else -> return fail("challenge", result)
    }

    val signature = SwarmWalletIdentity.signChallenge(aci, challenge.challenge)

    Log.i(TAG, "[signIn] Answering the challenge.")
    val verification = when (val result = repository.verifySwarmWalletChallenge(aci.publicKey, signature)) {
      is RequestResult.Success -> result.result
      else -> return fail("verify", result)
    }

    Log.i(TAG, "[signIn] Registering.")
    when (val result = repository.registerAccountWithSwarmWallet(identity, verification)) {
      is RequestResult.Success -> {
        val (response, keyMaterial, registeredAci) = result.result
        Log.i(TAG, "[signIn] Registered. Re-registration: ${response.reregistration}. No PIN for a SWARM account; completing registration.")

        // The words have done their job; nothing needs them any more.
        _state.value = WalletSignInState(working = true)

        parentEventEmitter(RegistrationFlowEvent.Registered(registeredAci, keyMaterial.accountEntropyPool, response.storageCapable, phoneNumberless = false))
        repository.setPinOptedOut()
        repository.setRestoreDecision(RestoreDecision.NEW_ACCOUNT)
        parentEventEmitter(RegistrationFlowEvent.RegistrationComplete)
      }

      is RequestResult.NonSuccess -> {
        Log.w(TAG, "[signIn] Registration refused: ${result.error::class.simpleName}")
        val error = when (result.error) {
          is RegisterAccountError.RateLimited -> WalletSignInState.SignInError.RATE_LIMITED
          // With skipDeviceTransfer set, the only 409 the SWARM server sends here is "this identifier belongs to a
          // different identity key": two wallets that derive the same identifier (see the server's
          // docs/WALLET-SIGN-IN.md, "Collisions"). The newer wallet cannot use it.
          RegisterAccountError.DeviceTransferPossible -> WalletSignInState.SignInError.IDENTIFIER_TAKEN
          else -> WalletSignInState.SignInError.REJECTED
        }
        _state.value = _state.value.copy(working = false, error = error)
      }

      is RequestResult.RetryableNetworkError -> {
        Log.w(TAG, "[signIn] Network error while registering.", result.networkError)
        _state.value = _state.value.copy(working = false, error = WalletSignInState.SignInError.NETWORK)
      }

      is RequestResult.ApplicationError -> {
        Log.w(TAG, "[signIn] Registration failed.", result.cause)
        _state.value = _state.value.copy(working = false, error = WalletSignInState.SignInError.UNKNOWN)
      }
    }
  }

  private fun fail(step: String, result: RequestResult<*, SwarmWalletError>) {
    val error = when (result) {
      is RequestResult.NonSuccess -> {
        Log.w(TAG, "[signIn] The server refused the $step step: ${result.error::class.simpleName}")
        when (result.error) {
          is SwarmWalletError.RateLimited -> WalletSignInState.SignInError.RATE_LIMITED
          is SwarmWalletError.IdentifierTaken -> WalletSignInState.SignInError.IDENTIFIER_TAKEN
          else -> WalletSignInState.SignInError.REJECTED
        }
      }
      is RequestResult.RetryableNetworkError -> {
        Log.w(TAG, "[signIn] Network error at the $step step.", result.networkError)
        WalletSignInState.SignInError.NETWORK
      }
      is RequestResult.ApplicationError -> {
        Log.w(TAG, "[signIn] The $step step failed.", result.cause)
        WalletSignInState.SignInError.UNKNOWN
      }
      else -> WalletSignInState.SignInError.UNKNOWN
    }
    _state.value = _state.value.copy(working = false, error = error)
  }
}
