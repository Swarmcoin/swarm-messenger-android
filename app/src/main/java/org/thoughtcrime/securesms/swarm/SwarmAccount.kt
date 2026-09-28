/*
 * Copyright 2026 SWARM
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.swarm

import org.signal.registration.swarm.SwarmWalletIdentity
import org.thoughtcrime.securesms.keyvalue.SignalStore

/**
 * What kind of account this install holds.
 *
 * A SWARM wallet account is registered under a synthetic `+888` identifier derived from its identity key
 * (docs/WALLET-SIGN-IN.md). The server needs a value shaped like a phone number, but it is not one: nobody can
 * find the account by it (the SWARM server runs no contact discovery), it is never shown, and it can never be
 * "changed" to a real number. Settings that are about the account's phone number are therefore hidden for such an
 * account. The screens behind them stay in the tree, unreached, so upstream merges stay small.
 */
object SwarmAccount {

  /** True when the local account is a SWARM wallet account: its identifier is a `+888` SWARM identifier. */
  @JvmStatic
  fun isWalletAccount(): Boolean {
    return SwarmWalletIdentity.isSwarmAccountIdentifier(SignalStore.account.e164)
  }

  /**
   * Whether the account has a phone number that settings can be about. False for upstream's phone-numberless
   * accounts and for SWARM wallet accounts. Gates "Who can find me by number?" on the profile screen, the Privacy
   * page's "Phone number" row and the Account page's "Change phone number" row.
   */
  @JvmStatic
  fun hasPhoneNumber(): Boolean {
    return !SignalStore.account.isPhoneNumberless && !isWalletAccount()
  }
}
