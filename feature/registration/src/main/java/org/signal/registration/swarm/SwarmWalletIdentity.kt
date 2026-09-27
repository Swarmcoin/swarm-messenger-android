/*
 * Copyright 2026 SWARM
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.swarm

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.kdf.HKDF
import java.math.BigInteger
import java.security.MessageDigest

/**
 * SWARM addition (wallet sign-in). One recovery phrase, two things derived from it: the SWARM wallet's keys (in the
 * wallet's own code) and this account's Signal identity key pairs (here). Restoring the phrase therefore restores the
 * account, on any device, with no server-side backup and nothing to remember but the words.
 *
 * Everything below is libsignal's own cryptography and nothing else: libsignal's HKDF-SHA256 stretches the seed,
 * libsignal's key types hold the result, libsignal's XEdDSA signs the server's challenge. No primitive is
 * implemented here.
 *
 * The derivation is the contract with the chat server (`SwarmWalletIdentity.java` in Swarm-Official/swarm-messenger-
 * server) and with SWARM Messenger for desktop (`ts/util/swarm/walletIdentity.node.ts`): all three carry the same
 * test vectors. Changing a label, a length or the clamp orphans every account that already exists.
 *
 * The seed and the private keys never leave this process and are never logged. What goes to the server is a public
 * key and a signature.
 */
object SwarmWalletIdentity {

  /** Domain separation for each derived key. As load-bearing as a database migration; pinned by the tests. */
  const val ACI_IDENTITY_LABEL = "SWARM-Messenger-ACI-identity-v1"
  const val PNI_IDENTITY_LABEL = "SWARM-Messenger-PNI-identity-v1"

  /** Domain separation for the account identifier. Must match the server's SwarmWalletIdentity. */
  const val E164_LABEL = "SWARM-Messenger-e164-v1"

  /** Domain separation for what is signed to prove possession of the identity key. Must match the server. */
  const val CHALLENGE_LABEL = "SWARM-Messenger-wallet-registration-v1"

  /** ITU-T calling code 888: non-geographic, never dialable. See the server's docs/WALLET-SIGN-IN.md. */
  const val E164_PREFIX = "+888"

  /** How many digits follow the prefix. */
  const val NATIONAL_DIGITS = 11

  /** The length of the server's registration challenge, in bytes. */
  const val CHALLENGE_BYTES = 32

  /** A Curve25519 private key scalar, in bytes. */
  private const val PRIVATE_KEY_BYTES = 32

  /** The smallest 11-digit national number: the derivation never produces a leading zero. */
  private val NATIONAL_FLOOR: BigInteger = BigInteger.TEN.pow(NATIONAL_DIGITS - 1)

  /** How many identifiers exist: 9 x 10^10. */
  private val NATIONAL_SPACE: BigInteger = BigInteger.valueOf(9).multiply(NATIONAL_FLOOR)

  /**
   * The account's identity, derived from its recovery phrase.
   *
   * @property accountIdentifier the synthetic, non-dialable E.164-shaped identifier the server keys the account by.
   *   It exists so that Signal's account model keeps working unchanged, and no screen ever shows it.
   */
  class Identity(
    val aciIdentityKeyPair: IdentityKeyPair,
    val pniIdentityKeyPair: IdentityKeyPair,
    val accountIdentifier: String
  ) {
    override fun toString(): String = "SwarmWalletIdentity.Identity(<redacted>)"
  }

  /**
   * The identity for a recovery phrase. Deterministic: the same words give the same keys and the same identifier on
   * this device and on the next one. That is the entire feature.
   */
  fun derive(phrase: String): Identity {
    val seed = Bip39.phraseToSeed(phrase)
    try {
      val aci = identityKeyPairFromSeed(seed, ACI_IDENTITY_LABEL)
      val pni = identityKeyPairFromSeed(seed, PNI_IDENTITY_LABEL)
      return Identity(aci, pni, accountIdentifierFor(aci.publicKey))
    } finally {
      seed.fill(0)
    }
  }

  /**
   * One identity key pair from the seed and a label: HKDF-SHA256 over the seed (no salt, the label as info, 32
   * bytes), then the X25519 clamp - the low three bits and the high bit cleared, the second-highest set, as in RFC
   * 7748 - before handing the bytes to libsignal. libsignal clamps the same way internally; clamping first means the
   * bytes derived here and the bytes libsignal serializes back are the same bytes.
   */
  fun identityKeyPairFromSeed(seed: ByteArray, label: String): IdentityKeyPair {
    val derived = HKDF.deriveSecrets(seed, label.toByteArray(Charsets.UTF_8), PRIVATE_KEY_BYTES)
    try {
      derived[0] = (derived[0].toInt() and 0b1111_1000).toByte()
      derived[PRIVATE_KEY_BYTES - 1] = ((derived[PRIVATE_KEY_BYTES - 1].toInt() and 0b0111_1111) or 0b0100_0000).toByte()

      val privateKey = ECPrivateKey(derived)
      return IdentityKeyPair(IdentityKey(privateKey.getPublicKey()), privateKey)
    } finally {
      derived.fill(0)
    }
  }

  /**
   * The account identifier for an identity public key: `+888` then
   * `10^10 + (the high 8 bytes of SHA-256(label || key), unsigned, mod 9 x 10^10)`. The server derives the same string
   * from the same key and refuses a registration where the two disagree.
   */
  fun accountIdentifierFor(identityKey: IdentityKey): String {
    val digest = MessageDigest.getInstance("SHA-256").run {
      update(E164_LABEL.toByteArray(Charsets.UTF_8))
      update(identityKey.serialize())
      digest()
    }
    val high = BigInteger(1, digest.copyOfRange(0, 8))
    return E164_PREFIX + high.mod(NATIONAL_SPACE).add(NATIONAL_FLOOR).toString()
  }

  /** Whether a string is shaped like a SWARM account identifier. Shape only. */
  fun isSwarmAccountIdentifier(value: String?): Boolean {
    if (value == null || value.length != E164_PREFIX.length + NATIONAL_DIGITS || !value.startsWith(E164_PREFIX)) {
      return false
    }
    val national = value.substring(E164_PREFIX.length)
    return national[0] in '1'..'9' && national.all { it in '0'..'9' }
  }

  /**
   * What the server wants signed: `"SWARM-Messenger-wallet-registration-v1" || identityKey || challenge`. The identity
   * key is inside the message so a captured signature cannot be presented as a signature by some other key.
   */
  fun challengeMessage(identityKey: IdentityKey, challenge: ByteArray): ByteArray {
    require(challenge.size == CHALLENGE_BYTES) { "a challenge is $CHALLENGE_BYTES bytes, got ${challenge.size}" }
    return CHALLENGE_LABEL.toByteArray(Charsets.UTF_8) + identityKey.serialize() + challenge
  }

  /** The ACI identity key pair's XEdDSA signature over [challengeMessage]. */
  fun signChallenge(aciIdentityKeyPair: IdentityKeyPair, challenge: ByteArray): ByteArray {
    return aciIdentityKeyPair.privateKey.calculateSignature(challengeMessage(aciIdentityKeyPair.publicKey, challenge))
  }
}
