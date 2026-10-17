# CLAUDE.md — denopub

## Response language

Reply in English by default, even when the user writes in Russian (Cyrillic ≈ 2× tokens).
Switch only when explicitly asked. App UI copy is Russian; code, comments, docs, commits are English.

## TL;DR + read order

Personal Android TV (Chromecast with Google TV) client for kino.pub. One user, remote-only,
optimized for "resume what I'm watching in one click". Read in order:
1. `README.md` — what/why, architecture, deploy over Wi-Fi.
2. `docs/kinopub-api.md` — the API, with UNVERIFIED markers. Trust it over memory.
3. `data/Repository.kt` → `ui/home/HomeScreen.kt` → `ui/player/PlayerController.kt`.
4. `docs/design-prompt.md` — the agreed screen structure (wireframes approved 2026-10-16).

## Mental model

- **kino.pub is the source of truth** for progress and the wishlist; the TV stores only what the
  API has no slot for (`LocalPrefs`: per-title `TrackChoice`, wishlist seen-set; `TokenStore`).
- **Continue row** = `/v1/history` (order + last-touched episode, 2 pages) ∪ `watching/serials`
  (both `subscribed=0|1`) ∪ `watching/movies`, then `/v1/watching?id=` per title to compute the
  resume point: first non-finished episode at/after the last touched one. Same rule in Details.
- **Wishlist** = bookmark folder named `Wishlist` (also matches "Хочу посмотреть"), auto-created.
  "New" = item id not in the local seen-set; marked seen when its card gets focus.
- **Playback**: `PlayerController` (an AndroidViewModel) owns ExoPlayer for a session that can
  span episodes. File choice: highest `h` ≤ `TrackChoice.maxHeight`, HEVC preferred at equal
  height. URL preference hls4 → hls → http. Audio tracks map to kino.pub `audios[]` **by order**
  (1-based `index` = manifest order). External subtitles are sideloaded `SubtitleConfiguration`s.
  Track picks are saved on manual change and re-applied once per episode in `onTracksChanged`.
- **Progress**: `marktime` every 30 s while playing, on pause, on exit; `toggle?status=1` at end,
  then auto-advance to the next episode (crossing seasons).
- **Continue rows** are split adult / kids (`isKidsContent`: genre starts with "мульт" or "детск";
  "Семейный" and "Аниме" deliberately excluded). Genres come from `/items/{id}`, cached per process.
- **Every user action writes to kino.pub** (no local-only state besides track picks and the
  wishlist seen-set): watched/unwatched = `watching/toggle?status=1|0` (episode, season, or every
  season), "Начал смотреть" = `togglewatchlist` for serials / 1-second `marktime` for movies,
  "Убрать из «Продолжить»" = `history/clear-for-item` + unsubscribe, wishlist = bookmarks add/remove.
- Kids/adult flags are cached persistently (`LocalPrefs.kidsFlags`); Home renders from the cache
  and `HomeViewModel.classifyNewTitles()` fetches genres only for unseen titles afterwards
  (calling `/items` for ~30 titles inline made first launch >10 s).
  Continue = history ∪ *subscribed* serials ∪ unfinished movies, so removal sticks.
- **Long-press menus** (`ui/common/ActionMenu.kt`) on every card and episode row.
- **Focus is the UI.** Every screen requests initial focus on its primary action (first Continue
  card / Play button / current audio chip). Lazy rows compose late → focus requests retry briefly.

## How to develop here

