/*
 * Copyright 2026 SWARM
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.swarm

import android.app.Application
import assertk.assertThat
import assertk.assertions.isFalse
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.util.RemoteConfig

/**
 * SWARM Messenger offers no MobileCoin wallet: upstream's payments talk to MobileCoin's consensus and Fog servers and
 * hold a currency that is not SWARM's. The payments flag must read off whatever the server's kill switch says, so the
 * Payments row and every other payments entry point stay hidden, exactly as upstream hides them when the kill switch
 * is on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class SwarmNoMobileCoinTest {

  companion object {
    private const val KILL_SWITCH = "android.payments.kill"
  }

  @Before
  fun setUp() {
    RemoteConfig.initialized = true
  }

  @After
  fun tearDown() {
    RemoteConfig.REMOTE_VALUES.remove(KILL_SWITCH)
  }

  @Test
  fun `the build has no MobileCoin payments`() {
    assertThat(SwarmBuild.MOBILECOIN_PAYMENTS).isFalse()
  }

  @Test
  fun `the payments flag reads off, whatever the server's kill switch says`() {
    RemoteConfig.REMOTE_VALUES.remove(KILL_SWITCH)
    assertThat(RemoteConfig.payments).isFalse()

    RemoteConfig.REMOTE_VALUES[KILL_SWITCH] = false
    assertThat(RemoteConfig.payments).isFalse()

    RemoteConfig.REMOTE_VALUES[KILL_SWITCH] = true
    assertThat(RemoteConfig.payments).isFalse()
  }
}
