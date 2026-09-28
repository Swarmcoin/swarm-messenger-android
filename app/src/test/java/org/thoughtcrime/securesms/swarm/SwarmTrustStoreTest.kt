/*
 * Copyright 2026 SWARM
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.swarm

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotNull
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
 * certificate) could never validate. SWARM's holds Let's Encrypt's roots: ISRG Root X1 and X2, which validate what
 * chat.swarm.green serves today through Let's Encrypt's cross-signatures, and the newer Root YE and Root YR, which
 * are what validates once Let's Encrypt stops sending those cross-signatures. See docs/SWARM-CHANGES.md,
 * "Trust store", for how it was built.
 */
class SwarmTrustStoreTest {

  companion object {
    private val STORE_PATH = Path("src/main/res/raw/whisper.store")
    private const val STORE_PASSWORD = "whisper"

    /** SHA-256 of each root's DER encoding, as published by Let's Encrypt (and, for X1 and X2, in the JDK's cacerts). */
    private const val ISRG_ROOT_X1 = "96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6"
    private const val ISRG_ROOT_X2 = "69729b8e15a86efc177a57afb7171dfc64add28c2fca8cf1507e34453ccb1470"
    private const val ISRG_ROOT_YE = "e14ffcad5b0025731006caa43a121a22d8e9700f4fb9cf852f02a708aa5d5666"
    private const val ISRG_ROOT_YR = "e57b7e6f150c419102e8d5c055729ff967b9d1a829bf00cec89ca604ebf4a86f"

    /** Upstream's pinned certificate, CN=Signal Messenger. */
    private const val SIGNAL_MESSENGER_CA = "ddb0f92bb95c8d6fd202ea6e8cc5ccd182b544f8cd696f47d580659ddc9df65a"

    /** A moment when every certificate of the recorded chat.swarm.green chain was valid (the leaf: 2026-09-27 to 2026-12-26). */
    private val CHAIN_VALID_AT: Date = Date.from(Instant.parse("2026-10-01T00:00:00Z"))

    /** A moment inside the validity of the recorded test-site certificates (issued 2026-09-24/25, valid to 2026-10-01). */
    private val TEST_SITES_VALID_AT: Date = Date.from(Instant.parse("2026-09-28T12:00:00Z"))

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
     * The store's certificates by fingerprint, re-parsed by the JDK's certificate factory. The objects BouncyCastle's
     * key store hands back carry BouncyCastle key classes, and the JDK's EC signature code on the CI runner's JDK 21
     * does not accept those for P-384 (ISRG Root X2, Root YE) - a test artefact, not something Android does.
     */
    private fun storeCertificates(store: KeyStore = loadStore()): Map<String, X509Certificate> {
      return store.aliases().toList().map { alias ->
        val encoded = store.getCertificate(alias).encoded
        certificateFactory().generateCertificate(encoded.inputStream()) as X509Certificate
      }.associateBy { sha256(it) }
    }

    private fun anchors(certificates: Collection<X509Certificate>): Set<TrustAnchor> {
      return certificates.map { TrustAnchor(it, null) }.toSet()
    }

    private fun pem(resource: String): List<X509Certificate> {
      val stream = SwarmTrustStoreTest::class.java.getResourceAsStream(resource) ?: error("missing test resource $resource")
      return stream.use { certificateFactory().generateCertificates(it).map { certificate -> certificate as X509Certificate } }
    }

    /** The chain chat.swarm.green served on 2026-09-27 (and, byte for byte, on 2026-09-28): leaf, YE2, Root YE (signed by X2), ISRG Root X2 (signed by X1). */
    private fun recordedChain(): List<X509Certificate> = pem("/swarm/chat-swarm-green-chain-2026-09-27.pem")

    /** Null when [path] validates to one of [anchors] at [date], otherwise why not. */
    private fun validationProblem(path: List<X509Certificate>, anchors: Set<TrustAnchor>, date: Date = CHAIN_VALID_AT): String? {
      val parameters = PKIXParameters(anchors).apply {
        isRevocationEnabled = false
        this.date = date
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
  fun `the store holds exactly the four Let's Encrypt roots`() {
    val fingerprints = storeCertificates().keys

    assertThat(fingerprints).containsExactlyInAnyOrder(ISRG_ROOT_X1, ISRG_ROOT_X2, ISRG_ROOT_YE, ISRG_ROOT_YR)
    assertThat(fingerprints.contains(SIGNAL_MESSENGER_CA)).isFalse()
  }

  @Test
  fun `the store is a version 2 BKS store, which Android reads`() {
    val header = STORE_PATH.inputStream().use { it.readNBytes(4) }
    assertThat(header.toList()).isEqualTo(listOf<Byte>(0, 0, 0, 2))
  }

  @Test
  fun `every root is a self-signed certificate authority`() {
    for ((fingerprint, root) in storeCertificates()) {
      assertThat(root.subjectX500Principal, fingerprint).isEqualTo(root.issuerX500Principal)
      assertThat(root.basicConstraints, "$fingerprint is a CA").isGreaterThanOrEqualTo(0)
      // Its own key verifies its own signature (with the JDK's own signature providers, for the reason above).
      root.verify(root.publicKey, if (root.publicKey.algorithm == "EC") "SunEC" else "SunRsaSign")
    }
  }

  @Test
  fun `Root YE and Root YR hold the keys that ISRG Root X2 and X1 vouch for`() {
    val roots = storeCertificates()
    val x1 = roots.getValue(ISRG_ROOT_X1)
    val x2 = roots.getValue(ISRG_ROOT_X2)
    val ye = roots.getValue(ISRG_ROOT_YE)
    val yr = roots.getValue(ISRG_ROOT_YR)

    // Root YE as chat.swarm.green serves it, signed by ISRG Root X2.
    val yeByX2 = recordedChain()[2]
    // Root YR as Let's Encrypt publishes it cross-signed by ISRG Root X1 (letsencrypt.org/certs/gen-y/root-yr-by-x1.pem).
    val yrByX1 = pem("/swarm/letsencrypt-root-yr-by-x1.pem").single()

    assertThat(validationProblem(listOf(yeByX2), anchors(listOf(x2))), "Root YE cross-signed by X2").isNull()
    assertThat(validationProblem(listOf(yrByX1), anchors(listOf(x1))), "Root YR cross-signed by X1").isNull()

    assertThat(ye.subjectX500Principal).isEqualTo(yeByX2.subjectX500Principal)
    assertThat(ye.publicKey.encoded.toList()).isEqualTo(yeByX2.publicKey.encoded.toList())
    assertThat(yr.subjectX500Principal).isEqualTo(yrByX1.subjectX500Principal)
    assertThat(yr.publicKey.encoded.toList()).isEqualTo(yrByX1.publicKey.encoded.toList())
  }

  @Test
  fun `chat_swarm_green's recorded chain validates against the store`() {
    val chain = recordedChain()
    val anchors = anchors(storeCertificates().values)

    assertThat(chain.first().subjectX500Principal.name).isEqualTo("CN=chat.swarm.green")

    // Ending at Root YE, the root Let's Encrypt issues from now.
    assertThat(validationProblem(chain.take(2), anchors), "path to Root YE").isNull()
    // Ending at Root YE's certificate that ISRG Root X2 signed.
    assertThat(validationProblem(chain.take(3), anchors), "path to ISRG Root X2").isNull()
    // Ending at the cross-signed ISRG Root X2, signed by ISRG Root X1.
    assertThat(validationProblem(chain, anchors), "path to ISRG Root X1").isNull()
  }

  @Test
  fun `chat_swarm_green still validates when Let's Encrypt stops sending the cross-signatures`() {
    val leafAndIntermediate = recordedChain().take(2)
    val roots = storeCertificates()

    assertThat(validationProblem(leafAndIntermediate, anchors(listOf(roots.getValue(ISRG_ROOT_YE)))), "Root YE alone").isNull()
    // Without Root YE, the X roots cannot complete that shorter chain: this is the lock-out the Y roots prevent.
    assertThat(validationProblem(leafAndIntermediate, anchors(listOf(roots.getValue(ISRG_ROOT_X1), roots.getValue(ISRG_ROOT_X2)))), "X1 and X2 alone").isNotNull()
  }

  @Test
  fun `chains that end at Root YE or Root YR, as Let's Encrypt's test sites serve them, validate`() {
    val roots = storeCertificates()
    val anchors = anchors(roots.values)
    val onlyX = anchors(listOf(roots.getValue(ISRG_ROOT_X1), roots.getValue(ISRG_ROOT_X2)))

    // valid.ye.test-certs.letsencrypt.org and valid.yr.test-certs.letsencrypt.org, 2026-09-28: leaf and intermediate only.
    for ((name, chain) in listOf("ECDSA, YE1" to pem("/swarm/letsencrypt-test-site-ye-chain-2026-09-28.pem"), "RSA, YR1" to pem("/swarm/letsencrypt-test-site-yr-chain-2026-09-28.pem"))) {
      assertThat(validationProblem(chain, anchors, date = TEST_SITES_VALID_AT), name).isNull()
      assertThat(validationProblem(chain, onlyX, date = TEST_SITES_VALID_AT), "$name without the Y roots").isNotNull()
    }
  }
}
