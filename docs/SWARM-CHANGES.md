# SWARM Messenger for Android — every change from upstream Signal-Android

Upstream base: the tree of `6151a523373e02f37d6a367fe448bd15a3de1a72` = tag `v8.29.1` = upstream
`main` on 2026-09-27, kept in `Swarm-Official/swarm-messenger-android` as branch `upstream-main` and tag
`upstream-v8.29.1`. All SWARM work is on `swarm-main`, so every change below is
`git diff upstream-v8.29.1..swarm-main`.

**`upstream-main` is a snapshot, not upstream's history.** It is one commit holding exactly the tree of
upstream `v8.29.1` (the same bytes: `git diff 6151a523 upstream-v8.29.1` in a clone that has both is
empty). The full history (20,822 commits) could not be published here: GitHub push protection refuses
upstream's 2014–2015 commits, which carry OpenSSL's public sample RSA key
(`jni/openssl/apps/rsa8192.pem`, from the OpenSSL source tree upstream vendored then) and flags it as an
"SSH private key". Allowing it is a security setting only the owner may change, so the planner chose the
snapshot on 2026-09-27; the owner has a vault item to unblock full history later. Consequence: `git log`
and `git blame` on this repository stop at the snapshot — use
[signalapp/Signal-Android](https://github.com/signalapp/Signal-Android) for anything older. Merging a
later upstream release works the same way: bring its tree in as the next snapshot commit on
`upstream-main`, then merge that into `swarm-main`.

Wave 1 (milestone M5), 2026-09-27, Opus M5-A. The desktop fork (`Swarm-Official/swarm-messenger`,
`docs/SWARM-CHANGES.md`) did the same job first; this fork follows its choices unless Android forces
a different one, and says so where it does.

**Nothing cryptographic was changed.** `org.signal:libsignal-android` is the published artifact
(upstream 0.102.2, or the SWARM build of the same version from `Swarm-Official/swarm-libsignal` once
it exists). No primitive, key schedule, message format or protocol step was touched.

Status words used below: **implemented** (in the tree), **tested** (a test or a CI run proves it, with
the run named), **proposed** (not done, described so someone can do it).

---

## 0. Repository layout

| Branch / tag       | What it is                                                                  |
| ------------------ | --------------------------------------------------------------------------- |
| `upstream-main`    | signalapp/Signal-Android `v8.29.1` (= upstream `main` on 2026-09-27) as one snapshot commit, untouched |
| `upstream-v8.29.1` | tag on that snapshot commit                                                  |
| `swarm-main`       | the SWARM fork: upstream-v8.29.1 plus the commits indexed in this file. The deliverable; always builds |
| `swarm-next`       | where a change goes first: CI builds and tests it there, then `swarm-main` fast-forwards to it |

Two documents live beside this one:

- [`SWARM-BUILD.md`](SWARM-BUILD.md) — how to build, test and release it, locally and in CI.
- This file — the running index of every change, grouped by purpose. Each section names the files.

---

## 1. Build and CI

| File | Change | Status |
| --- | --- | --- |
| `app/build.gradle.kts` | `websiteProdDebug` added to `selectableVariants`. Upstream builds `website` only as `Spinner` (starts an on-device database inspection server) or `Release` (minified, needs a signing key). | implemented |
| `.github/workflows/swarm-build.yml` (new) | GitHub-hosted `ubuntu-latest`, on pushes to `swarm-main` and `swarm-next`, pull requests and manual runs: job `apk` builds `:Signal-Android:assembleWebsiteProdDebug` and runs the endpoint scan; job `unit-tests` runs the app's unit tests for the same variant plus `:feature:registration`, `:lib:libsignal-service`, `:lib:network`; job `release` (pushes to `swarm-main` only) publishes a pre-release `swarm-messenger-android-<yyyymmdd>-<shortsha>` with the APKs, `SHA256SUMS.txt` and the scan output. A Gradle user-home `gradle.properties` lowers the 12 GB heap `gradle.properties` asks for, so Gradle and the Kotlin daemon fit a 16 GB runner. | implemented |
| `scripts/swarm/endpoint_scan.py` (new) | Reads the generated `BuildConfig.java`, the merged `AndroidManifest.xml`, the merged default-locale `values.xml` and the APK's text assets of the built variant. Fails on any host under `signal.org`, `signal.art`, `signalcaptchas.org`, `signaldonations.org`, `whispersystems.org` (so `storage.`, `cdsi.`, `sfu.voip.`, `updates.` and every other Signal service host), on an http(s) intent filter for `signal.me` / `signal.group` / `signal.link` / `signal.tube` (with `autoVerify`, Android itself fetches `/.well-known/assetlinks.json` from that host at install time), and on Firebase/FCM components, permissions and project settings. Reports without failing: `sgnl://` deep links, Google Play services components from dependencies, and how often Signal hosts still occur in compiled code. `SWARM_SCAN_MODE` in the workflow is `report` until the SWARM endpoints are in, then `enforce`. | implemented |
| `.github/workflows/docker.yml`, `.github/workflows/stale.yml` | Removed. Both are `schedule` workflows, and schedules run on the default branch: the first would run upstream's hour-long reproducible-build container every night on this repository, the second would label and close this repository's issues. Upstream's `android.yml` and `diffuse.yml` are kept unchanged; they trigger only on pull requests and on upstream's own branch names, and `android.yml` asks for a runner size this organisation does not have. | implemented |

---

## 2. Rebrand (English first)

| Area | Files | Status |
| --- | --- | --- |
| Application id | `app/build.gradle.kts`: `applicationId = "green.swarm.messenger"` (the Java/Kotlin `namespace` stays `org.thoughtcrime.securesms`, so no source file moves). APK base name `SWARM-Messenger`. SWARM Messenger installs beside Signal and shares nothing with it. | implemented |
| Things upstream tied to its own package name | `AndroidManifest.xml`: the `ACCESS_SECRETS` `uses-permission` now names `${applicationId}` (upstream hard-coded `org.thoughtcrime.securesms`, which with another id would point at Signal's permission). `res/xml/authenticator.xml`, `res/xml/syncadapter.xml`: account type `green.swarm.messenger` (the code already uses `BuildConfig.APPLICATION_ID`). Contact data mime types `vnd.green.swarm.messenger.{contact,call,videocall}` in `res/xml/contactsformat.xml`, the manifest, `SyncSystemContactLinksJob.kt`, `VoiceCallShare.java`. `core/ui/.../StorageUtil.java` and `SaveAttachmentUtil.kt`: folders on shared storage are `SWARM Messenger`, not `Signal`. | implemented |
| App name and English strings | `scripts/swarm/rebrand_strings.py` (new, idempotent, re-run after an upstream merge) sweeps the default-locale `res/values/strings.xml` of every module (demo apps excluded): **557 strings**. Same rules as the desktop fork: `Signal` → `SWARM Messenger`, `Signal PIN/account/call/...` → `SWARM PIN/account/call/...`, `support.signal.org/...` → `https://swarm.green/support`, `signal.org/legal` → `https://swarm.green/legal`, `support@signal.org` → `support@swarm.green`. Kept on purpose: `Signal Protocol` (the protocol really used), `Signal Technology Foundation`, and the copyright notice. Only element text is touched, never names, attributes or comments. `app_name` = `SWARM Messenger`; notification channel names come from the same strings (e.g. "Contact joined SWARM Messenger"). | implemented |
| Translations | **68 translated locales** in `app/` (and the modules' own) are **not** swept: re-translation is an owner decision. SWARM's new strings live in `res/values/swarm_strings.xml` files, English only, so every locale falls back to them. A phone set to French still reads "Signal" in sentences that were already translated. | proposed |
| About screen | `HelpSettingsFragment.kt`: the footer is "SWARM Messenger is based on Signal by Signal Messenger, LLC" (new `SwarmAbout__based_on_signal`), then "Copyright Signal Messenger, LLC", then "Licensed under the GNU AGPLv3". Upstream's third line, "Signal is a 501c3 nonprofit", is not true of SWARM and is gone. The Licenses screen and every licence file are unchanged. | implemented |
| Onboarding | `feature/registration/.../WelcomeScreen.kt`: the hero image is the SWARM mark (`drawable/swarm_mark.xml`) instead of upstream's illustration; the terms link reads "Terms & Privacy Policy" (`SwarmRegistration__terms_and_privacy`) without upstream's "501c3 nonprofit" line, which every translation also carries. | implemented |
| Icons | `scripts/swarm/make_brand_assets.py` (new) generates everything from `scripts/swarm/brand/swarm-mark.svg`, a verbatim copy of the brand kit's mark (sha256 `1daf0ec5…6583`, the file the SWARM wallet ships). Adaptive launcher icon: warm black `#0A0908` background, the mark in honey `#F5A623` / amber `#E8890C` inside the 66 dp safe zone, a monochrome layer for themed icons. Legacy `mipmap-*/ic_launcher.png` for Android 6–7.1. Status-bar icon: `drawable/ic_notification.xml` (white mark) replaces the five `ic_notification.png`. `ic_signal_logo_large.xml` / `ic_signal_logo_small.xml` (release-notes avatar, empty detail pane, incoming call screen) now draw the mark; the file names are kept so no code changes. | implemented |
| Alternate app icons | Upstream's alternates are Signal's logo in several colours. The Appearance > App icon row is hidden (`SwarmBuild.ALTERNATE_APP_ICONS = false`, new `swarm/SwarmBuild.kt`); the aliases stay disabled in the manifest as upstream ships them, so none can appear. Replacing them with SWARM variants is | proposed |
| Colour | Hive Orange is the accent. Dark scheme: primary `#FF8A1F` with warm black `#0A0908` on it. Light scheme: primary `#B85300`, a deeper Hive Orange, because `#FF8A1F` text on white is 2.3:1. `core/ui/.../SignalTheme.kt` (Compose), `core/ui/res/values/material3_colors_{light,dark}.xml` (views), `app/res/values/core_colors.xml` (`core_ultramarine*`), `ChatColorsPalette.kt`: the default outgoing bubble is `#B85300` (white text 4.9:1). No other restyle in wave 1. | implemented |
| Dark by default | `TextSecurePreferences.getTheme()` defaults to `dark` (upstream: follow the system); the user can still choose. The registration activity, which runs before there is a setting, is always dark (`feature/registration/.../RegistrationActivity.kt`). | implemented |
| Names in code | The release-notes chat is named "SWARM Messenger" (`CreateReleaseChannelJob.kt`, `RecipientTableArchiveExtensions.kt`); the authenticator-app issuer is "SWARM Messenger" (`TotpRepository.kt`). | implemented |

---

## 3. Endpoints: SWARM's servers, never Signal's

### Server URLs and the values the server generated

`app/build.gradle.kts`, `defaultConfig`:

| `BuildConfig` field | Value | Note |
| --- | --- | --- |
| `SIGNAL_URL` | `https://chat.swarm.green` | chat REST API and websocket |
| `STORAGE_URL` | `https://chat.swarm.green` | no storage service on staging; the desktop's choice too (contacts and settings do not sync between one person's devices; messaging works) |
| `SIGNAL_CDN_URL`, `SIGNAL_CDN2_URL`, `SIGNAL_CDN3_URL` | `https://cdn.chat.swarm.green` | one host for all three CDN generations, as on the desktop |
| `SIGNAL_CDSI_URL`, `SIGNAL_SVR2_URL` | `https://cdsi.service-not-deployed.swarm.invalid`, `https://svr2.service-not-deployed.swarm.invalid` | staging runs no contact discovery and no secure value recovery; `.invalid` never resolves (RFC 2606), the placeholder the SWARM libsignal build uses too |
| `SIGNAL_SFU_URL`, `SIGNAL_STAGING_SFU_URL` | `https://sfu.chat.swarm.green` | reserved; no SFU or TURN server yet, so calls do not work |
| `SIGNAL_SFU_INTERNAL_NAMES/URLS` | empty | upstream's internal-build SFU picker listed Signal's test SFUs |
| `CONTENT_PROXY_HOST` | `contentproxy.chat.swarm.green` | link previews and GIFs go through it |
| `SIGNAL_SERVICE_STATUS_URL` | `uptime.swarm.green` | the outage probe is a DNS lookup of this name, as on the desktop |
| `SIGNAL_*_IPS` (nine) | empty | `app/static-ips.properties` held Signal's IP addresses as a DNS fallback; SWARM hosts resolve through DNS only. The `resolveStaticIps` task (build-logic) now resolves SWARM names if anyone runs it |
| `UNIDENTIFIED_SENDER_TRUST_ROOTS` | `BSgjRy7s…biyMu` | the staging server's sealed-sender root |
| `ZKGROUP_SERVER_PUBLIC_PARAMS` | `ALiTHZaw…` (900 characters) | the server's `groupsZkConfig` |
| `GENERIC_SERVER_PUBLIC_PARAMS` | `AMzkIq08…` (300 characters) | the server's **`callingZkConfig`**: Android uses this set for call links, and the server signs call-link credentials with its calling parameters (`CertificateController`, `CallLinkController`) |
| `BACKUP_SERVER_PUBLIC_PARAMS` | `ALrEtWYx…` (300 characters) | the server's `chatZkConfig` (`BackupAuthManager`) |
| `SIGNAL_CAPTCHA_URL`, `RECAPTCHA_PROOF_URL` | `https://chat.swarm.green/challenge/registration/generate.html`, `…/challenge/generate.html` | the desktop's values |
| `BADGE_STATIC_ROOT` | `https://static.swarm.green/static/badges/` | |
| `SWARM_STATIC_HOST` (new) | `static.swarm.green` | see "Static files" below |
| `SWARM_LIBSIGNAL_NET` (new) | `false` | true once the SWARM build of libsignal is in; see "libsignal" below |
| `STRIPE_PUBLISHABLE_KEY`, `GIPHY_API_KEY` | empty | both were Signal's own keys |
| `mapsKey` manifest placeholder, `getMapsKey()` default | empty | Signal's Google Maps key |

The four server values come from `D:\swarm-messenger\shared\staging-public-params.json` (schema
`swarm-messenger/staging-public-params/1`, generated by the staging host at 2026-09-27T00:25:05Z), the file
the desktop imports as well. **The desktop wires `genericServerPublicParams` (the chat set) where Android
wires the calling set.** On the desktop that makes call-link credentials fail zk verification in a loop
(Opus M-G, 2026-09-27 17:30 UTC). Android's `GENERIC_SERVER_PUBLIC_PARAMS` is used only for call links, so
it gets `callingServerPublicParams`, and `SwarmEndpointsTest` pins that the two generic sets differ.

The `staging` flavor no longer sets Signal's staging hosts, keys and trust roots; SWARM runs one server,
so staging inherits the SWARM values. The `website` flavor no longer manages its own updates
(`MANAGES_APP_UPDATES = false`, `APK_UPDATE_MANIFEST_URL = null`); upstream's polled
`updates.signal.org/android/latest.json` and installed Signal's APK.

If the staging server is rebuilt, its parameters change: copy the four values from the new
`staging-public-params.json` into `app/build.gradle.kts` and update the trust root pinned in
`SwarmEndpointsTest`.

### libsignal, until the SWARM build of it is in (superseded by section 5)

libsignal's network stack takes an environment, not a host, and upstream libsignal 0.102.2 knows only
Signal's staging and production. New `app/.../swarm/SwarmNetwork.kt`:

- `ApplicationDependencyProvider.provideLibsignalNetwork()` calls `SwarmNetwork.guard(network)`: while
  `SWARM_LIBSIGNAL_NET` is false, the `Network` gets an invalid proxy, which libsignal documents as
  "refuses to make any new connections". Contact discovery, secure value recovery, key transparency and
  libsignal's chat connection therefore cannot dial anything, Signal included.
  `LibSignalNetworkExtensions.applyConfiguration` is the only other place that sets libsignal's proxy, and
  it runs before the guard.
- The chat websocket (`provideAuthWebSocket`, `provideUnauthWebSocket`) is upstream's own
  `OkHttpWebSocketConnection` to `SIGNAL_URL` with the app's trust store while `SWARM_LIBSIGNAL_NET` is
  false. It receives messages and carries plain requests. The typed libsignal chat APIs (sending
  messages, usernames, some account calls) need libsignal's own connection and fail cleanly until the
  SWARM build is in.
- When `Swarm-Official/swarm-libsignal` publishes `0.102.2-swarm.1`: follow its `docs/SWARM-MOBILE.md`
  (repository, version, verification metadata), set `LIBSIGNAL_NET_ENV` to `Network.Environment.SWARM` and
  `SWARM_LIBSIGNAL_NET` to true.

### Censorship circumvention

`push/SignalServiceNetworkAccess.kt`: upstream builds extra configurations that domain-front through
Google and Fastly to Signal's hosts, and switches them on by default for nine country codes. They are
gone: `getConfiguration()` always returns the SWARM configuration and `isCensored()` is false.

### No Google Play services, no FCM, no Firebase

| File | Change |
| --- | --- |
| `app/src/main/res/values/firebase_messaging.xml` | removed: it was Signal's Firebase project (`google_app_id`, `gcm_defaultSenderId`, API keys) |
| `AndroidManifest.xml` | Signal's `FcmReceiveService` and the two `firebase_*` meta-data removed. The firebase-messaging library's `FirebaseMessagingService`, `ComponentDiscoveryService`, `FirebaseInstanceIdReceiver`, `FirebaseInitProvider`, Google data-transport components and the `c2dm` permission are removed from the merged manifest with `tools:node="remove"` |
| `core/util/.../PlayServicesUtil.java` | always reports Play services missing, which puts the whole app on upstream's own path for phones without them: messages arrive over the persistent websocket |
| `app/.../gcm/FcmUtil.java` | returns no token, without touching Firebase |

The library code still ships in the APK (every upstream flavor depends on it); nothing can start it.

### Static files, app links, links in the interface

| File | Change |
| --- | --- |
| `s3/S3.kt` | release notes, remote megaphones, emoji and badge files come from `SWARM_STATIC_HOST`, not `updates2.signal.org` |
| `service/webrtc/CallingAssets.kt` | the calling model comes from `SWARM_STATIC_HOST` (its SHA-512 digest still pins the bytes) |
| `AndroidManifest.xml` | the https app links for `signal.art`, `signal.group`, `signal.me`, `signal.link`, `signal.tube` and `signaldonations.org` are removed. With `autoVerify`, Android fetched `/.well-known/assetlinks.json` from each of those Signal hosts when the app was installed. The `sgnl://` deep links stay: nothing is fetched through them |
| 29 source files | every support-article link in code points at `https://swarm.green/support`, legal links at `https://swarm.green/legal`, download links at `https://swarm.green`, the debug-log address at `support@swarm.green` |
| `InAppDonations.kt`, `SwarmBuild.DONATIONS = false` | donations pay Signal Technology Foundation through Signal's Stripe and PayPal accounts, so no payment method is ever offered and every donation entry point stays hidden |

Still in code on purpose: `StickerUrl.java` builds `signal.art` sticker-pack share links and the username
link is `signal.me` (links that Signal-protocol apps understand; nothing is fetched from them), the
PayPal and Stripe return URLs of the disabled donation flow, and `WifiDirect.java`'s local service name.
The endpoint scan reports them as occurrences in compiled code.

### The guard

- `app/src/test/.../swarm/SwarmEndpointsTest.kt` (new): every server URL and host in `BuildConfig` is
  under `swarm.green` (or `.invalid`); no `BuildConfig` string names a Signal host; there are no fallback
  addresses; the sealed-sender trust root is the SWARM server's and decodes; the three zkgroup parameter
  sets decode, are not Signal's, and the calling set differs from the backup set; no self-update; static
  files come from `static.swarm.green`; libsignal networking is SWARM's or switched off; no Stripe or
  Giphy key.
- `scripts/swarm/endpoint_scan.py` runs in **enforce** mode in CI (`SWARM_SCAN_MODE: enforce`), and
  counts host names in compiled code by whole name (upstream's `org.signal.messages` package no longer
  counts as `signal.me`).
- `S3Test_getS3Url` expects `static.swarm.green`.

---

## 4. Trust store: Let's Encrypt's roots instead of Signal's CA

The app pins its own trust store for the chat, CDN and storage hosts: `app/src/main/res/raw/whisper.store`,
a BouncyCastle `BKS` keystore with password `whisper`, read by `push/SignalServiceTrustStore.java` and turned
into trust managers by `BlacklistingTrustManager`. Upstream's store holds one certificate, Signal's private
CA (`CN=Signal Messenger`, SHA-256 `DD:B0:F9:2B:B9:5C:8D:6F:D2:02:EA:6E:8C:C5:CC:D1:82:B5:44:F8:CD:69:6F:47:D5:80:65:9D:DC:9D:F6:5A`),
so a Let's Encrypt certificate such as `chat.swarm.green`'s could never validate.

SWARM's store holds exactly the two ISRG roots. **Status: implemented; tested by `SwarmTrustStoreTest`.**

| Alias | Certificate | SHA-256 fingerprint | Valid until |
| --- | --- | --- | --- |
| `isrg-root-x1` | `C=US, O=Internet Security Research Group, CN=ISRG Root X1` (RSA 4096) | `96:BC:EC:06:26:49:76:F3:74:60:77:9A:CF:28:C5:A7:CF:E8:A3:C0:AA:E1:1A:8F:FC:EE:05:C0:BD:DF:08:C6` | 2035-06-04 |
| `isrg-root-x2` | `C=US, O=Internet Security Research Group, CN=ISRG Root X2` (ECDSA P-384) | `69:72:9B:8E:15:A8:6E:FC:17:7A:57:AF:B7:17:1D:FC:64:AD:D2:8C:2F:CA:8C:F1:50:7E:34:45:3C:CB:14:70` | 2040-09-17 |

The store file: 2,063 bytes, BKS version 2 (the format upstream's store also uses, which Android reads),
SHA-256 `5928dd3854610310b662fdc93d39b0fdf97dbc1f3ad3f3555516bb332685a07e`.

### How it was made (2026-09-27, Windows workstation, Temurin JDK 26.0.2 keytool, OpenSSL 3.2.3)

The two roots were taken from the JDK's own `cacerts` rather than downloaded, and their fingerprints
checked against the values Let's Encrypt publishes (above):

```sh
keytool -exportcert -rfc -cacerts -storepass changeit \
  -alias "cn_isrg_root_x1,o_internet_security_research_group,c_us [jdk]" -file isrg-root-x1.pem
keytool -exportcert -rfc -cacerts -storepass changeit \
  -alias "cn_isrg_root_x2,o_internet_security_research_group,c_us [jdk]" -file isrg-root-x2.pem
openssl x509 -in isrg-root-x1.pem -noout -subject -fingerprint -sha256
openssl x509 -in isrg-root-x2.pem -noout -subject -fingerprint -sha256

BC=bcprov-jdk18on-1.84.jar   # org.bouncycastle:bcprov-jdk18on:1.84 from Maven Central
for a in x1 x2; do
  keytool -importcert -noprompt -storetype BKS \
    -providerclass org.bouncycastle.jce.provider.BouncyCastleProvider -providerpath "$BC" \
    -keystore whisper.store -storepass whisper -alias isrg-root-$a -file isrg-root-$a.pem
done
keytool -list -storetype BKS -providerclass org.bouncycastle.jce.provider.BouncyCastleProvider \
  -providerpath "$BC" -keystore whisper.store -storepass whisper
```

### What chat.swarm.green serves, and why both roots

On 2026-09-27 `chat.swarm.green` and `cdn.chat.swarm.green` served Let's Encrypt's new hierarchy with its
cross-signatures: leaf → `YE2` → `ISRG Root YE` (signed by ISRG Root X2) → `ISRG Root X2` (signed by
ISRG Root X1). With the cross-signatures in the handshake, either root completes the path:
`openssl verify` succeeds with X1 alone, with X2 alone and with both. The recorded chain is kept as
`app/src/test/resources/swarm/chat-swarm-green-chain-2026-09-27.pem` and `SwarmTrustStoreTest` validates it
(PKIX, at a fixed date inside the leaf's validity) along both paths, checks that the store holds exactly
the two roots and not Signal's CA, and that it is a version-2 BKS file.

**Proposed:** when Let's Encrypt stops sending the cross-signed certificates, only chains to
`ISRG Root YE` / `ISRG Root YR` will be served; add those two roots then (same commands, from the JDK's
`cacerts` once it carries them). Until then X1 and X2 are what the brief asked for and what validates.

---

## 5. libsignal: the SWARM build, on its SWARM environment

**Status: implemented.** `org.signal:libsignal-android` / `libsignal-client` **0.102.2-swarm.1** from
[Swarm-Official/swarm-libsignal](https://github.com/Swarm-Official/swarm-libsignal) (release
`swarm-libsignal-0.102.2-swarm.1`, built from branch `swarm-v0.102.2` at `90a3a9ff6` by run 36353915436):
upstream 0.102.2 plus the SWARM network environment and nothing else; the protocol and cryptography crates
are byte-for-byte upstream. Followed exactly as its `docs/SWARM-MOBILE.md` says:

| File | Change |
| --- | --- |
| `settings.gradle.kts` | an `exclusiveContent` repository `SwarmLibsignal` = `https://raw.githubusercontent.com/Swarm-Official/swarm-libsignal/maven/` for `org.signal:libsignal-android` and `org.signal:libsignal-client` only, so Gradle never asks Signal's artifact host for them |
| `gradle/libs.versions.toml` | `libsignal-client = "0.102.2-swarm.1"` (both artifacts use it) |
| `gradle/verification-metadata.xml` | the two components replaced by the release's `SHA256SUMS.txt` values: AAR `6edcc0cf…10ad7`, its `.module` `c8082e9d…bf73`, JAR `1b029d0e…f1b2`, its `.module` `75a52c03…9546` |
| `app/build.gradle.kts` | `LIBSIGNAL_NET_ENV = Network.Environment.SWARM` (`chat.swarm.green`, TLS 1.3 to the public Let's Encrypt roots, HTTP/2 with the websocket and gRPC on one connection); `SWARM_LIBSIGNAL_NET = true`, so `SwarmNetwork.guard()` no longer blocks libsignal-net and the chat websocket is libsignal's `LibSignalChatConnection` again, as upstream. |

The SWARM environment deploys no contact discovery, no SVR2/SVRB and no key transparency (placeholders on
`service-not-deployed.swarm.invalid`) and runs no censorship-circumvention reflectors, so those features stay
unavailable. The OkHttp websocket path in `SwarmNetwork` stays in the tree as the fallback for a build made
against upstream libsignal (`SWARM_LIBSIGNAL_NET = false`), which also blocks every libsignal-net connection.

## 6. Publishing only SWARM builds

**Status: implemented.** Planner rule of 2026-09-27, after the first pre-release (the rebrand without the
endpoints, `swarm-messenger-android-20260927-38f0ae7`) was published and then deleted: no build that carries
Signal's servers may ever be offered for download.

- Job `apk`, step "SWARM endpoints in BuildConfig": fails unless the built `BuildConfig.SIGNAL_URL` is
  `https://chat.swarm.green` and `LIBSIGNAL_NET_ENV` is `Network.Environment.SWARM`; the relevant lines go
  into `buildconfig-endpoints.txt`, which travels with the APKs.
- Job `release`, step "Refuse to publish anything that is not a SWARM build": before any upload, checks
  again that the endpoint scan ran in **enforce** mode with **0 failures** and that
  `buildconfig-endpoints.txt` names `https://chat.swarm.green` and the SWARM environment. The release carries
  both files.
- A change reaches `swarm-main` (the only branch that publishes) only after the same commit has been green
  on `swarm-next`.

---

## 7. Wallet sign-in: registration without a telephone number

**Status: implemented; unit-tested** (see [`WALLET-SIGN-IN.md`](WALLET-SIGN-IN.md) for the flow, the protocol,
what the phrase may touch, and every test). In short:

| File | Change |
| --- | --- |
| `feature/registration/.../swarm/Bip39.kt`, `Bip39Wordlist.kt` (new) | BIP-39 as specified: 24 words from 32 bytes of entropy, the checksum, the NFKD-normalized seed (PBKDF2-HMAC-SHA512, 2048 rounds) |
| `feature/registration/.../swarm/SwarmWalletIdentity.kt` (new) | ACI and PNI identity key pairs from the seed (libsignal's HKDF with the labels `SWARM-Messenger-ACI-identity-v1` / `-PNI-identity-v1`, X25519 clamp, libsignal key types), the `+888` identifier, the challenge message and its XEdDSA signature. The three shared vectors match the server's and the desktop's |
| `feature/registration/.../screens/swarmwallet/*` (new) | the wallet screen: create (24 words once, "written down" checkbox) or restore; `FLAG_SECURE` while shown |
| `lib/network/.../api/SwarmWalletApi.kt` (new) | `POST /v1/swarm/registration/challenge` and `/verify` |
| `NetworkController.kt`, `RegistrationRepository.kt`, `RegistrationDependencies.kt`, `RegistrationNavigation.kt`, `WelcomeScreenViewModel.kt` | the calls, `registerAccountWithSwarmWallet` (upstream's recovery-password registration with the derived keys and the server's single-use password), the `SwarmWallet` route, Continue → wallet, no restore-or-transfer; all behind `isSwarmWalletSignIn`, which only the app turns on, so upstream's phone-number flow and its tests stay as they were |
| `AppRegistrationNetworkController.kt`, `ApplicationContext.java` | the calls over the app's REST client; wallet sign-in on |
| `SignalE164Util.kt`, `swarm_strings.xml` | a `+888` identifier is displayed as "SWARM account", never as a number |

No PIN: after registration the flow records the PIN opt-out and a new-account restore decision, exactly as
upstream does when a person skips the PIN, and completes.
