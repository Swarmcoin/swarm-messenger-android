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

### libsignal, until the SWARM build of it is in

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
