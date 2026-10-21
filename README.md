# denopub

**denopub** — a personal Android TV client for kino.pub, built to resume what you're watching in one click.

`status: active, in daily use (v0.2)` · `Kotlin · Jetpack Compose for TV · Media3 ExoPlayer` · `runs on: Chromecast with Google TV (sideloaded, local only)`

## What it does

A replacement for the official kino.pub TV app, for one user. When it opens, focus is already
on the first thing you're watching, so pressing OK resumes at the exact second. The home screen
is built around three jobs, in order of how often they come up:

1. **Continue.** Three rows — Сериалы, Фильмы, Детям — each with the most recently watched title
   first. A compact header shows the focused title, its episode, and how much is left.
2. **Wishlist.** A kino.pub bookmark folder called `Wishlist`. Titles you haven't seen on the TV yet
   carry a "new" badge. *(Phase 2: send a screenshot from your phone to a bot, which recognizes
   the title and adds it.)*
3. **What's new.** New movies, new serials, and popular titles on kino.pub. *(Personal
   recommendations come later.)*

Long-press any card for actions: mark watched, "Начал смотреть", remove from Continue, wishlist
add/remove. In a title card, long-press an episode or season to toggle it watched. Every action is
written to kino.pub immediately, so the official apps and the website see the same state. The title
card also shows the English IMDb description (OK on the description opens it in full) and
IMDb/kino.pub trailers. There's a search screen (movies / serials filter) behind **Поиск**.

The player has three levels: ↓ shows the seek bar (← → scrub with a preview frame, faster the
longer you hold), ↓ again opens one row each for voiceover, subtitles and quality (every option
visible at once), and ↑ shows a frame-stepping bar on top; ↑ hides each level again. With no
overlay, ← → jump straight away (3 s, growing to 5 s, 10 s, 30 s, 1 min, 5 min as you keep
pressing or hold) and show only a "+N с" label. OK pauses; the pause sign shrinks to a corner badge. ↑ enters a frame-stepping mode that stays
until you leave it with ↓ or Back.

Home has a **Спорт** row: Eurosport, Okko and Match channels from kino.pub's live TV, each with
what's on now (free iptvx.one XMLTV guide) and a fresh still frame cut from the live stream every
few minutes while Home is open; the focused channel plays live, muted, right in its card.
Track picks are saved per title and applied to the next episode automatically. Playback asks
kino.pub for HEVC, 4K and HDR streams and uses a SurfaceView so HDR10 and Dolby Vision reach the TV.

**On the owner's TV** (Hisense VIDAA + this Chromecast): the TV starts on the dongle's input
("Default Startup Page = Last Accessed Source"), and denopub is the first app tile on Google TV.
Google TV's own "Continue watching" row and widgets are not available to sideloaded apps.

Current state, open items and how to resume work: [`HANDOFF.md`](HANDOFF.md).

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
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:assembleRelease
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

Chromecast with Google TV (Android 14) uses **Wireless debugging**: on the TV open Developer
options → Wireless debugging → *Pair device with pairing code*, then run
`scripts/tv.sh pair <ip:pair-port> <code>` once. The connect port changes after every reboot;
`scripts/tv.sh deploy` rediscovers it via mDNS, so no reconnecting by hand. (Owner's TV:
"Living Room TV", 192.168.1.106.)

From then on, each change is one command:

```bash
scripts/tv.sh deploy
```

This builds the optimized release APK (R8, signed with the debug key), installs it over Wi-Fi, and
launches it. `scripts/tv.sh logs` streams logcat, and
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
  data/api/ImdbClient.kt   IMDb plot + trailers (unofficial GraphQL)
  data/prefs/LocalPrefs.kt track choices, wishlist seen-set, kids flags, recency stamps
  data/Repository.kt       home assembly, resume-point logic, wishlist folder
  ui/Navigation.kt         routes: auth, home, details/{id}, player/{id}?season&episode&start
  ui/home/                 compact header + Continue rows (serials/movies/kids), wishlist, new releases
  ui/search/               search with movie/serial filter
  ui/common/               cards, long-press ActionMenu
  ui/details/              title page: plots, trailers, seasons, episodes
  ui/player/               PlayerController (ExoPlayer, voiceovers, preview player), PlayerScreen
                           (fine bar / seek bar / tracks panel), TrailerScreen
  system/                  WatchNext (Google TV row, filtered), WakeService (inert, declined)
  ui/theme/Theme.kt        design tokens (object K)
docs/kinopub-api.md        kino.pub API reference (verified vs UNVERIFIED notes)
docs/design-prompt.md      UI spec / prompt for visual design
docs/brand-prompts.md      image prompts for icon, TV banner, splash mark
docs/lenapub-kit/          self-contained prompt + assets + reference screenshots for a fresh Claude
                           to rebuild this app as "lenapub" for another TV (start: START-HERE.md)
scripts/tv.sh              Wi-Fi deploy helper
```

## Deploy

There is no store release. The R8 release APK (signed with the debug key) is the build that runs
on the TV, installed with `scripts/tv.sh deploy`.

## License

Private, © dt. Personal use.
