/*
 * Copyright 2026 SWARM
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.swarm

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import org.junit.Test
import org.signal.core.util.Base64
import org.signal.libsignal.protocol.IdentityKeyPair
import java.math.BigInteger
import java.security.MessageDigest

/**
 * SWARM addition (wallet sign-in): the derivation that ties a SWARM account to a wallet recovery phrase, and the
 * challenge it signs.
 *
 * The three vectors are the contract with the server and the desktop. The identical values are in the chat server's
 * `SwarmWalletIdentityTest` (Swarm-Official/swarm-messenger-server, service/src/test/.../swarm/) and in SWARM
 * Messenger desktop's `ts/test-node/swarm/walletIdentity_test.node.ts`. If this derivation disagrees with them, this
 * derivation is wrong: a silent change would make every account that already exists unreachable.
 */
class SwarmWalletIdentityTest {

  private data class Vector(
    val name: String,
    val entropyHex: String,
    val aciPublicKey: String,
    val pniPublicKey: String,
    val accountIdentifier: String
  )

  companion object {
    private val ALL_ZERO = Vector(
      name = "all-zero entropy",
      entropyHex = "0000000000000000000000000000000000000000000000000000000000000000",
      aciPublicKey = "BdUup/KTUWFdGc1rkQy8qqoAXARjDuW2b32p1STJMl5Y",
      pniPublicKey = "Bb/DNv+zf1/4omFOsikRqikH98rBEEbzHpAnRWjNlGAe",
      accountIdentifier = "+88810866442360"
    )

    private val COUNTING = Vector(
      name = "counting entropy",
      entropyHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
      aciPublicKey = "BUOazbfLOeK5YwjvL8ASlC5sCw/d+FZZ+q6SUesG1kJw",
      pniPublicKey = "BVg+L0CFHf5JJmbswnfeloNIC8Tr1UDsOg9fKfqBukZj",
      accountIdentifier = "+88844571413476"
    )

    private val ALL_ONES = Vector(
      name = "all-ones entropy",
      entropyHex = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
      aciPublicKey = "BQ6Pzj4KiojM/8GeHWZRfKPSaPVN6SDYaala/FDHmvUL",
      pniPublicKey = "BdmZayL61kzx0DrCVNQcAkzN7pxBLroSZtuMLAfjPJ8V",
      accountIdentifier = "+88834279664637"
    )

    private val VECTORS = listOf(ALL_ZERO, COUNTING, ALL_ONES)

    private fun phraseFor(vector: Vector): String {
      require(vector.entropyHex.length == 64) { "${vector.name}: the entropy must be 32 bytes, 64 hex digits" }
      return Bip39.entropyToPhrase(ByteArray(32) { vector.entropyHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
    }

    private fun base64(bytes: ByteArray): String = Base64.encodeWithPadding(bytes)

    private val CHALLENGE = ByteArray(SwarmWalletIdentity.CHALLENGE_BYTES) { 0x11 }
  }

  @Test
  fun `derives the keys and identifier the server expects`() {
    for (vector in VECTORS) {
      val identity = SwarmWalletIdentity.derive(phraseFor(vector))

      assertThat(base64(identity.aciIdentityKeyPair.publicKey.serialize()), "${vector.name}: ACI identity key").isEqualTo(vector.aciPublicKey)
      assertThat(base64(identity.pniIdentityKeyPair.publicKey.serialize()), "${vector.name}: PNI identity key").isEqualTo(vector.pniPublicKey)
      assertThat(identity.accountIdentifier, "${vector.name}: account identifier").isEqualTo(vector.accountIdentifier)
    }
  }

  @Test
  fun `gives the same answer however the phrase was typed`() {
    val typed = SwarmWalletIdentity.derive("  " + phraseFor(ALL_ZERO).uppercase() + "  ")
    assertThat(typed.accountIdentifier).isEqualTo(ALL_ZERO.accountIdentifier)
    assertThat(base64(typed.aciIdentityKeyPair.publicKey.serialize())).isEqualTo(ALL_ZERO.aciPublicKey)
  }

  @Test
  fun `gives different keys for the ACI and the PNI, and different accounts for different phrases`() {
    for (vector in VECTORS) {
      assertThat(vector.aciPublicKey).isNotEqualTo(vector.pniPublicKey)
    }
    assertThat(VECTORS.map { SwarmWalletIdentity.derive(phraseFor(it)).accountIdentifier }.toSet()).hasSize(VECTORS.size)
  }

  @Test
  fun `the private key survives libsignal unchanged by the clamp`() {
    // libsignal clamps a private key when it takes one. The derivation clamps first, so what it hands libsignal and
    // what libsignal hands back must be the same 32 bytes; otherwise the key in the protocol store would not be the
    // key the identifier was derived from.
    val identity = SwarmWalletIdentity.derive(phraseFor(ALL_ZERO))
    val serialized = identity.aciIdentityKeyPair.privateKey.serialize()

    assertThat(serialized).hasSize(32)
    assertThat(serialized[0].toInt() and 0b111).isEqualTo(0)
    assertThat(serialized[31].toInt() and 0b1100_0000).isEqualTo(0b0100_0000)

    // And the key pair round-trips through libsignal's own serialization, as the protocol store will do it.
    val roundTripped = IdentityKeyPair(identity.aciIdentityKeyPair.serialize())
    assertThat(base64(roundTripped.publicKey.serialize())).isEqualTo(ALL_ZERO.aciPublicKey)
  }

  @Test
  fun `derives identifiers shaped like one, and nothing else is mistaken for one`() {
    for (vector in VECTORS) {
      assertThat(SwarmWalletIdentity.isSwarmAccountIdentifier(vector.accountIdentifier)).isTrue()
      assertThat(vector.accountIdentifier.length).isEqualTo("+888".length + 11)
    }
    for (value in listOf("+14155550123", "+8881854292138", "+888185429213820", "+88808542921382", "+8881854292138a", "888185429213820", "+889185429213820", "", null)) {
      assertThat(SwarmWalletIdentity.isSwarmAccountIdentifier(value), value.toString()).isFalse()
    }
  }

  @Test
  fun `derives the identifier the documented way`() {
    val identity = SwarmWalletIdentity.derive(phraseFor(ALL_ZERO))
    val digest = MessageDigest.getInstance("SHA-256").run {
      update("SWARM-Messenger-e164-v1".toByteArray(Charsets.UTF_8))
      update(identity.aciIdentityKeyPair.publicKey.serialize())
      digest()
    }
    val high = BigInteger(1, digest.copyOfRange(0, 8))
    val expected = "+888" + high.mod(BigInteger.valueOf(90_000_000_000L)).add(BigInteger.valueOf(10_000_000_000L))

    assertThat(SwarmWalletIdentity.accountIdentifierFor(identity.aciIdentityKeyPair.publicKey)).isEqualTo(expected)
  }

  @Test
  fun `signs the message the server verifies`() {
    val aci = SwarmWalletIdentity.derive(phraseFor(ALL_ZERO)).aciIdentityKeyPair
    val signature = SwarmWalletIdentity.signChallenge(aci, CHALLENGE)

    assertThat(aci.publicKey.publicKey.verifySignature(SwarmWalletIdentity.challengeMessage(aci.publicKey, CHALLENGE), signature)).isTrue()
  }

  @Test
  fun `builds the message from the label, the key and the challenge`() {
    val aci = SwarmWalletIdentity.derive(phraseFor(ALL_ZERO)).aciIdentityKeyPair
    val expected = "SWARM-Messenger-wallet-registration-v1".toByteArray(Charsets.UTF_8) + aci.publicKey.serialize() + CHALLENGE

    assertThat(base64(SwarmWalletIdentity.challengeMessage(aci.publicKey, CHALLENGE))).isEqualTo(base64(expected))
  }

  @Test(expected = IllegalArgumentException::class)
  fun `will not sign a challenge of the wrong length`() {
    val aci = SwarmWalletIdentity.derive(phraseFor(ALL_ZERO)).aciIdentityKeyPair
    SwarmWalletIdentity.signChallenge(aci, ByteArray(SwarmWalletIdentity.CHALLENGE_BYTES - 1))
  }

  @Test
  fun `a signature does not answer a different challenge`() {
    val aci = SwarmWalletIdentity.derive(phraseFor(ALL_ZERO)).aciIdentityKeyPair
    val signature = SwarmWalletIdentity.signChallenge(aci, CHALLENGE)
    val other = ByteArray(SwarmWalletIdentity.CHALLENGE_BYTES) { 0x12 }

    assertThat(aci.publicKey.publicKey.verifySignature(SwarmWalletIdentity.challengeMessage(aci.publicKey, other), signature)).isFalse()
  }

  @Test
  fun `a signature by another key does not answer for this one`() {
    val victim = SwarmWalletIdentity.derive(phraseFor(ALL_ZERO)).aciIdentityKeyPair
    val attacker = SwarmWalletIdentity.derive(phraseFor(COUNTING)).aciIdentityKeyPair

    // The attacker signs the message that names the victim's key.
    val forged = attacker.privateKey.calculateSignature(SwarmWalletIdentity.challengeMessage(victim.publicKey, CHALLENGE))

    assertThat(victim.publicKey.publicKey.verifySignature(SwarmWalletIdentity.challengeMessage(victim.publicKey, CHALLENGE), forged)).isFalse()
  }

  @Test
  fun `the identity never prints its keys`() {
    assertThat(SwarmWalletIdentity.derive(phraseFor(ALL_ZERO)).toString().contains(ALL_ZERO.accountIdentifier)).isFalse()
  }
}
