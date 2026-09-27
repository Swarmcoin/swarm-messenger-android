/*
 * Copyright 2026 SWARM
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.swarm

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SWARM addition (wallet sign-in). BIP-39: generating a 24-word recovery phrase, checking one a person typed, and
 * turning one into the 64-byte seed everything else is derived from.
 *
 * This is BIP-39 as specified and nothing more: SHA-256 for the checksum; PBKDF2 with HMAC-SHA512, 2048
 * iterations and the salt "mnemonic" for the seed. The SWARM-specific parts live in [SwarmWalletIdentity].
 * Kept byte-for-byte equivalent to the SWARM Messenger desktop's `ts/util/swarm/bip39.node.ts`.
 *
 * The phrase is the account and the money. Nothing in this file logs, stores or transmits one.
 */
object Bip39 {

  /** Words in a SWARM recovery phrase. */
  const val WORDS = 24

  /** The entropy behind a 24-word phrase, in bytes (256 bits). */
  private const val ENTROPY_BYTES = 32

  /** BIP-39's iteration count. Fixed by the specification. */
  private const val SEED_ITERATIONS = 2048

  /** The seed BIP-39 produces, in bytes. */
  const val SEED_BYTES = 64

  private const val HMAC_SHA512 = "HmacSHA512"

  private val WHITESPACE = Regex("\\s+")

  private val wordIndex: Map<String, Int> by lazy { Bip39Wordlist.WORDS.withIndex().associate { (index, word) -> word to index } }

  /** Why a phrase was refused. The screen turns this into a sentence; it is never logged with the words. */
  sealed interface Check {
    /** A real BIP-39 phrase, in its normalized form. */
    class Valid(val phrase: String) : Check {
      override fun toString(): String = "Valid(<phrase redacted>)"
    }

    data class WrongWordCount(val words: Int) : Check

    /** [index] is zero-based. */
    data class UnknownWord(val index: Int) : Check

    data object BadChecksum : Check
  }

  /**
   * A phrase as BIP-39 wants it compared: Unicode NFKD, lower case, single spaces, no padding. Normalizing before
   * hashing is what makes "the same phrase" mean the same thing on Android, the desktop and the wallet, whatever the
   * keyboard did with capitals and whitespace.
   */
  fun normalize(phrase: String): String {
    return Normalizer.normalize(phrase, Normalizer.Form.NFKD)
      .lowercase(Locale.ROOT)
      .trim()
      .split(WHITESPACE)
      .filter { it.isNotEmpty() }
      .joinToString(" ")
  }

  /** A new 24-word recovery phrase from the system's own randomness. Shown once, written down, then forgotten. */
  fun generate(random: SecureRandom = SecureRandom()): String {
    val entropy = ByteArray(ENTROPY_BYTES)
    random.nextBytes(entropy)
    return try {
      entropyToPhrase(entropy)
    } finally {
      entropy.fill(0)
    }
  }

  /** The phrase for exactly 32 bytes of entropy. */
  fun entropyToPhrase(entropy: ByteArray): String {
    require(entropy.size == ENTROPY_BYTES) { "a 24-word phrase needs $ENTROPY_BYTES bytes of entropy" }

    // The checksum is the first (entropy bits / 32) bits of SHA-256(entropy): 8 bits for 256, so 264 bits = 24 x 11.
    val checksum = MessageDigest.getInstance("SHA-256").digest(entropy)
    val bits = BooleanArray(ENTROPY_BYTES * 8 + 8)
    for (i in 0 until ENTROPY_BYTES * 8) {
      bits[i] = bit(entropy, i)
    }
    for (i in 0 until 8) {
      bits[ENTROPY_BYTES * 8 + i] = bit(checksum, i)
    }

    return (0 until WORDS).joinToString(" ") { word ->
      var index = 0
      for (i in 0 until 11) {
        index = (index shl 1) or (if (bits[word * 11 + i]) 1 else 0)
      }
      Bip39Wordlist.WORDS[index]
    }
  }

  /**
   * Whether a phrase is a real BIP-39 phrase: 24 known words whose checksum agrees. A typo in one word fails the
   * checksum with probability 255/256, which is the whole reason BIP-39 has one.
   */
  fun check(phrase: String): Check {
    val normalized = normalize(phrase)
    val words = if (normalized.isEmpty()) emptyList() else normalized.split(" ")

    if (words.size != WORDS) {
      return Check.WrongWordCount(words.size)
    }

    val bits = BooleanArray(WORDS * 11)
    for ((position, word) in words.withIndex()) {
      val index = wordIndex[word] ?: return Check.UnknownWord(position)
      for (i in 0 until 11) {
        bits[position * 11 + i] = (index shr (10 - i)) and 1 == 1
      }
    }

    val entropy = ByteArray(ENTROPY_BYTES)
    for (i in 0 until ENTROPY_BYTES * 8) {
      if (bits[i]) {
        entropy[i / 8] = (entropy[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
      }
    }

    val checksum = MessageDigest.getInstance("SHA-256").digest(entropy)
    entropy.fill(0)
    for (i in 0 until 8) {
      if (bits[ENTROPY_BYTES * 8 + i] != bit(checksum, i)) {
        return Check.BadChecksum
      }
    }

    return Check.Valid(normalized)
  }

  /**
   * The 64-byte BIP-39 seed for a phrase. SWARM uses the empty passphrase, so the same words open the SWARM wallet.
   * [passphrase] exists for BIP-39's own test vectors.
   */
  fun phraseToSeed(phrase: String, passphrase: String = ""): ByteArray {
    val password = Normalizer.normalize(normalize(phrase), Normalizer.Form.NFKD).toByteArray(Charsets.UTF_8)
    val salt = Normalizer.normalize("mnemonic$passphrase", Normalizer.Form.NFKD).toByteArray(Charsets.UTF_8)
    return try {
      pbkdf2HmacSha512(password, salt, SEED_ITERATIONS, SEED_BYTES)
    } finally {
      password.fill(0)
    }
  }

  /**
   * PBKDF2 (RFC 8018, section 5.2) with the platform's HMAC-SHA512. Android's `SecretKeyFactory` only offers
   * PBKDF2WithHmacSHA512 from API 26 and this app supports API 23, so the loop is spelled out here, over the
   * platform's own HMAC; Bip39Test checks it against the JVM's PBKDF2WithHmacSHA512 and BIP-39's published seeds.
   */
  internal fun pbkdf2HmacSha512(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
    val mac = Mac.getInstance(HMAC_SHA512)
    mac.init(SecretKeySpec(password, HMAC_SHA512))
    val blockSize = mac.macLength
    val blocks = (length + blockSize - 1) / blockSize
    val output = ByteArray(blocks * blockSize)

    for (block in 1..blocks) {
      mac.update(salt)
      mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
      var u = mac.doFinal()
      val t = u.copyOf()
      for (iteration in 2..iterations) {
        u = mac.doFinal(u)
        for (i in t.indices) {
          t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
        }
      }
      t.copyInto(output, (block - 1) * blockSize)
      t.fill(0)
      u.fill(0)
    }

    return if (output.size == length) output else output.copyOf(length).also { output.fill(0) }
  }

  private fun bit(bytes: ByteArray, index: Int): Boolean {
    return (bytes[index / 8].toInt() shr (7 - index % 8)) and 1 == 1
  }
}