- Build: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:assembleRelease` — the **release**
  build (R8, signed with the debug key, ~5 MB) is what runs on the TV; debug Compose is far slower
  (170 skipped frames at startup on the emulator). Debug and release share the signing key, so they
  install over each other and keep the login.
- Run on TV: `scripts/tv.sh deploy` (needs `scripts/tv.sh connect <ip>` once). Logs: `scripts/tv.sh logs`.
- Emulator: AVD `kino_tv` (system-images;android-34;android-tv;arm64-v8a, 1920×1080). Start with
  `$ANDROID_HOME/emulator/emulator -avd kino_tv -no-snapshot-save`, then `adb install -r
  app/build/outputs/apk/debug/app-debug.apk`. `adb shell input text` can't type Cyrillic. No tests yet.
- Add an endpoint: DTO in `data/api/Models.kt` (nullable/defaulted fields; Json ignores unknown
  keys), method in `KinopubApi`, use from `Repository`. Update `docs/kinopub-api.md` if you
  verified something marked UNVERIFIED.

- App name is **denopub** (label, device name in the kino.pub cabinet). Package/applicationId stay
  `dev.dt.kinopub` — changing it would install a second app and lose the login.
- Splash: `ui/SplashOverlay.kt` (Compose) shows the mark until `AppContainer.uiReady` (Home data or
  login code), max 5 s. The system splash (`Theme.Kinopub.Splash`) only bridges process start —
  the Android TV image doesn't draw its icon, so never hold it with `setKeepOnScreenCondition`.
- Brand art (2026-10-16, from Claude Design): sources in `docs/brand/` (SVG mark, banner, icon);
  resources: `drawable-{xhdpi,xxhdpi}/banner.png`, `mipmap-*/ic_launcher.png`,
  `drawable-nodpi/splash_screen.jpg` (full-frame splash used by `SplashOverlay`),
  `drawable/splash_mark.xml` (vector of the mark for the system splash). Prompts: `docs/brand-prompts.md`.
- Home is cached on disk (`filesDir/home.json`) and rendered instantly on launch; fresh data
  replaces it, and focus jumps back to the first card only if the user hasn't moved yet.

- **Google TV integration (option 2, 2026-10-17):**
  - `system/WakeService.kt` — AccessibilityService. On `SCREEN_ON` (dongle woken by the TV over
    CEC) it waits 2 s and, if the Google TV launcher/ambient is in front, starts `MainActivity`.
    An accessibility service is used because Android 14 blocks background activity starts for
    plain receivers. The user enables it once: Settings → System → Accessibility → denopub.
    Owner's setup: Hisense VIDAA "Default Startup Page = Last Accessed Source" + CEC on both sides,
    so TV power-on lands on the Chromecast.
  - `system/WatchNext.kt` — mirrors Continue into the system Watch Next row after each Home load;
    tiles open `denopub://play?id&season&episode&start` → `AppContainer.pendingPlay` → player.
    **Verified 2026-10-17: Google TV does NOT show them.** Rows are accepted (`watch next synced: 9`)
    but filtered; the Continue watching row only shows allow-listed partner apps ("Your services"
    lists only catalog partners). The Engage SDK continuation cluster was tried too:
    `isServiceAvailable` → `false` for this sideloaded app (enrollment/Play distribution needed), so
    it was removed. Kept Watch Next (harmless; would light up if Google ever relaxes the filter).
    Google TV has no home-screen widgets; the only surface we own there is the app tile.
  - `WakeService` exists but the owner chose NOT to auto-open on power-on (2026-10-17); it stays
    inert unless enabled in Accessibility settings.

## Conventions

- Manual DI via `AppContainer`; no Hilt. Hand-written OkHttp client; no Retrofit.
- Design tokens live in `ui/theme/Theme.kt` (`object K`); don't hardcode colors in screens.
- Russian counts go through `plural(n, one, few, many)` (`ui/common/Cards.kt`) — "4 серии", not "4 серий".
- Russian UI strings inline for now (single-user app); move to `strings.xml` if it grows.

## Gotchas & known issues

- **Gradle**: Homebrew's `gradle` is 9.x and cannot even run `wrapper` inside this project (AGP 8
  uses an internal API removed in Gradle 9.6). Always use `./gradlew` (8.14.3). Regenerate the
  wrapper from an empty dir if ever needed.
- **JDK**: system Java is absent; use `openjdk@21` (keg-only). Don't add `jvmToolchain(17)` —
  no JDK 17 is installed and toolchain download is not configured.
- `tv-material` chips (`FilterChip`) are experimental → opt-in set in `app/build.gradle.kts`.
- API: `items/fresh` vs `items/new` disagree between docs and clients — `fresh()` tries both.
  `device/info` / settings reportedly 500'd for another client in Sep 2026; registration is
  best-effort (`runCatching`), so 4K/HDR may silently stay off. Check the device in the kino.pub
  cabinet if 4K files don't show up.
- HDR marking in `files[]` is UNVERIFIED; we only prefer `codec == "h265"` at equal height.
- Client credentials are the Kodi plugin's public ones (`xbmc`). If kino.pub revokes them, ask
  support@kino.pub for own creds.
- **`watching/marktime` stores time but does NOT change status** (stays -1). "In progress" must be
  computed as `time > 0 && status != 1` everywhere (Repository, Details). Wrong once → resume showed 0.
- **Writes from `onCleared` must not use `viewModelScope`** (already cancelled) — use
  `AppContainer.appScope`. Progress on exit was silently lost before this.
- **Long-press opens a dialog while OK is still held**: its repeats/release would click the first
  item. `ActionMenu` swallows OK until a fresh key-down (repeatCount == 0). Keep that guard.
- `BasicTextField` swallows the D-pad on TV — Search moves focus out on ↓ manually.
- Player Back: closes the tracks panel; otherwise exits immediately (OSD is not modal).
- **PlayerView must be non-focusable** (`isFocusable=false`, `FOCUS_BLOCK_DESCENDANTS`): it eats
  D-pad keys otherwise, so ↓ "sometimes" didn't open the panel on the real TV. The Compose root
  re-requests focus on every overlay change except the tracks panel.
- **Home Back**: first Back → scroll to top + focus first Continue card; there, Back shows
  "Нажмите «Назад» ещё раз…" and a second Back within 2.5 s finishes the activity. Returning from
  any screen restores focus to the card you left (`HomeViewModel.lastFocusKey`, keys `c:`/`w:`/`<row>:`).
