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
}
