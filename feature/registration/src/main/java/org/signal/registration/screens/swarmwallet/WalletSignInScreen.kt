/*
 * Copyright 2026 SWARM
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.swarmwallet

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.Buttons
import org.signal.registration.R

/** Test tags, exposed as resource ids so UI Automator can drive the screen (the emulator smoke test does). */
object WalletSignInTags {
  const val CREATE = "swarm_wallet_create"
  const val RESTORE = "swarm_wallet_restore"
  const val WORDS = "swarm_wallet_words"
  const val WRITTEN_DOWN = "swarm_wallet_written_down"
  const val CONTINUE = "swarm_wallet_continue"
  const val PHRASE_INPUT = "swarm_wallet_phrase_input"
  const val WORKING = "swarm_wallet_working"
}

/**
 * SWARM addition (wallet sign-in). The screen that replaces "enter your phone number": create a SWARM wallet or
 * restore one. See [WalletSignInViewModel].
 *
 * While it is showing, the window is FLAG_SECURE: the recovery phrase cannot be screenshotted, recorded, or seen in
 * the recent-apps overview.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WalletSignInScreen(
  state: WalletSignInState,
  onEvent: (WalletSignInEvents) -> Unit,
  modifier: Modifier = Modifier
) {
  SecureWindow()

  BackHandler(enabled = state.stage != WalletSignInState.Stage.CHOOSE && !state.working) {
    onEvent(WalletSignInEvents.BackToChoice)
  }

  Surface(
    modifier = modifier
      .fillMaxSize()
      .semantics { testTagsAsResourceId = true }
  ) {
    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        when (state.stage) {
          WalletSignInState.Stage.CHOOSE -> Choose(onEvent)
          WalletSignInState.Stage.WRITE_DOWN -> WriteDown(state, onEvent)
          WalletSignInState.Stage.RESTORE -> Restore(state, onEvent)
        }
      }

      if (state.working) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
          Column(
            modifier = Modifier.fillMaxSize().testTag(WalletSignInTags.WORKING),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = stringResource(R.string.SwarmWallet__signing_in), style = MaterialTheme.typography.bodyLarge)
          }
        }
      }
    }
  }

  state.error?.let { error ->
    AlertDialog(
      onDismissRequest = { onEvent(WalletSignInEvents.ErrorDismissed) },
      confirmButton = {
        TextButton(onClick = { onEvent(WalletSignInEvents.ErrorDismissed) }) {
          Text(stringResource(android.R.string.ok))
        }
      },
      title = { Text(stringResource(R.string.SwarmWallet__error_title)) },
      text = { Text(stringResource(errorMessage(error))) }
    )
  }
}

@Composable
private fun SecureWindow() {
  val activity = LocalActivity.current
  DisposableEffect(activity) {
    activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    onDispose {
      activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
  }
}

@Composable
private fun Choose(onEvent: (WalletSignInEvents) -> Unit) {
  Spacer(modifier = Modifier.height(24.dp))
  Image(
    painter = painterResource(R.drawable.swarm_mark),
    contentDescription = null,
    modifier = Modifier.width(160.dp)
  )
  Spacer(modifier = Modifier.height(32.dp))
  Text(
    text = stringResource(R.string.SwarmWallet__choose_title),
    style = MaterialTheme.typography.headlineMedium,
    textAlign = TextAlign.Center
  )
  Spacer(modifier = Modifier.height(16.dp))
  Text(
    text = stringResource(R.string.SwarmWallet__choose_body),
    style = MaterialTheme.typography.bodyLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.Center
  )
  Spacer(modifier = Modifier.height(40.dp))
  Buttons.LargePrimary(
    onClick = { onEvent(WalletSignInEvents.CreateNewWallet) },
    modifier = Modifier.fillMaxWidth().testTag(WalletSignInTags.CREATE)
  ) {
    Text(stringResource(R.string.SwarmWallet__create))
  }
  Spacer(modifier = Modifier.height(16.dp))
  Buttons.LargeTonal(
    onClick = { onEvent(WalletSignInEvents.RestoreWallet) },
    modifier = Modifier.fillMaxWidth().testTag(WalletSignInTags.RESTORE)
  ) {
    Text(stringResource(R.string.SwarmWallet__restore))
  }
}

@Composable
private fun WriteDown(state: WalletSignInState, onEvent: (WalletSignInEvents) -> Unit) {
  Text(
    text = stringResource(R.string.SwarmWallet__write_down_title),
    style = MaterialTheme.typography.headlineSmall,
    modifier = Modifier.fillMaxWidth()
  )
  Spacer(modifier = Modifier.height(12.dp))
  Text(
    text = stringResource(R.string.SwarmWallet__write_down_body),
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.fillMaxWidth()
  )
  Spacer(modifier = Modifier.height(24.dp))

  val half = (state.generatedWords.size + 1) / 2
  Row(modifier = Modifier.fillMaxWidth().testTag(WalletSignInTags.WORDS), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    for (column in 0 until 2) {
      Column(modifier = Modifier.weight(1f)) {
        for (row in 0 until half) {
          val index = column * half + row
          if (index < state.generatedWords.size) {
            Row(modifier = Modifier.padding(vertical = 4.dp)) {
              Text(
                text = "${index + 1}.",
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(36.dp)
              )
              Text(
                text = state.generatedWords[index],
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace
              )
            }
          }
        }
      }
    }
  }

  Spacer(modifier = Modifier.height(24.dp))
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    Checkbox(
      checked = state.writtenDownConfirmed,
      onCheckedChange = { onEvent(WalletSignInEvents.WrittenDownChanged(it)) },
      modifier = Modifier.testTag(WalletSignInTags.WRITTEN_DOWN)
    )
    Text(text = stringResource(R.string.SwarmWallet__written_down), style = MaterialTheme.typography.bodyMedium)
  }
  Spacer(modifier = Modifier.height(24.dp))
  Buttons.LargePrimary(
    onClick = { onEvent(WalletSignInEvents.ContinueWithNewWallet) },
    enabled = state.writtenDownConfirmed && !state.working,
    modifier = Modifier.fillMaxWidth().testTag(WalletSignInTags.CONTINUE)
  ) {
    Text(stringResource(R.string.SwarmWallet__continue))
  }
  Spacer(modifier = Modifier.height(8.dp))
  TextButton(onClick = { onEvent(WalletSignInEvents.BackToChoice) }, enabled = !state.working) {
    Text(stringResource(R.string.SwarmWallet__back))
  }
}

@Composable
private fun Restore(state: WalletSignInState, onEvent: (WalletSignInEvents) -> Unit) {
  Text(
    text = stringResource(R.string.SwarmWallet__restore_title),
    style = MaterialTheme.typography.headlineSmall,
    modifier = Modifier.fillMaxWidth()
  )
  Spacer(modifier = Modifier.height(12.dp))
  Text(
    text = stringResource(R.string.SwarmWallet__restore_body),
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.fillMaxWidth()
  )
  Spacer(modifier = Modifier.height(24.dp))
  OutlinedTextField(
    value = state.typedPhrase,
    onValueChange = { onEvent(WalletSignInEvents.PhraseChanged(it)) },
    label = { Text(stringResource(R.string.SwarmWallet__phrase_label)) },
    minLines = 4,
    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
    keyboardOptions = KeyboardOptions(
      capitalization = KeyboardCapitalization.None,
      autoCorrectEnabled = false,
      keyboardType = KeyboardType.Password,
      imeAction = ImeAction.Done
    ),
    isError = state.phraseProblem != null && state.phraseProblem !is WalletSignInState.PhraseProblem.WordCount,
    supportingText = {
      Text(phraseHint(state))
    },
    modifier = Modifier.fillMaxWidth().testTag(WalletSignInTags.PHRASE_INPUT)
  )
  Spacer(modifier = Modifier.height(24.dp))
  Buttons.LargePrimary(
    onClick = { onEvent(WalletSignInEvents.ContinueWithRestoredWallet) },
    enabled = state.phraseIsValid && !state.working,
    modifier = Modifier.fillMaxWidth().testTag(WalletSignInTags.CONTINUE)
  ) {
    Text(stringResource(R.string.SwarmWallet__continue))
  }
  Spacer(modifier = Modifier.height(8.dp))
  TextButton(onClick = { onEvent(WalletSignInEvents.BackToChoice) }, enabled = !state.working) {
    Text(stringResource(R.string.SwarmWallet__back))
  }
}

@Composable
private fun phraseHint(state: WalletSignInState): String {
  return when (val problem = state.phraseProblem) {
    null -> if (state.phraseIsValid) stringResource(R.string.SwarmWallet__phrase_ok) else ""
    is WalletSignInState.PhraseProblem.WordCount -> stringResource(R.string.SwarmWallet__phrase_word_count, problem.words)
    is WalletSignInState.PhraseProblem.UnknownWord -> stringResource(R.string.SwarmWallet__phrase_unknown_word, problem.position)
    WalletSignInState.PhraseProblem.Checksum -> stringResource(R.string.SwarmWallet__phrase_checksum)
  }
}

private fun errorMessage(error: WalletSignInState.SignInError): Int {
  return when (error) {
    WalletSignInState.SignInError.NETWORK -> R.string.SwarmWallet__error_network
    WalletSignInState.SignInError.RATE_LIMITED -> R.string.SwarmWallet__error_rate_limited
    WalletSignInState.SignInError.IDENTIFIER_TAKEN -> R.string.SwarmWallet__error_identifier_taken
    WalletSignInState.SignInError.REJECTED -> R.string.SwarmWallet__error_rejected
    WalletSignInState.SignInError.UNKNOWN -> R.string.SwarmWallet__error_unknown
  }
}
