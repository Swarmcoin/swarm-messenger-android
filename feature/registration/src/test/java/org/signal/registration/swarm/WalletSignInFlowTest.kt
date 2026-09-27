/*
 * Copyright 2026 SWARM
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.swarm

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.core.models.ServiceId.ACI
import org.signal.core.util.Base64
import org.signal.core.util.billing.OneTimePurchaseApi
import org.signal.core.util.logging.Log
import org.signal.libsignal.net.RequestResult
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.zkgroup.receipts.ReceiptCredentialPresentation
import org.signal.network.api.RegistrationApiV2.AccountAttributes
import org.signal.network.api.RegistrationApiV2.PreKeyCollection
import org.signal.network.api.RegistrationApiV2.RegisterAccountError
import org.signal.network.api.RegistrationApiV2.RegisterAccountResponse
import org.signal.network.api.SwarmWalletApi
import org.signal.registration.NetworkController
import org.signal.registration.RegistrationFlowEvent
import org.signal.registration.RegistrationRepository
import org.signal.registration.RestoreDecision
import org.signal.registration.fakes.FakeNetworkController
import org.signal.registration.fakes.FakeStorageController
import org.signal.registration.fakes.SystemOutLogger
import org.signal.registration.screens.swarmwallet.WalletSignInEvents
import org.signal.registration.screens.swarmwallet.WalletSignInState
import org.signal.registration.screens.swarmwallet.WalletSignInViewModel
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.SecureRandom

/**
 * SWARM addition (wallet sign-in): the whole flow behind the wallet screen, against a fake chat server that behaves
 * like `SwarmWalletRegistrationController` + `RegistrationController` do (challenge, signature check, identifier
 * derivation, single-use password, and the binding of the registration to the key that derives the identifier).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WalletSignInFlowTest {

  companion object {
    /** Entropy 0x00 x 32: the first of the three shared vectors (see SwarmWalletIdentityTest). */
    private val ALL_ZERO_PHRASE = Bip39.entropyToPhrase(ByteArray(32))
    private const val ALL_ZERO_ACI_KEY = "BdUup/KTUWFdGc1rkQy8qqoAXARjDuW2b32p1STJMl5Y"
    private const val ALL_ZERO_IDENTIFIER = "+88810866442360"
  }

  /** The SWARM chat server's wallet sign-in, as far as the client can tell. */
  private class FakeSwarmServer(private val delegate: FakeNetworkController) : NetworkController by delegate {
    private val random = SecureRandom()
    private val pendingChallenges = mutableMapOf<String, ByteArray>()
    private val passwords = mutableMapOf<String, String>()

    var verifyResponse: ((SwarmWalletApi.Verification) -> RequestResult<SwarmWalletApi.Verification, SwarmWalletApi.SwarmWalletError>)? = null
    val registrations = mutableListOf<Triple<String?, String?, IdentityKey>>()
    var challengesIssued = 0

    override suspend fun requestSwarmWalletChallenge(identityKey: IdentityKey): RequestResult<SwarmWalletApi.Challenge, SwarmWalletApi.SwarmWalletError> {
      val challenge = ByteArray(SwarmWalletIdentity.CHALLENGE_BYTES).also { random.nextBytes(it) }
      pendingChallenges[Base64.encodeWithPadding(identityKey.serialize())] = challenge
      challengesIssued++
      return RequestResult.Success(SwarmWalletApi.Challenge(challenge, 120))
    }

    override suspend fun verifySwarmWalletChallenge(identityKey: IdentityKey, signature: ByteArray): RequestResult<SwarmWalletApi.Verification, SwarmWalletApi.SwarmWalletError> {
      val challenge = pendingChallenges.remove(Base64.encodeWithPadding(identityKey.serialize()))
        ?: return RequestResult.NonSuccess(SwarmWalletApi.SwarmWalletError.NoChallengePending)

      if (!identityKey.publicKey.verifySignature(SwarmWalletIdentity.challengeMessage(identityKey, challenge), signature)) {
        return RequestResult.NonSuccess(SwarmWalletApi.SwarmWalletError.SignatureRejected)
      }

      val number = SwarmWalletIdentity.accountIdentifierFor(identityKey)
      val password = Base64.encodeWithPadding(ByteArray(32).also { random.nextBytes(it) })
      passwords[number] = password
      val verification = SwarmWalletApi.Verification(number, password)
      return verifyResponse?.invoke(verification) ?: RequestResult.Success(verification)
    }

    override suspend fun registerAccount(
      e164: String?,
      password: String,
      sessionId: String?,
      recoveryPassword: String?,
      receiptCredentialPresentation: ReceiptCredentialPresentation?,
      attributes: AccountAttributes,
      aciPreKeys: PreKeyCollection,
      pniPreKeys: PreKeyCollection?,
      fcmToken: String?,
      skipDeviceTransfer: Boolean,
      aci: ACI?,
      totp: Int?
    ): RequestResult<RegisterAccountResponse, RegisterAccountError> {
      registrations += Triple(e164, recoveryPassword, aciPreKeys.identityKey)

      if (sessionId != null || recoveryPassword == null || e164 == null || passwords.remove(e164) != recoveryPassword) {
        return RequestResult.NonSuccess(RegisterAccountError.RegistrationRecoveryPasswordIncorrect("no such password"))
      }
      // The server's binding check: a SWARM identifier may only be registered by the key that derives it.
      if (SwarmWalletIdentity.accountIdentifierFor(aciPreKeys.identityKey) != e164) {
        return RequestResult.NonSuccess(RegisterAccountError.RegistrationRecoveryPasswordIncorrect("identity key does not derive this identifier"))
      }

      return delegate.registerAccount(e164, password, sessionId, recoveryPassword, receiptCredentialPresentation, attributes, aciPreKeys, pniPreKeys, fcmToken, skipDeviceTransfer, aci, totp)
    }
  }

  private lateinit var server: FakeSwarmServer
  private lateinit var storage: FakeStorageController
  private lateinit var events: MutableList<RegistrationFlowEvent>
  private lateinit var viewModel: WalletSignInViewModel

  @Before
  fun setUp() {
    Log.initialize(SystemOutLogger())
    server = FakeSwarmServer(FakeNetworkController())
    storage = FakeStorageController()
    events = mutableListOf()
    val repository = RegistrationRepository(
      context = ApplicationProvider.getApplicationContext<Application>(),
      networkController = server,
      storageController = storage,
      isLinkAndSyncAvailable = false,
      signalLoginPurchaseApi = OneTimePurchaseApi.Empty,
      isSwarmWalletSignIn = true
    )
    viewModel = WalletSignInViewModel(repository) { events += it }
  }

  @Test
  fun `a new wallet shows 24 words and registers only after they are marked written down`() = runTest {
    viewModel.handle(WalletSignInEvents.CreateNewWallet)
    val words = viewModel.state.value.generatedWords
    assertThat(viewModel.state.value.stage).isEqualTo(WalletSignInState.Stage.WRITE_DOWN)
    assertThat(words).hasSize(24)
    assertThat(Bip39.check(words.joinToString(" "))).isInstanceOf<Bip39.Check.Valid>()

    viewModel.handle(WalletSignInEvents.ContinueWithNewWallet)
    assertThat(server.challengesIssued).isEqualTo(0)

    viewModel.handle(WalletSignInEvents.WrittenDownChanged(true))
    viewModel.handle(WalletSignInEvents.ContinueWithNewWallet)

    val expected = SwarmWalletIdentity.derive(words.joinToString(" "))
    assertThat(server.registrations).hasSize(1)
    assertThat(server.registrations[0].first).isEqualTo(expected.accountIdentifier)
    assertThat(server.registrations[0].third).isEqualTo(expected.aciIdentityKeyPair.publicKey)
    assertRegistrationCompleted(expected.accountIdentifier)

    // The words are gone from the screen state once they have done their job.
    assertThat(viewModel.state.value.generatedWords).isEmpty()
  }

  @Test
  fun `restoring the all-zero vector registers the account the server and the desktop derive`() = runTest {
    viewModel.handle(WalletSignInEvents.RestoreWallet)
    viewModel.handle(WalletSignInEvents.PhraseChanged("  " + ALL_ZERO_PHRASE.uppercase() + "\n"))
    assertThat(viewModel.state.value.phraseIsValid).isTrue()

    viewModel.handle(WalletSignInEvents.ContinueWithRestoredWallet)

    assertThat(server.registrations).hasSize(1)
    assertThat(server.registrations[0].first).isEqualTo(ALL_ZERO_IDENTIFIER)
    assertThat(Base64.encodeWithPadding(server.registrations[0].third.serialize())).isEqualTo(ALL_ZERO_ACI_KEY)
    assertRegistrationCompleted(ALL_ZERO_IDENTIFIER)
  }

  @Test
  fun `a phrase with a bad checksum is refused and nothing is sent`() = runTest {
    val words = ALL_ZERO_PHRASE.split(" ").toMutableList()
    words[23] = "zoo"

    viewModel.handle(WalletSignInEvents.RestoreWallet)
    viewModel.handle(WalletSignInEvents.PhraseChanged(words.joinToString(" ")))
    assertThat(viewModel.state.value.phraseProblem).isEqualTo(WalletSignInState.PhraseProblem.Checksum)

    viewModel.handle(WalletSignInEvents.ContinueWithRestoredWallet)

    assertThat(server.challengesIssued).isEqualTo(0)
    assertThat(server.registrations).isEmpty()
    assertThat(events).isEmpty()
  }

  @Test
  fun `an identifier that belongs to another wallet is reported, and nothing registers`() = runTest {
    server.verifyResponse = { RequestResult.NonSuccess(SwarmWalletApi.SwarmWalletError.IdentifierTaken) }

    viewModel.handle(WalletSignInEvents.RestoreWallet)
    viewModel.handle(WalletSignInEvents.PhraseChanged(ALL_ZERO_PHRASE))
    viewModel.handle(WalletSignInEvents.ContinueWithRestoredWallet)

    assertThat(viewModel.state.value.error).isEqualTo(WalletSignInState.SignInError.IDENTIFIER_TAKEN)
    assertThat(viewModel.state.value.working).isFalse()
    assertThat(server.registrations).isEmpty()
    assertThat(events).isEmpty()
  }

  @Test
  fun `a server that derives a different identifier is not trusted`() = runTest {
    server.verifyResponse = { verification -> RequestResult.Success(SwarmWalletApi.Verification("+88899999999999", verification.registrationPassword)) }

    viewModel.handle(WalletSignInEvents.RestoreWallet)
    viewModel.handle(WalletSignInEvents.PhraseChanged(ALL_ZERO_PHRASE))
    viewModel.handle(WalletSignInEvents.ContinueWithRestoredWallet)

    assertThat(viewModel.state.value.error).isEqualTo(WalletSignInState.SignInError.UNKNOWN)
    assertThat(server.registrations).isEmpty()
    assertThat(events).isEmpty()
  }

  @Test
  fun `the screen state never prints the phrase`() = runTest {
    viewModel.handle(WalletSignInEvents.RestoreWallet)
    viewModel.handle(WalletSignInEvents.PhraseChanged(ALL_ZERO_PHRASE))
    assertThat(viewModel.state.value.toString().contains("abandon")).isFalse()

    viewModel.handle(WalletSignInEvents.BackToChoice)
    viewModel.handle(WalletSignInEvents.CreateNewWallet)
    val firstWord = viewModel.state.value.generatedWords.first()
    assertThat(viewModel.state.value.toString().contains(" $firstWord")).isFalse()
  }

  private fun assertRegistrationCompleted(expectedIdentifier: String) {
    assertThat(events.filterIsInstance<RegistrationFlowEvent.Registered>()).hasSize(1)
    assertThat(events.last()).isEqualTo(RegistrationFlowEvent.RegistrationComplete)
    assertThat(events.filterIsInstance<RegistrationFlowEvent.Registered>().single().phoneNumberless).isFalse()

    val committed = storage.committedData
    assertThat(committed).isNotNull()
    assertThat(committed!!.accountData?.e164).isEqualTo(expectedIdentifier)
    assertThat(committed.pinOptedOut).isTrue()
    assertThat(committed.pin).isEqualTo("")
    assertThat(storage.restoreDecision).isEqualTo(RestoreDecision.NEW_ACCOUNT)
    assertThat(viewModel.state.value.error).isNull()
    assertThat(events.map { it::class }).containsExactly(RegistrationFlowEvent.Registered::class, RegistrationFlowEvent.RegistrationComplete::class)
  }
}
