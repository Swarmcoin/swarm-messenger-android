/*
 * Copyright 2026 SWARM
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.swarm

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.gcm.FcmUtil
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.testutil.SignalStoreRule

/**
 * SWARM Messenger never uses Firebase Cloud Messaging. The app must therefore always believe FCM is off: that is what
 * keeps its own connection to the chat server open in the background and what makes it tell the server that it
 * fetches its messages itself. If FCM read as on, the server would see a device with no way to receive messages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class SwarmNoFcmTest {

  @get:Rule
  val signalStore = SignalStoreRule()

  @Test
  fun `the build has no FCM`() {
    assertThat(SwarmBuild.FCM).isFalse()
  }

  @Test
  fun `FCM reads as off, even after something stores it as on`() {
    assertThat(SignalStore.account.fcmEnabled).isFalse()

    // What upstream's first-launch migration and its FCM refresh job write.
    SignalStore.account.fcmEnabled = true

    assertThat(SignalStore.account.fcmEnabled).isFalse()
  }

  @Test
  fun `there is never an FCM token`() {
    assertThat(FcmUtil.getToken(ApplicationProvider.getApplicationContext()).isPresent).isFalse()
  }
}
