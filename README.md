# Kino TV

A personal Android TV client for kino.pub, built to resume what you're watching in one click.

`status: active, early (v0.1)` · `Kotlin · Jetpack Compose for TV · Media3 ExoPlayer` · `runs on: Chromecast with Google TV (sideloaded, local only)`

## What it does

A replacement for the official kino.pub TV app, for one user. When it opens, focus is already
on the first thing you're watching, so pressing OK resumes at the exact second. The home screen
is built around three jobs, in order of how often they come up:

1. **Continue.** In-progress serials and unfinished movies, most recent first. A large hero shows
   the focused title, its episode, and how much is left.
2. **Wishlist.** A kino.pub bookmark folder called `Wishlist`. Titles you haven't seen on the TV yet
   carry a "new" badge. *(Phase 2: send a screenshot from your phone to a bot, which recognizes
   the title and adds it.)*
3. **What's new.** New movies, new serials, and popular titles on kino.pub. *(Personal
   recommendations come later.)*

Long-press any card or episode for actions: mark watched (episode, everything up to here, season,
whole title), "Начал смотреть", remove from Continue, wishlist add/remove. Every action is written to
kino.pub immediately, so the official apps and the website see the same state. Continue is split into
two compact rows: grown-up titles first, then kids' (cartoons) titles. There's also a search screen
(movies / serials filter) behind the **Поиск** button (↑ from the first row).

The player seeks ±10 s with ← / → (hold to keep seeking). It has a tracks panel (press ↓) with one row each for voiceover, subtitles, and quality.
Every option is visible at once, so there are no lists to scroll. Your picks are saved per
serial and applied to the next episode automatically. Playback asks kino.pub for HEVC, 4K, and HDR
streams, and uses a SurfaceView so HDR10 and Dolby Vision reach the TV.

Wireframes: see [`docs/design-prompt.md`](docs/design-prompt.md). That file is also the prompt to
use for visual polish.

## Architecture

```
 Chromecast (Android TV 12+)                                 kino.pub
┌──────────────────────────────────────────────┐        ┌───────────────────────┐
│ MainActivity → KinoNavHost (Compose for TV)  │        │ api.service-kp.com    │
│   auth ─ home ─ details ─ player             │  HTTPS │  /oauth2/device       │
│      │      │       │        │               │◄──────►│  /v1/watching, history│
│      └── Repository ┴────────┘               │        │  /v1/items, bookmarks │
│            │            │                    │        │  /v1/device/settings  │
│      KinopubApi      LocalPrefs (DataStore)  │        └───────────────────────┘
│      (OkHttp)        tokens, track choices,  │        ┌───────────────────────┐
│            │         wishlist "seen" set     │  HLS4  │ kino.pub CDN          │
│      PlayerController (ExoPlayer, HLS) ──────┼───────►│ multi-audio m3u8 + srt│
└──────────────────────────────────────────────┘        └───────────────────────┘
         ▲ adb over Wi-Fi (scripts/tv.sh)
       Mac
```

- **Auth** uses the OAuth device flow. The TV shows a code, and you enter it at `kino.pub/device`.
  After login the app names the device and switches on HEVC, 4K, HDR, and the hls4 streaming type
  in kino.pub's per-device settings.
- **State** is kept on kino.pub where possible: watch progress (`watching/marktime`) and the
  wishlist (a bookmark folder). Only things kino.pub has no place for are stored on the TV: the
  per-title track choices and which wishlist items you've already seen.

## Getting started

Prerequisites (macOS, Homebrew):

```bash
brew install openjdk@21
```

```bash
brew install --cask android-commandlinetools android-platform-tools
```

```bash
/opt/homebrew/share/android-commandlinetools/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/homebrew/share/android-commandlinetools "platforms;android-36" "build-tools;36.0.0" "platform-tools"
```

Create `local.properties` (gitignored) with:

```
sdk.dir=/opt/homebrew/share/android-commandlinetools
```

Build:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:assembleDebug
```

### Put it on the TV (Wi-Fi)

One-time setup on the Chromecast:

1. Settings → System → About → click **Android TV OS build** 7 times to enable developer options.
2. Settings → System → Developer options → turn on **USB debugging**. If the menu has
   **Wireless debugging**, turn that on too.
3. Find the TV's IP in Settings → Network & Internet → your Wi-Fi.

Then, from the Mac:

```bash
scripts/tv.sh connect 192.168.1.50
```

The first connect shows an "Allow debugging?" prompt on the TV. Choose **Always allow**. If your
build only offers *Wireless debugging* with a pairing code, run
`scripts/tv.sh pair <ip:pair-port> <code>` first, then connect to the port it shows.

From then on, each change is one command:

```bash
scripts/tv.sh deploy
```

This builds, installs over Wi-Fi, and launches. `scripts/tv.sh logs` streams logcat, and
`scripts/tv.sh shot` saves a screenshot of the TV.

## Configuration

There are no env vars. The app uses the public device-flow client credentials of the open-source
Kodi plugin (see `KinopubApi.kt`). Tokens are stored on the TV in DataStore.

| Local file | Purpose |
|---|---|
| `local.properties` | Android SDK path (gitignored) |
| `.tv-address` | TV `ip:port` remembered by `scripts/tv.sh` (gitignored) |

## Project layout

```
app/src/main/java/dev/dt/kinopub/
  KinopubApp.kt            Application + AppContainer (manual DI, OkHttp, Coil)
  MainActivity.kt          single activity
  data/api/                KinopubApi (hand-written OkHttp client) + DTOs
  data/auth/               TokenStore, DeviceRegistration (4K/HDR/hls4 settings)
  data/prefs/LocalPrefs.kt per-title TrackChoice, wishlist "seen" set
  data/Repository.kt       home assembly, resume-point logic, wishlist folder
  ui/Navigation.kt         routes: auth, home, details/{id}, player/{id}?season&episode&start
  ui/home/                 Continue hero + adult/kids rows, wishlist, new releases
  ui/search/               search with movie/serial filter
  ui/common/               cards, long-press ActionMenu
  ui/details/              title page, seasons, episodes
  ui/player/               PlayerController (ExoPlayer + track memory), PlayerScreen (OSD, tracks panel)
  ui/theme/Theme.kt        design tokens (object K)
docs/kinopub-api.md        kino.pub API reference (verified vs UNVERIFIED notes)
docs/design-prompt.md      UI spec / prompt for visual design
scripts/tv.sh              Wi-Fi deploy helper
```

## Deploy

There is no store release. The debug APK is the build that runs on the TV, installed with
`scripts/tv.sh deploy`.

## License

Private, © dt. Personal use.
