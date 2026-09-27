/*
 * Copyright 2026 SWARM
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.swarm

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Test
import org.signal.core.util.Base64
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.zkgroup.GenericServerPublicParams
import org.signal.libsignal.zkgroup.ServerPublicParams
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.s3.S3
import java.lang.reflect.Modifier
import java.net.URI

/**
 * The endpoint guard: SWARM Messenger is configured for SWARM's servers and nothing else.
 *
 * The same rule is enforced on the built APK by scripts/swarm/endpoint_scan.py; this test catches a
 * regression before the APK exists, and pins the server-generated values the app must carry.
 */
class SwarmEndpointsTest {

  companion object {
    /** Any of Signal's service domains, at any depth. */
    private val SIGNAL_HOST = Regex(
      "(^|[^A-Za-z0-9.-])([A-Za-z0-9-]+\\.)*(signal\\.org|signal\\.art|signalcaptchas\\.org|signaldonations\\.org|whispersystems\\.org)([^A-Za-z0-9-]|$)",
      RegexOption.IGNORE_CASE
    )

    /** The sealed-sender trust root of chat.swarm.green (shared/staging-public-params.json, 2026-09-27). */
    private const val SWARM_TRUST_ROOT = "BSgjRy7ssr0kXjLd9fVTuWpomsxWMm31NG1TKTKbiyMu"

    /** The first characters of Signal's own production zkgroup parameters, which must never come back. */
    private const val SIGNAL_ZKGROUP_PREFIX = "AMhf5ywVwITZMsff"

    private fun isSwarmHost(host: String): Boolean {
      return host == "swarm.green" || host.endsWith(".swarm.green") || host.endsWith(".swarm.invalid")
    }

    private fun hostOf(url: String): String = URI(url).host ?: error("no host in $url")
  }

  @Test
  fun `every server URL the app is built with is a SWARM host`() {
    val urls = mapOf(
      "SIGNAL_URL" to BuildConfig.SIGNAL_URL,
      "STORAGE_URL" to BuildConfig.STORAGE_URL,
      "SIGNAL_CDN_URL" to BuildConfig.SIGNAL_CDN_URL,
      "SIGNAL_CDN2_URL" to BuildConfig.SIGNAL_CDN2_URL,
      "SIGNAL_CDN3_URL" to BuildConfig.SIGNAL_CDN3_URL,
      "SIGNAL_CDSI_URL" to BuildConfig.SIGNAL_CDSI_URL,
      "SIGNAL_SVR2_URL" to BuildConfig.SIGNAL_SVR2_URL,
      "SIGNAL_SFU_URL" to BuildConfig.SIGNAL_SFU_URL,
      "SIGNAL_STAGING_SFU_URL" to BuildConfig.SIGNAL_STAGING_SFU_URL,
      "SIGNAL_CAPTCHA_URL" to BuildConfig.SIGNAL_CAPTCHA_URL,
      "RECAPTCHA_PROOF_URL" to BuildConfig.RECAPTCHA_PROOF_URL,
      "BADGE_STATIC_ROOT" to BuildConfig.BADGE_STATIC_ROOT
    )

    for ((name, url) in urls) {
      assertThat(isSwarmHost(hostOf(url)), name + " = " + url).isTrue()
    }

    for ((name, host) in mapOf(
      "CONTENT_PROXY_HOST" to BuildConfig.CONTENT_PROXY_HOST,
      "SIGNAL_SERVICE_STATUS_URL" to BuildConfig.SIGNAL_SERVICE_STATUS_URL,
      "SWARM_STATIC_HOST" to BuildConfig.SWARM_STATIC_HOST
    )) {
      assertThat(isSwarmHost(host), "$name = $host").isTrue()
    }
  }

  @Test
  fun `the chat host is chat_swarm_green`() {
    assertThat(BuildConfig.SIGNAL_URL).isEqualTo("https://chat.swarm.green")
    assertThat(BuildConfig.SIGNAL_CDN_URL).isEqualTo("https://cdn.chat.swarm.green")
  }

