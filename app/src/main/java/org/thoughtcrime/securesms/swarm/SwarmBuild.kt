/*
 * Copyright 2026 SWARM
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.swarm

/**
 * SWARM Messenger build switches. Each one names the upstream feature it turns off and why, so that an
 * upstream merge shows at a glance what SWARM deliberately does not ship. See docs/SWARM-CHANGES.md.
 */
object SwarmBuild {
  /** Alternate launcher icons. Upstream's alternates are Signal's logo in several colours. */
  const val ALTERNATE_APP_ICONS: Boolean = false

  /** In-app donations. They pay Signal Technology Foundation through Signal's Stripe and PayPal accounts. */
  const val DONATIONS: Boolean = false

  /**
   * Firebase Cloud Messaging. SWARM has no Firebase project, so nothing is pushed through Google: the app keeps its own
   * connection to the chat server and tells the server that it fetches its messages itself, as upstream does on a
   * phone without Google Play services. With this off, [org.thoughtcrime.securesms.keyvalue.AccountValues.fcmEnabled]
   * always reads false, whatever upstream's first-launch migration stored there.
   */
  const val FCM: Boolean = false
}