- **Player overlay levels** (rule: ↓ reveals the next level, ↑ hides the current one):
  NONE → ↑ FINE (top bar, frame stepping 0.5 s→1 s→3 s while held, EXACT seeks, pauses) ·
  NONE → ↓ OSD (seek bar with white position marker; ← → scrub with preview, commits ~0.9 s after
  key-up or on OK) → ↓ TRACKS panel (↑ from its top row → OSD). Paused = full-screen dim + ❚❚.
  Player holds the chosen quality (`setForceHighestSupportedBitrate`), ABR used to sit at 1080p.
- **Home Continue = three rows**: Сериалы / Фильмы / Детям, each sorted by `lastActivityMs` =
  max(history last_seen, "Начал смотреть"). **No caps**: every candidate (~60: history +
  subscriptions + unfinished movies) is checked and every row shows all it has — a 40/24 cap
  silently dropped subscribed serials without recent history (Peppa, 2026-10-17). Diagnostics:
  `adb logcat -s denopub | grep continue:` lists candidates and why each one was dropped.
- Player overlays animate (slide from top/bottom, panel expands); scrub and fine steps accelerate by
  **hold time** (scrub 10 s→30 s→1 min→2 min→5 min, fine 0.5→1→2→5 s).
- **Title card**: Russian plot (kino.pub) + English plot and trailers/teasers from IMDb
  (`data/api/ImdbClient.kt`, unofficial GraphQL `caching.graphql.imdb.com`; imdb.com HTML is behind
  an anti-bot wall) + kino.pub trailer (`items/trailer`). Trailers play in `TrailerScreen`.
- Player seeking: ← / → accelerate while held (10 s → 30 s → 1 min → 2 min, by repeatCount).
  With the tracks panel open, ↑ focuses the seek bar = scrubber: a second muted 360p ExoPlayer
  (`PlayerController.previewPlayer`) decodes the frame at the marker into a TextureView; OK jumps.
  kino.pub has no thumbnail sprites, hence the real-frame preview.
- Tracks panel focus: the selected chip may be off-screen (not composed) — the row scrolls to it,
  falls back to the first chip, and the root pulls focus into the panel on the next D-pad press.
- Continue ordering: titles started via "Начал смотреть" (`LocalPrefs.startedAt`) and subscribed
  serials with nothing watched lead the row; otherwise they fell past the 30-candidate cut.
- Quality labels go by **width** (3840×1920 is 4K; height-based labelling showed it as 1080p).
- NavHost transitions are disabled: a SurfaceView can't fade, leaving the player blinked.
- The player logs (tag `denopub`) the HLS master's `#EXT-X-MEDIA` lines and the audio tracks vs
  kino.pub `audios[]` — added to debug voiceover switching (open as of 2026-10-17).
- Real TV: Chromecast "Living Room TV", Android 14, wireless debugging at 192.168.1.106 (connect
  port changes; `tv.sh` rediscovers it via mDNS). Check `dumpsys audio | grep state:started`
  before deploying — an install kills playback.
- **Testing on the emulator uses the owner's real kino.pub account.** Never drive "mark watched" /
  "remove" flows blind with adb — screenshot before every OK. (2026-10-16: a blind key sequence
  marked a whole season watched; it was restored via the app.)
- Verified on the Android TV emulator (AVD `kino_tv`, API 34, 1080p) on 2026-10-16: login, Home,
  Details, Search, player with 4K HEVC + multi-audio hls4. **Not yet on the real Chromecast.**

## What NOT to touch

- `gradle/wrapper/*` except via a deliberate wrapper upgrade.

## Secrets & data

- No secrets in the repo. OAuth tokens live only on the TV (DataStore `auth`).
- `local.properties`, `.tv-address` are gitignored and machine-local.

## Roadmap

1. Verify on the Chromecast: login, Continue row, hls4 audio mapping, 4K/HDR file selection.
2. Wishlist ingest: Telegram bot (phone → screenshot → vision model identifies title →
   kino.pub search → `bookmarks/add` to `Wishlist`).
3. "Next episode in 10 s" card, skip-intro, voice search.
4. Recommendations from new releases + watch history.
5. Visual polish via `docs/design-prompt.md`.

## Git / backup rule

Commit after every meaningful change, with docs updated in the same commit, and push to
`origin` = `github.com/deetan/kinopub-android-tv` (private).

## Open items (2026-10-17)

- Voiceover switching: **fixed 2026-10-17, awaiting owner confirmation on the TV.** Root cause:
  hls4 repeats every voiceover once per quality (GROUP-ID audio2160/1080/720/480), so ExoPlayer has
  one audio TrackGroup per voiceover with a copy per quality; we used to treat each *track* as a
  voiceover (64 "options", mapped by position → wrong voice). Now one option per group, override
  with all its tracks, kino.pub metadata matched by the "NN." prefix of the rendition NAME.
- Screensaver "widget" was offered and declined.