  @Test
  fun `no BuildConfig value names a Signal host`() {
    val offenders = mutableListOf<String>()

    for (field in BuildConfig::class.java.fields) {
      if (!Modifier.isStatic(field.modifiers)) {
        continue
      }
      val values: List<String> = when (val value = field.get(null)) {
        is String -> listOf(value)
        is Array<*> -> value.filterIsInstance<String>()
        else -> emptyList()
      }
      values.filter { SIGNAL_HOST.containsMatchIn(it) }.forEach { offenders += "${field.name} = $it" }
    }

    assertThat(offenders).isEmpty()
  }

  @Test
  fun `there are no built-in fallback addresses`() {
    for (ips in listOf(
      BuildConfig.SIGNAL_SERVICE_IPS,
      BuildConfig.SIGNAL_STORAGE_IPS,
      BuildConfig.SIGNAL_CDN_IPS,
      BuildConfig.SIGNAL_CDN2_IPS,
      BuildConfig.SIGNAL_CDN3_IPS,
      BuildConfig.SIGNAL_SFU_IPS,
      BuildConfig.SIGNAL_CONTENT_PROXY_IPS,
      BuildConfig.SIGNAL_CDSI_IPS,
      BuildConfig.SIGNAL_SVR2_IPS
    )) {
      assertThat(ips.toList()).isEmpty()
    }
  }

  @Test
  fun `sealed sender trusts only the SWARM server's root`() {
    assertThat(BuildConfig.UNIDENTIFIED_SENDER_TRUST_ROOTS.toList()).isEqualTo(listOf(SWARM_TRUST_ROOT))

    // A real Curve25519 public key, not a placeholder.
    ECPublicKey(Base64.decode(SWARM_TRUST_ROOT))
  }

  @Test
  fun `the zkgroup parameters are the SWARM server's and decode`() {
    assertThat(BuildConfig.ZKGROUP_SERVER_PUBLIC_PARAMS.startsWith(SIGNAL_ZKGROUP_PREFIX)).isFalse()

    ServerPublicParams(Base64.decode(BuildConfig.ZKGROUP_SERVER_PUBLIC_PARAMS))
    GenericServerPublicParams(Base64.decode(BuildConfig.GENERIC_SERVER_PUBLIC_PARAMS))
    GenericServerPublicParams(Base64.decode(BuildConfig.BACKUP_SERVER_PUBLIC_PARAMS))

    // Call links are signed with the server's calling parameters and backups with its chat parameters, so
    // the two generic sets must not be the same one (the desktop learnt this the hard way, see
    // docs/SWARM-CHANGES.md).
    assertThat(BuildConfig.GENERIC_SERVER_PUBLIC_PARAMS).isNotEqualTo(BuildConfig.BACKUP_SERVER_PUBLIC_PARAMS)
  }

  @Test
  fun `the app never updates itself from Signal's update feed`() {
    assertThat(BuildConfig.MANAGES_APP_UPDATES).isFalse()
    assertThat(BuildConfig.APK_UPDATE_MANIFEST_URL).isNull()
  }

  @Test
  fun `static files come from SWARM's static host`() {
    assertThat(S3.s3Url("/static/badges/example.png").host).isEqualTo("static.swarm.green")
  }

  @Test
  fun `libsignal networking is either SWARM's or switched off`() {
    if (BuildConfig.SWARM_LIBSIGNAL_NET) {
      assertThat(BuildConfig.LIBSIGNAL_NET_ENV.name.startsWith("SWARM"), BuildConfig.LIBSIGNAL_NET_ENV.name).isTrue()
    } else {
      assertThat(SwarmNetwork.libsignalHasSwarmEnvironment).isFalse()
    }
  }

  @Test
  fun `no Signal payment keys are built in`() {
    assertThat(BuildConfig.STRIPE_PUBLISHABLE_KEY).isEmpty()
    assertThat(BuildConfig.GIPHY_API_KEY).isEmpty()
  }
}
