/*
 * Copyright 2026 SWARM
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.swarm

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import org.junit.Test
import java.security.MessageDigest
import java.text.Normalizer
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * SWARM addition (wallet sign-in): the recovery phrase itself. The same checks as the SWARM Messenger desktop's
 * `ts/test-node/swarm/bip39_test.node.ts`, plus BIP-39's published seeds.
 */
class Bip39Test {

  companion object {
    /** The published SHA-256 of BIP-39's own english.txt: 2048 words, one per line, trailing newline. */
    private const val CANONICAL_WORDLIST_SHA256 = "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda"

    private const val ALL_ZERO_PHRASE =
      "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"

    /**
     * BIP-39's own 256-bit English test vectors (trezor/python-mnemonic vectors.json): entropy, phrase, and the seed
     * with the passphrase "TREZOR". Passing them means this is BIP-39, so a phrase written down here can be typed into
     * any other BIP-39 wallet, the SWARM wallet included.
     */
    private val VECTORS = listOf(
      Triple(
        "0000000000000000000000000000000000000000000000000000000000000000",
        ALL_ZERO_PHRASE,
        "bda85446c68413707090a52022edd26a1c9462295029f2e60cd7c4f2bbd3097170af7a4d73245cafa9c3cca8d561a7c3de6f5d4a10be8ed2a5e608d68f92fcc8"
      ),
      Triple(
        "7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f",
        "legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth useful " +
          "legal winner thank year wave sausage worth title",
        "bc09fca1804f7e69da93c2f2028eb238c227f2e9dda30cd63699232578480a4021b146ad717fbb7e451ce9eb835f43620bf5c514db0f8add49f5d121449d3e87"
      ),
      Triple(
        "8080808080808080808080808080808080808080808080808080808080808080",
        "letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic " +
          "avoid letter advice cage absurd amount doctor acoustic bless",
        "c0c519bd0e91a2ed54357d9d1ebef6f5af218a153624cf4f2da911a0ed8f7a09e2ef61af0aca007096df430022f7a2b6fb91661a9589097069720d015e4e982f"
      ),
      Triple(
        "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote",
        "dd48c104698c30cfe2b6142103248622fb7bb0ff692eebb00089b32d22484e1613912f0a5b694407be899ffd31ed3992c456cdf60f5d4564b8ba3f05a69890ad"
      )
    )

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
  }

  @Test
  fun `carries the wordlist from the specification, unedited`() {
    val words = Bip39Wordlist.WORDS
    assertThat(words).hasSize(2048)
    assertThat(words.toSet()).hasSize(2048)
    assertThat(words.first()).isEqualTo("abandon")
    assertThat(words.last()).isEqualTo("zoo")

    val canonicalFile = words.joinToString("\n", postfix = "\n")
    assertThat(hex(MessageDigest.getInstance("SHA-256").digest(canonicalFile.toByteArray(Charsets.UTF_8)))).isEqualTo(CANONICAL_WORDLIST_SHA256)
  }

  @Test
  fun `matches BIP-39's own phrases`() {
    for ((entropy, phrase, _) in VECTORS) {
      assertThat(Bip39.entropyToPhrase(unhex(entropy)), entropy).isEqualTo(phrase)
    }
  }

  @Test
  fun `matches BIP-39's own seeds`() {
    for ((_, phrase, seed) in VECTORS) {
      assertThat(hex(Bip39.phraseToSeed(phrase, passphrase = "TREZOR")), phrase.substringAfterLast(' ')).isEqualTo(seed)
    }
  }

  @Test
  fun `the PBKDF2 loop agrees with the JVM's own PBKDF2WithHmacSHA512`() {
    for (phrase in VECTORS.map { it.second } + Bip39.generate()) {
      val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        .generateSecret(PBEKeySpec(Normalizer.normalize(phrase, Normalizer.Form.NFKD).toCharArray(), "mnemonic".toByteArray(), 2048, 512))
        .encoded
      assertThat(hex(Bip39.phraseToSeed(phrase))).isEqualTo(hex(expected))
    }
  }

  @Test
  fun `generates 24 valid words, different every time`() {
    val phrases = (0 until 32).map { Bip39.generate() }
    for (phrase in phrases) {
      assertThat(phrase.split(" ")).hasSize(Bip39.WORDS)
      assertThat(Bip39.check(phrase)).isInstanceOf<Bip39.Check.Valid>()
    }
    assertThat(phrases.toSet()).hasSize(32)
  }

  @Test
  fun `refuses the wrong number of words`() {
    assertThat(Bip39.check("abandon abandon abandon")).isEqualTo(Bip39.Check.WrongWordCount(3))
    assertThat(Bip39.check("")).isEqualTo(Bip39.Check.WrongWordCount(0))
    assertThat(Bip39.check("   ")).isEqualTo(Bip39.Check.WrongWordCount(0))
  }

  @Test
  fun `refuses a word that is not in the list`() {
    val words = ALL_ZERO_PHRASE.split(" ").toMutableList()
    // "swarm" is a BIP-39 word; "swarmcoin" is not.
    words[5] = "swarmcoin"
    assertThat(Bip39.check(words.joinToString(" "))).isEqualTo(Bip39.Check.UnknownWord(5))
  }

  @Test
  fun `refuses a phrase whose checksum does not agree`() {
    // Every word is real and the count is right; only the checksum can catch this.
    val words = ALL_ZERO_PHRASE.split(" ").toMutableList()
    words[23] = "zoo"
    assertThat(Bip39.check(words.joinToString(" "))).isEqualTo(Bip39.Check.BadChecksum)
  }

  @Test
  fun `forgives capitals, padding, tabs and line breaks`() {
    val typed = "  " + ALL_ZERO_PHRASE.uppercase().replace(" ", " \t\n ") + "\n"
    val check = Bip39.check(typed)
    assertThat(check).isInstanceOf<Bip39.Check.Valid>()
    assertThat((check as Bip39.Check.Valid).phrase).isEqualTo(ALL_ZERO_PHRASE)
    assertThat(hex(Bip39.phraseToSeed(typed))).isEqualTo(hex(Bip39.phraseToSeed(ALL_ZERO_PHRASE)))
  }

  @Test
  fun `the seed is 64 bytes`() {
    assertThat(Bip39.phraseToSeed(ALL_ZERO_PHRASE)).hasSize(Bip39.SEED_BYTES)
  }

  @Test
  fun `a valid check never prints the phrase`() {
    assertThat(Bip39.check(ALL_ZERO_PHRASE).toString().contains("abandon")).isEqualTo(false)
    assertThat(Bip39.check(ALL_ZERO_PHRASE) is Bip39.Check.Valid).isTrue()
  }
}
