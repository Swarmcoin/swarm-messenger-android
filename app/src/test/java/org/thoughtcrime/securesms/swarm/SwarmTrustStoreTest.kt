/*
 * Copyright 2026 SWARM
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.swarm

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Test
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date
import kotlin.io.path.Path
import kotlin.io.path.inputStream

/**
 * The trust store the app pins for its own servers (`res/raw/whisper.store`, read by
 * `SignalServiceTrustStore` for the chat, CDN and storage URLs).
 *
 * Upstream's store holds exactly one certificate, Signal's private CA, so chat.swarm.green (a Let's Encrypt
 * certificate) could never validate. SWARM's holds exactly the two Let's Encrypt roots. See
 * docs/SWARM-CHANGES.md, "Trust store", for how it was built.
 */
class SwarmTrustStoreTest {

  companion object {
    private val STORE_PATH = Path("src/main/res/raw/whisper.store")
    private const val STORE_PASSWORD = "whisper"

    /** SHA-256 of each root's DER encoding, as published by Let's Encrypt and shipped in the JDK's cacerts. */
    private const val ISRG_ROOT_X1 = "96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6"
    private const val ISRG_ROOT_X2 = "69729b8e15a86efc177a57afb7171dfc64add28c2fca8cf1507e34453ccb1470"

    /** Upstream's pinned certificate, CN=Signal Messenger. */
    private const val SIGNAL_MESSENGER_CA = "ddb0f92bb95c8d6fd202ea6e8cc5ccd182b544f8cd696f47d580659ddc9df65a"

    /** A moment when every certificate of the recorded chain was valid (the leaf: 2026-09-27 to 2026-12-26). */
    private val CHAIN_VALID_AT: Date = Date.from(Instant.parse("2026-10-01T00:00:00Z"))

    private fun sha256(certificate: X509Certificate): String {
      return MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
    }

    private fun loadStore(): KeyStore {
      return KeyStore.getInstance("BKS", BouncyCastleProvider()).apply {
        STORE_PATH.inputStream().use { load(it, STORE_PASSWORD.toCharArray()) }
      }
    }

    /**
     * The JDK's own X.509 and PKIX implementations, named explicitly: the unit-test JVM may have other providers
     * (Conscrypt, an older BouncyCastle) installed ahead of them by other tests.
     */
    private fun certificateFactory(): CertificateFactory = CertificateFactory.getInstance("X.509", "SUN")

    /**
     * Trust anchors from the store, re-parsed by the JDK's certificate factory. The objects BouncyCastle's key store
     * hands back carry BouncyCastle key classes, and the JDK's EC signature code on the CI runner's JDK 21 does not
     * accept those for P-384 (ISRG Root X2) - a test artefact, not something Android does.
     */
    private fun anchors(store: KeyStore): Set<TrustAnchor> {
      return store.aliases().toList().map { alias ->
        val encoded = store.getCertificate(alias).encoded
        TrustAnchor(certificateFactory().generateCertificate(encoded.inputStream()) as X509Certificate, null)
      }.toSet()
    }

    /** The chain chat.swarm.green served on 2026-09-27: leaf, YE2, ISRG Root YE (signed by X2), ISRG Root X2 (signed by X1). */
    private fun recordedChain(): List<X509Certificate> {
      val stream = SwarmTrustStoreTest::class.java.getResourceAsStream("/swarm/chat-swarm-green-chain-2026-09-27.pem")
        ?: error("missing test resource")
      return stream.use { certificateFactory().generateCertificates(it).map { certificate -> certificate as X509Certificate } }
    }

    /** Null when [path] validates to one of [anchors], otherwise why not. */
    private fun validationProblem(path: List<X509Certificate>, anchors: Set<TrustAnchor>): String? {
      val parameters = PKIXParameters(anchors).apply {
        isRevocationEnabled = false
        date = CHAIN_VALID_AT
      }
      return try {
        CertPathValidator.getInstance("PKIX", "SUN").validate(certificateFactory().generateCertPath(path), parameters)
        null
      } catch (e: Exception) {
        e.toString()
      }
    }
  }

  @Test
  fun `the store holds exactly the two Let's Encrypt roots`() {
    val store = loadStore()
    val fingerprints = store.aliases().toList().map { sha256(store.getCertificate(it) as X509Certificate) }

    assertThat(fingerprints).containsExactlyInAnyOrder(ISRG_ROOT_X1, ISRG_ROOT_X2)
    assertThat(fingerprints.contains(SIGNAL_MESSENGER_CA)).isFalse()
  }

  @Test
  fun `the store is a version 2 BKS store, which Android reads`() {
    val header = STORE_PATH.inputStream().use { it.readNBytes(4) }
    assertThat(header.toList()).isEqualTo(listOf<Byte>(0, 0, 0, 2))
  }

  @Test
  fun `chat_swarm_green's recorded chain validates against the store`() {
    val chain = recordedChain()
    val anchors = anchors(loadStore())

    assertThat(chain.first().subjectX500Principal.name).isEqualTo("CN=chat.swarm.green")

    // Ending at ISRG Root YE, whose certificate is signed by ISRG Root X2.
    assertThat(validationProblem(chain.take(3), anchors), "path to ISRG Root X2").isNull()
    // Ending at the cross-signed ISRG Root X2, signed by ISRG Root X1.
    assertThat(validationProblem(chain, anchors), "path to ISRG Root X1").isNull()
  }
}
