# Wallet sign-in: no phone number, no SMS (Android)

**Status: implemented, wave 1** (Opus M5-A, 2026-09-27). The protocol is the one SWARM Messenger for desktop
and the chat server already speak: the server half is `docs/WALLET-SIGN-IN.md` in
`Swarm-Official/swarm-messenger-server`, the desktop half is `docs/WALLET-SIGN-IN.md` in
`Swarm-Official/swarm-messenger`. This file is about the Android app.

The owner's decision, 2026-09-27: *"instead of a phone number make the users sign in with our wallet."*

## 1. What a person sees

The welcome screen's **Continue** leads (through the usual permissions screen) to one question:
**create a new wallet, or enter a recovery phrase?**

- **Create a new wallet** → 24 numbered words, shown once. A checkbox, "I have written these 24 words down",
  enables **Continue**. The account is created.
- **I have a recovery phrase** → type the 24 words. Capitals, extra spaces and line breaks do not matter; a
  wrong word or a wrong order is caught before anything is sent. The same words always give the same account,
  on this phone or any other, and the desktop.

Then the profile name, and the conversation list. **There is no PIN step**: a SWARM account's recovery is its
recovery phrase, and the SWARM server runs no secure value recovery. The window is `FLAG_SECURE` while the
wallet screen is showing, so the words cannot be screenshotted, recorded or seen in the recent-apps overview.

Upstream's "restore or transfer" choices (Signal backups, moving a phone-number account between phones) are
not offered: a SWARM account is restored from its phrase. The phone-number, captcha and verification-code
screens are left in the tree, unreached, so upstream merges stay small.

No screen of the registration flow shows the synthetic `+888…` identifier the account is keyed by.

## 2. What happens underneath

```
recovery phrase (24 BIP-39 words)
   │  PBKDF2-HMAC-SHA512(NFKD(phrase), "mnemonic", 2048)            → 64-byte BIP-39 seed
   ├── HKDF-SHA256(seed, info "SWARM-Messenger-ACI-identity-v1", 32) → X25519 clamp → ACI identity key pair
   └── HKDF-SHA256(seed, info "SWARM-Messenger-PNI-identity-v1", 32) → X25519 clamp → PNI identity key pair

POST /v1/swarm/registration/challenge  { identityKey }            → { challenge, ttlSeconds }
POST /v1/swarm/registration/verify     { identityKey, signature }  → { number, registrationPassword }
POST /v1/registration                  Signal's own endpoint: basic auth with number, recoveryPassword =
                                       registrationPassword, aciIdentityKey = the derived ACI key, no session
```

- account identifier: `+888` then `10^10 + (high 8 bytes of SHA-256("SWARM-Messenger-e164-v1" || identityKey), mod 9·10^10)`;
  the app refuses to register if the server's `number` is not the identifier it derived itself;
- signature: libsignal's XEdDSA over `"SWARM-Messenger-wallet-registration-v1" || identityKey || challenge`.

After registration the flow records "no PIN" and "new account" and completes, as upstream does when a person
skips the PIN. The phrase itself is dropped as soon as the identity is derived.

### No new cryptography

| Step | Whose code |
| --- | --- |
| BIP-39 checksum | the platform's SHA-256 (`MessageDigest`) |
| BIP-39 seed | PBKDF2 (RFC 8018) over the platform's HMAC-SHA512. Android's `SecretKeyFactory` has PBKDF2WithHmacSHA512 only from API 26 and the app supports 23, so the loop is written out; a test checks it against the JVM's `PBKDF2WithHmacSHA512` and BIP-39's published seeds |
| key stretching | libsignal's `HKDF.deriveSecrets` |
| key types | libsignal's `ECPrivateKey`, `IdentityKey`, `IdentityKeyPair` |
| the signature | libsignal's `ECPrivateKey.calculateSignature` (XEdDSA) |

libsignal, the protocol, session setup and message encryption are untouched.

## 3. The code

New, in `feature/registration` (package `org.signal.registration.swarm` and `screens.swarmwallet`):

| File | What |
| --- | --- |
| `swarm/Bip39.kt`, `swarm/Bip39Wordlist.kt` | generate, check and normalize a 24-word phrase; the seed. The wordlist is BIP-39's `english.txt`, verbatim |
| `swarm/SwarmWalletIdentity.kt` | the ACI/PNI derivation, the account identifier, the challenge message and signature |
| `screens/swarmwallet/WalletSignInScreen.kt`, `WalletSignInViewModel.kt`, `WalletSignInState.kt` | the one new screen |
| `res/values/swarm_strings.xml` | its strings (English) |

