# REPLICATE — make your own copy of denopub for another TV

**For an agent told "take everything from here and do the same".** This repo is a complete,
working Android TV client for kino.pub. To give someone else the same app, **fork the source and
rebrand it** — don't rewrite it. Everything below is the checklist; the rest of the repo
(`CLAUDE.md`, `README.md`, `HANDOFF.md`, `docs/kinopub-api.md`) explains how it works.

Ask the person three things before you start (in plain words, in their language):
1. **Name** of the app (e.g. `lenapub`). It becomes the launcher label, the device title on kino.pub
   and the package suffix.
2. **Which TV device** (Chromecast with Google TV, Xiaomi stick, a Google/Android TV set…) and
   whether it's on the same Wi-Fi as this Mac.
3. **Which owner-only features to keep** (see §4). Default: drop the IMDb *rating* feature, keep
   everything else.

## 1. Two paths

| Path | When | Start |
|---|---|---|
| **A. Fork the source** (recommended) | the agent can read this repo (it is private: the owner must add the person as a collaborator, or hand over a clone/zip) | §2 below |
| **B. Rebuild from spec** | no access to the source, only a self-contained kit | `docs/lenapub-kit/START-HERE.md` → `PROMPT.md`. Spec frozen 2026-10-21: it predates the IMDb rating, prev/next episode cards and the Continue changes listed in `git log` after `a76677a`. Where kit and source differ, **the source wins**. |

## 2. Set up the Mac (skip what's installed)

Follow `docs/lenapub-kit/PROMPT.md` §3 "Phase A" — it is current and covers Apple Silicon vs
Intel prefixes. In short:

```bash
brew install openjdk@21 git
brew install --cask android-commandlinetools android-platform-tools
yes | sdkmanager --sdk_root=$(brew --prefix)/share/android-commandlinetools --licenses
sdkmanager --sdk_root=$(brew --prefix)/share/android-commandlinetools "platforms;android-36" "build-tools;36.0.0" "platform-tools"
```

Homebrew itself needs the person's Mac password: ask them to paste the installer line from
brew.sh into Terminal themselves. `scripts/tv.sh` expects
`JAVA_HOME=/opt/homebrew/opt/openjdk@21` and `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`
(both overridable by env). Write `local.properties` with `sdk.dir=<ANDROID_HOME>`. The wrapper is
in the repo — use `./gradlew` (8.14.3), never Homebrew `gradle`. compileSdk 36, **minSdk 31**
(Android 12): an older TV device needs `minSdk` lowered and a test pass.

## 3. Fork and rebrand

Work in a **new repo** (`~/Projects/<name>`), not in this one: copy the tree without `.git`,
`build/`, `.gradle/`, `local.properties`, `.tv-address`, `.claude/`, then `git init`.

