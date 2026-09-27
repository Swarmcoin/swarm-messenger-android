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
