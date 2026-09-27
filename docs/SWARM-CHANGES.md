# SWARM Messenger for Android — every change from upstream Signal-Android

Upstream base: `6151a523373e02f37d6a367fe448bd15a3de1a72` = tag `v8.29.1` = upstream `main` on
2026-09-27, preserved in `Swarm-Official/swarm-messenger-android` as branch `upstream-main` and tag
`upstream-v8.29.1`. All SWARM work is on `swarm-main`, so every change below is
`git diff upstream-v8.29.1..swarm-main`.

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
| `upstream-main`    | signalapp/Signal-Android `main`, full history, untouched                    |
| `upstream-v8.29.1` | upstream's own annotated tag `v8.29.1`, re-published under a SWARM name     |
| `swarm-main`       | the SWARM fork: upstream-v8.29.1 plus the commits indexed in this file      |

Two documents live beside this one:

- [`SWARM-BUILD.md`](SWARM-BUILD.md) — how to build, test and release it, locally and in CI.
- This file — the running index of every change, grouped by purpose. Each section names the files.

---

## 1. Build and CI

| File | Change | Status |
| --- | --- | --- |
| `app/build.gradle.kts` | `websiteProdDebug` added to `selectableVariants`. Upstream builds `website` only as `Spinner` (starts an on-device database inspection server) or `Release` (minified, needs a signing key). | implemented |
| `.github/workflows/swarm-build.yml` (new) | GitHub-hosted `ubuntu-latest`: job `apk` builds `:Signal-Android:assembleWebsiteProdDebug` and runs the endpoint scan; job `unit-tests` runs the app's unit tests for the same variant plus `:feature:registration`, `:lib:libsignal-service`, `:lib:network`; job `release` (pushes to `swarm-main` only) publishes a pre-release `swarm-messenger-android-<yyyymmdd>-<shortsha>` with the APKs, `SHA256SUMS.txt` and the scan output. A Gradle user-home `gradle.properties` lowers the 12 GB heap `gradle.properties` asks for, so Gradle and the Kotlin daemon fit a 16 GB runner. | implemented |
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