| What | Where | Change to |
|---|---|---|
| App name | `app/src/main/res/values/strings.xml` (`app_name`, wake-service strings) | `<name>` |
| applicationId + namespace | `app/build.gradle.kts` | `dev.<owner>.<name>` — **must differ from `dev.dt.kinopub`**, or it overwrites denopub on a shared TV and can't coexist |
| Kotlin package | `app/src/main/java/dev/dt/kinopub/**` | optional; leaving `dev.dt.kinopub` as the *source* package is fine (namespace ≠ package is legal) — skip it unless asked, it's a big noisy diff |
| Device title on kino.pub | `data/auth/DeviceRegistration.kt` (`"denopub (${Build.MODEL})"`, `software = …`) | `<name>` |
| Deep-link scheme | `AndroidManifest.xml` (`android:scheme="denopub"`), `system/WatchNext.kt` | `<name>` |
| Log tag | `"denopub"` across the code | `<name>` (then `adb logcat -s <name>`) |
| Gradle root name | `settings.gradle.kts` `rootProject.name` | `<name>-tv` |
| `PKG=` | `scripts/tv.sh` | the new applicationId |
| Brand art | `app/src/main/res/{mipmap-*,drawable*}` — launcher icons, TV banner (640×360 xhdpi, 960×540 xxhdpi), `splash_screen.jpg` 1920×1080, `splash_mark.xml` | For **lenapub** ready-made files are in `docs/lenapub-kit/assets/res/` — copy over. For another name: adapt `docs/lenapub-kit/assets/brand/render_brand.py` (Pillow; draws lenapub's "l" capsule mark — change the geometry and wordmark) or design new ones; prompts in `docs/brand-prompts.md`. |
| Docs | `README.md`, `CLAUDE.md`, `HANDOFF.md` | rewrite for the new owner/device; keep the Gotchas sections — they apply verbatim |

`grep -rn "denopub\|dev.dt.kinopub\|kinopub-android-tv" --exclude-dir=build .` should end up
showing only intentional references (credits, the kinopub API name).

## 4. Owner-specific bits — decide each one

| Feature | Code | Default for a copy |
|---|---|---|
| **IMDb rating** (rate dialog after a movie, «Оценить на IMDb» on the title card) | `data/api/ImdbRater.kt` (`USER = "ur0218725"`, `BASE = den.tanaev.su/movies`), `ui/common/RateDialog.kt`, `PlayerController.offerRating`, `DetailsScreen` (rating button, `myRating`) | **Remove.** It reads and writes *denopub owner's* IMDb account through his private server. Never point a copy at it. Delete the button, the `offerRating` call and the rater from `AppContainer`. |
| IMDb plot + trailers | `data/api/ImdbClient.kt` | Keep — public, no account. |
| kino.pub client creds | `KinopubApi.kt` `client_id = "xbmc"` | Keep — the Kodi plugin's public creds. The new person logs in with **their own** kino.pub account (device code on kino.pub/device). |
| Wishlist folder name | `Repository.WISHLIST_TITLE = "Wishlist"` (also matches «Хочу посмотреть») | Keep; auto-created on first add. |
| Sport row | `data/Sport.kt` (`lineup`), `ui/home/LivePreview.kt` | Keep if they watch sport; else hide the row in `HomeScreen`. Channels come from *their* kino.pub `/v1/tv`, so the lineup may need trimming. |
| WakeService / WatchNext | `system/` | Keep inert (both are dead ends on Google TV, see CLAUDE.md). |

## 5. Adapt to the device

Most tuning in this repo is for a **Chromecast with Google TV (Android 14)** on a 4K HDR panel.
On another device, check (and record findings in the new `HANDOFF.md`):
- Android version → wireless debugging (11+: `scripts/tv.sh pair` + mDNS; 9–10: `adb tcpip`/
  `scripts/tv.sh connect <ip>`). `minSdk` in `app/build.gradle.kts` must cover it.
- Decoder caps (`adb shell dumpsys media.codec` / `MediaCodecList`): HEVC, HDR, max height —
  `DeviceRegistration` reports them to kino.pub; the default quality cap is `TrackChoice.maxHeight`.
- Remote keys: verify ↑↓←→, OK long-press, Back, media keys arrive (some sticks lack ⏪⏩).
- Performance: Home and the EPG parse (~170 MB XML stream) were measured on the Chromecast;
  a weaker stick may need a longer Sport refresh interval.

## 6. Build, install, verify

```bash
./gradlew :app:assembleRelease
scripts/tv.sh pair <ip:port> <code>    # once: TV → Developer options → Wireless debugging → Pair
scripts/tv.sh deploy
```

Walk the person through enabling developer mode on the TV (Settings → About → press *Build* 7×,
then Developer options → USB/Wireless debugging) and confirming the "Allow debugging" prompt.

Verify on the real TV with `scripts/tv.sh shot` before saying done — use the acceptance checklist
at the end of `docs/lenapub-kit/PROMPT.md` (§14), minus anything removed in §4. Rules from
CLAUDE.md that bite here: the TV uses the person's **real** kino.pub account — screenshot before
every OK in "mark watched"/"remove" flows; an install kills playback.

## 7. Hand over

Commit + push the new repo (its own GitHub repo, private). Tell the person, in plain words: how to
update (`cd ~/Projects/<name> && scripts/tv.sh deploy`, TV awake), that the debugging port changes
after a reboot (the script rediscovers it), and how to re-login (Settings → Apps → <name> →
Clear data).
