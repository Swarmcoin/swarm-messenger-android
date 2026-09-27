/*
 * Copyright 2026 SWARM
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.swarm

import org.signal.core.util.logging.Log
import org.signal.libsignal.net.Network
import org.signal.network.config.SignalServiceConfiguration
import org.thoughtcrime.securesms.BuildConfig
import org.whispersystems.signalservice.api.util.CredentialsProvider
import org.whispersystems.signalservice.api.websocket.HealthMonitor
import org.whispersystems.signalservice.internal.websocket.OkHttpWebSocketConnection
import org.whispersystems.signalservice.internal.websocket.WebSocketConnection
import java.util.Optional

/**
 * Where SWARM Messenger's connections go, and the one rule that holds whatever libsignal is in the build:
 * **no connection ever reaches Signal's servers.**
 *
 * libsignal's own network stack (`org.signal.libsignal.net.Network`) does not take a hostname. It takes an
 * environment, and upstream libsignal knows only two: Signal's staging and Signal's production. SWARM's
 * build of libsignal ([Swarm-Official/swarm-libsignal](https://github.com/Swarm-Official/swarm-libsignal))
 * adds `Network.Environment.SWARM` for `chat.swarm.green`, and the app is built against it (0.102.2-swarm.1,
 * [BuildConfig.SWARM_LIBSIGNAL_NET] true), so [guard] is a no-op and the chat websocket is libsignal's own. Should the
 * app ever be built against upstream libsignal ([BuildConfig.SWARM_LIBSIGNAL_NET] false):
 *
 * - every libsignal-net connection is refused ([guard] gives the [Network] an invalid proxy, which libsignal
 *   documents as "refuses to make any new connections"), so contact discovery, secure value recovery, key
 *   transparency and libsignal's chat connection cannot dial Signal;
 * - the chat websocket is carried by upstream's own OkHttp implementation ([chatConnection]), which connects to
 *   the app's configured `SIGNAL_URL` (`chat.swarm.green`) and trusts the app's own trust store. It receives
 *   messages and carries plain requests; the few typed libsignal chat APIs (sending messages, usernames,
 *   account attributes over gRPC) need libsignal's connection and fail cleanly until the SWARM build is in.
 *
 * REST (registration, attachments, profiles, storage) never used libsignal-net and always goes to the SWARM
 * hosts in `BuildConfig`.
 */
object SwarmNetwork {

  private val TAG = Log.tag(SwarmNetwork::class)

  /** Whether libsignal in this build carries the SWARM network environment. */
  @JvmStatic
  val libsignalHasSwarmEnvironment: Boolean
    get() = BuildConfig.SWARM_LIBSIGNAL_NET

  /**
   * Makes sure a libsignal [Network] cannot reach Signal. With the SWARM build of libsignal and the SWARM
   * environment selected, there is nothing to do.
   */
  @JvmStatic
  fun guard(network: Network) {
    if (libsignalHasSwarmEnvironment) {
      return
    }

    Log.w(TAG, "This build's libsignal has no SWARM network environment. Blocking every libsignal-net connection so none can reach Signal's servers.")
    network.setInvalidProxy()
  }

  /**
   * The chat websocket to use when libsignal cannot provide one (see the class documentation): upstream's
   * OkHttp websocket, dialing `SIGNAL_URL` from [configuration].
   */
  @JvmStatic
  fun chatConnection(
    name: String,
    configuration: SignalServiceConfiguration,
    credentialsProvider: CredentialsProvider?,
    healthMonitor: HealthMonitor,
    allowStories: Boolean
  ): WebSocketConnection {
    return OkHttpWebSocketConnection(
      name,
      configuration,
      Optional.ofNullable(credentialsProvider),
      BuildConfig.SIGNAL_AGENT,
      healthMonitor,
      allowStories
    )
  }
}
