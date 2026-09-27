# Building SWARM Messenger for Android

SWARM Messenger for Android is a fork of Signal-Android `v8.29.1`. It builds exactly the way upstream
builds, with one flavor chosen and one set of endpoints changed. Every change is indexed in
[`SWARM-CHANGES.md`](SWARM-CHANGES.md).

## What gets built

| Thing              | Value                                                                                              |
| ------------------ | -------------------------------------------------------------------------------------------------- |
| Gradle project     | `:Signal-Android` (the `app/` directory; upstream's project name is kept so upstream merges stay small) |
| Variant            | `websiteProdDebug` — the `website` distribution flavor, `prod` environment, `debug` build type     |
| Signing            | the Android debug key of whoever builds it (a CI runner generates a fresh one per run)             |
| Output             | `app/build/outputs/apk/websiteProd/debug/*.apk`: one APK per ABI plus a universal one              |

### Why `website`, and what "no Play services" means at v8.29.1

Upstream has four distribution flavors: `play`, `website`, `github` and `nightly`. At `v8.29.1` **none of
them is free of Google Play services at the dependency level**: `firebase-messaging`,
`play-services-maps`, `play-services-auth` and the Google sign-in library are `implementation`
dependencies of every flavor in `app/build.gradle.kts`. The `website` flavor is the one upstream ships
outside the Play Store, and the one that already runs on a phone without Google Play services (it
falls back to a persistent websocket instead of FCM). So `website` is the flavor used here, as the
brief asked, and FCM is switched off in the SWARM build itself rather than by flavor — see
`SWARM-CHANGES.md`, "Push messaging".

`websiteProdDebug` is not in upstream's `selectableVariants`; upstream only builds `website` as
`Spinner` (a debugging build that starts an on-device database inspection server — not something to
hand to people) and `Release` (minified, needs a real signing key). Adding `websiteProdDebug` to that
list is a one-line change in `app/build.gradle.kts`.

## Build it in CI (the normal way)

`.github/workflows/swarm-build.yml` runs on GitHub-hosted `ubuntu-latest` for every push to
`swarm-main` and `swarm-next`, every pull request and on manual dispatch. Jobs:

| Job          | What it does                                                                                                   |
| ------------ | -------------------------------------------------------------------------------------------------------------- |
| `apk`        | `assembleWebsiteProdDebug`, the SWARM endpoint scan over the shipped configuration (enforce mode), a check that `BuildConfig` names `https://chat.swarm.green` and libsignal's SWARM environment, then SHA-256 sums |
| `unit-tests` | the app's own unit tests for the same variant, `:feature:registration`, `:lib:libsignal-service`, `:lib:network` (the SWARM tests are among them) |
| `release`    | pushes to `swarm-main` only: refuses to go on unless the scan ran in enforce mode with no failure and the build is a SWARM build, then publishes a GitHub **pre-release** tagged `swarm-messenger-android-<yyyymmdd>-<shortsha>` with the APKs, `SHA256SUMS.txt`, the scan output and the `BuildConfig` endpoint lines |

A change goes to `swarm-next` first; `swarm-main` fast-forwards to it once that commit is green. Pull
request runs build and test but publish nothing.

`.github/workflows/swarm-emulator-smoke.yml` is the end-to-end proof: it builds the same APK, boots an
API 34 x86_64 emulator on a hosted runner (KVM), and `scripts/swarm/emulator_smoke.py` creates a
**disposable** SWARM wallet account on `chat.swarm.green` through the real screens, names the profile and
waits for the conversation list. Screenshots, UI dumps and the app's log lines are the run's artifact. It
creates a real account on the staging server, so it runs only on a push to `swarm-smoke` or by hand.

libsignal is the SWARM build (`org.signal:libsignal-android` `0.102.2-swarm.1` from
`https://raw.githubusercontent.com/Swarm-Official/swarm-libsignal/maven/`, pinned in
`gradle/verification-metadata.xml`); Gradle fetches it like any other dependency. Signal's own artifact
host is still used for the other `org.signal` libraries (ringrtc, sqlcipher), at build time only.

The APKs are signed with the runner's throwaway debug key, so **two releases are signed by different
keys**: Android refuses to update one with the other, and the old one has to be uninstalled first.
A stable signing key is an owner decision (a key in a GitHub secret, or Play App Signing); no agent
creates one.

## Build it on your own machine

Requirements, all from `gradle/libs.versions.toml` and `gradle/wrapper/gradle-wrapper.properties`:

| Tool                | Version                                     |
| ------------------- | ------------------------------------------- |
| JDK                 | 21 (Temurin is what CI uses)                |
| Gradle              | 9.6.0, through `./gradlew` (downloads itself) |
| Android SDK platform| 37 (`compileSdk`), target 36, min 23        |
| Build tools         | 36.0.0                                      |
| NDK                 | 28.0.13004108 (used to strip native libraries) |

```sh
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk     # platform 37, build-tools 36.0.0, NDK 28.0.13004108
./gradlew :Signal-Android:assembleWebsiteProdDebug
```

`gradle.properties` asks for a 12 GB Gradle heap. On a smaller machine pass
`-Dorg.gradle.jvmargs="-Xmx6g -XX:MaxMetaspaceSize=1g"` — that is what CI does on its 16 GB runners.

On the SWARM workstation, keep every cache on `D:` (see `D:\swarm-messenger\AGENT-ENV.md`):
`GRADLE_USER_HOME=D:\swarm-messenger\.cache\gradle`, the SDK under
`D:\swarm-messenger\.tools\android-sdk`.

## Tests

```sh
./gradlew :Signal-Android:testWebsiteProdDebugUnitTest --tests 'org.thoughtcrime.securesms.swarm.*'   # the SWARM tests only
./gradlew :Signal-Android:testWebsiteProdDebugUnitTest                                              # the whole app suite
```

## The endpoint scan

`scripts/swarm/endpoint_scan.py` fails the build if the configuration that ships in the APK names a
Signal host or a Firebase/FCM setting. Run it after `assembleWebsiteProdDebug`:

```sh
python3 scripts/swarm/endpoint_scan.py
```

What it reads and what it refuses is written at the top of the script.
