/*
 * Copyright 2026 SWARM
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.swarm

import android.app.Application
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.thoughtcrime.securesms.components.settings.app.privacy.PrivacySettingsViewModel
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.testutil.SignalStoreRule

/**
 * A SWARM wallet account is registered under a `+888` identifier, which is not a phone number (docs/WALLET-SIGN-IN.md).
 * The rows that are about a phone number - "Who can find me by number?" on the profile screen, Privacy > "Phone number",
 * Account > "Change phone number" - must not be offered to it, and must stay for an account that has a real number.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class SwarmPhoneNumberRowsTest {

  companion object {
    /** The account identifier of the all-zero shared test vector (SwarmWalletIdentityTest). */
    private const val SWARM_IDENTIFIER = "+88810866442360"

    /** A number in a range reserved for fiction. */
    private const val PHONE_NUMBER = "+15555550123"
  }

  @get:Rule
  val appDependencies = MockAppDependenciesRule()

  @get:Rule
  val signalStore = SignalStoreRule()

  @Test
  fun `a +888 identifier makes a SWARM wallet account, which has no phone number`() {
    SignalStore.account.setE164(SWARM_IDENTIFIER)

    assertThat(SwarmAccount.isWalletAccount()).isTrue()
    assertThat(SwarmAccount.hasPhoneNumber()).isFalse()
  }

  @Test
  fun `an account with a real phone number keeps its phone-number rows`() {
    SignalStore.account.setE164(PHONE_NUMBER)

    assertThat(SwarmAccount.isWalletAccount()).isFalse()
    assertThat(SwarmAccount.hasPhoneNumber()).isTrue()
  }

  @Test
  fun `before registration there is no wallet account`() {
    assertThat(SwarmAccount.isWalletAccount()).isFalse()
  }

  @Test
  fun `Privacy has no Phone number row for a SWARM wallet account`() {
    SignalStore.account.setE164(SWARM_IDENTIFIER)

    assertThat(privacyShowsPhoneNumberRow()).isFalse()
  }

  @Test
  fun `Privacy keeps the Phone number row for an account with a phone number`() {
    SignalStore.account.setE164(PHONE_NUMBER)

    assertThat(privacyShowsPhoneNumberRow()).isTrue()
  }

  /** PrivacySettingsFragment builds the "Phone number" row only when its state says the account has a phone number. */
  private fun privacyShowsPhoneNumberRow(): Boolean {
    val application = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = PrivacySettingsViewModel(PreferenceManager.getDefaultSharedPreferences(application), mockk(relaxed = true))
    ShadowLooper.idleMainLooper()
    return viewModel.state.value!!.hasPhoneNumber
  }
}
