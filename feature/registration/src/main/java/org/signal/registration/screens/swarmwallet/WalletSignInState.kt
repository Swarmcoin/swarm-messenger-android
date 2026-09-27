/*
 * Copyright 2026 SWARM
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.screens.swarmwallet

/**
 * SWARM addition (wallet sign-in). What the wallet sign-in screen shows.
 *
 * The recovery phrase lives here only while a screen needs it (being shown once, or being typed) and is never
 * written to disk: this state is not part of the persisted registration flow, and [toString] never prints it.
 */
data class WalletSignInState(
  val stage: Stage = Stage.CHOOSE,
  /** The words of a freshly generated phrase, while [Stage.WRITE_DOWN] shows them. */
  val generatedWords: List<String> = emptyList(),
  val writtenDownConfirmed: Boolean = false,
  /** What the person has typed on [Stage.RESTORE]. */
  val typedPhrase: String = "",
  val phraseProblem: PhraseProblem? = null,
  val phraseIsValid: Boolean = false,
  val working: Boolean = false,
  val error: SignInError? = null
) {
  enum class Stage {
    /** Create a new wallet, or restore one. */
    CHOOSE,

    /** The 24 new words, shown once, with the "written down" confirmation. */
    WRITE_DOWN,

    /** Typing the 24 words of an existing wallet. */
    RESTORE
  }

  sealed interface PhraseProblem {
    data class WordCount(val words: Int) : PhraseProblem

    /** [position] is one-based, as the person counts. */
    data class UnknownWord(val position: Int) : PhraseProblem

    data object Checksum : PhraseProblem
  }

  enum class SignInError {
    NETWORK,
    RATE_LIMITED,
    IDENTIFIER_TAKEN,
    REJECTED,
    UNKNOWN
  }

  override fun toString(): String {
    return "WalletSignInState(stage=$stage, words=${generatedWords.size}, writtenDownConfirmed=$writtenDownConfirmed, " +
      "typedWords=${typedPhrase.trim().split(Regex("\\s+")).count { it.isNotEmpty() }}, phraseProblem=$phraseProblem, " +
      "phraseIsValid=$phraseIsValid, working=$working, error=$error)"
  }
}

/** SWARM addition (wallet sign-in). What the wallet sign-in screen can ask for. */
sealed interface WalletSignInEvents {
  data object CreateNewWallet : WalletSignInEvents
  data class WrittenDownChanged(val confirmed: Boolean) : WalletSignInEvents
  data object ContinueWithNewWallet : WalletSignInEvents
  data object RestoreWallet : WalletSignInEvents

  class PhraseChanged(val text: String) : WalletSignInEvents {
    override fun toString(): String = "PhraseChanged(<redacted>)"
  }

  data object ContinueWithRestoredWallet : WalletSignInEvents
  data object BackToChoice : WalletSignInEvents
  data object ErrorDismissed : WalletSignInEvents
}