New in `lib/network`: `api/SwarmWalletApi.kt`, the two REST calls (`POST /v1/swarm/registration/challenge`,
`/verify`) with typed errors (400/422 invalid, 403 signature rejected, 404 no challenge pending, 409 identifier
taken, 429 rate limited). They go through the app's own REST client, so they use the SWARM hosts and the
SWARM trust store like every other request.

Changed:

| File | Change |
| --- | --- |
| `feature/registration/.../NetworkController.kt` | `requestSwarmWalletChallenge`, `verifySwarmWalletChallenge`, with default bodies so the demo app and test fakes compile unchanged |
| `feature/registration/.../RegistrationRepository.kt` | the two calls; `registerAccountWithSwarmWallet(identity, verification)`, which runs upstream's own recovery-password registration with the derived key pairs; `isSwarmWalletSignIn` |
| `feature/registration/.../RegistrationDependencies.kt` | `isSwarmWalletSignIn` (default false, which keeps upstream's phone flow for the module's own tests and demo) |
| `feature/registration/.../RegistrationNavigation.kt` | the `SwarmWallet` route and its entry |
| `feature/registration/.../screens/welcome/WelcomeScreenViewModel.kt` | with wallet sign-in, Continue → `SwarmWallet`, and no restore-or-transfer |
| `app/.../registration/v2/AppRegistrationNetworkController.kt` | the two calls through `SwarmWalletApi` |
| `app/.../ApplicationContext.java` | turns wallet sign-in on |

## 4. What the phrase is allowed to touch

- It is generated in memory or read from the text field, turned into keys, and dropped. It is **not** written
  to disk (the wallet screen's state is not part of the persisted registration flow), **not** logged (the
  state's and the events' `toString()` redact it), and **not** sent anywhere.
- The derived seed and the HKDF output are zeroed after use; the private keys go where upstream's identity
  keys go, into the registration data and then the protocol store.
- What crosses the network is the ACI identity **public** key and a signature.

## 5. Tests

```sh
./gradlew :feature:registration:testDebugUnitTest --tests 'org.signal.registration.swarm.*'
```

- `Bip39Test` — the wordlist's SHA-256 is BIP-39's published one; BIP-39's four 24-word vectors (phrases
  and seeds with passphrase "TREZOR"); the PBKDF2 loop equals the JVM's PBKDF2WithHmacSHA512; 32 generated
  phrases valid and distinct; wrong count, unknown word and bad checksum refused; capitals, tabs and line
  breaks forgiven; a valid check never prints the phrase.
- `SwarmWalletIdentityTest` — **the three vectors the server's `SwarmWalletIdentityTest` and the desktop's
  `walletIdentity_test.node.ts` carry**, byte for byte (ACI key, PNI key, identifier); typing does not change
  the result; ACI ≠ PNI; distinct phrases give distinct accounts; the clamped private key round-trips through
  libsignal; the identifier's shape and eight non-identifiers; the identifier recomputed by hand; the signed
  message byte for byte; a signature does not answer another challenge or another key.
- `WalletSignInFlowTest` (Robolectric) — the whole screen flow against a fake server that checks the
  signature, derives the identifier, issues a single-use password and refuses a registration whose key does
  not derive the identifier: a new wallet registers only after "written down"; restoring the all-zero vector
  registers `+88810866442360` with the vector's ACI key; a bad checksum sends nothing; an identifier taken by
  another wallet is reported; a server that derives a different identifier is not trusted; the screen state
  never prints the phrase. Registration ends with no PIN, a new-account restore decision, and
  `RegistrationComplete`.
- `SwarmWelcomeScreenTest` — with wallet sign-in on, Continue goes to the wallet screen (through permissions
  when needed) and restore-or-transfer is never offered.

Before this was written, the three vectors were recomputed independently (Python: `hashlib.pbkdf2_hmac`,
HMAC-based HKDF, an RFC 7748 X25519 ladder) and matched.

## 6. Not in wave 1

- Mounting the SWARM wallet itself in the app (the phrase opens the wallet too, but there is no wallet
  screen yet).
- Finding people by wallet address (server-side address-hash lookup, wave 2); usernames need the SWARM
  server's gRPC routes (see the libsignal notes in `SWARM-CHANGES.md`).
- The identifier after registration: `SignalE164Util.prettyPrint`, which every screen uses to show a number
  (the settings header, conversation headers, the about sheet, a contact's fallback name), shows "SWARM
  account" for any `+888` identifier instead. Not yet done: hiding the Account settings rows that are about
  phone numbers ("Change phone number", phone-number privacy), which do nothing useful for a SWARM account,
  and an audit for any screen that prints the raw number without `prettyPrint`.
