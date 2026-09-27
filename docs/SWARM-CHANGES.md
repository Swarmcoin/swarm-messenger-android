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
