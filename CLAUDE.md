# CLAUDE.md — denopub

## Response language

Reply in English by default, even when the user writes in Russian (Cyrillic ≈ 2× tokens).
Switch only when explicitly asked. App UI copy is Russian; code, comments, docs, commits are English.

## TL;DR + read order

Personal Android TV client for kino.pub ("denopub"), sideloaded on the owner's Chromecast with
Google TV. One user, remote-only, built for "resume what I'm watching in one click". Read in order:
1. **`HANDOFF.md`** — current state, open items, how to resume, owner's preferences.
2. `README.md` — what/why, architecture, setup, deploy over Wi-Fi.
3. `docs/kinopub-api.md` — the API, with UNVERIFIED markers. Trust it over memory.
4. Code: `data/Repository.kt` → `ui/home/HomeScreen.kt` → `ui/player/PlayerController.kt` →
   `ui/player/PlayerScreen.kt` → `ui/details/DetailsScreen.kt`.

## Mental model

- **kino.pub is the source of truth** for progress, watched status and the wishlist. The TV
  keeps only what the API has no slot for (`LocalPrefs`): per-title `TrackChoice`, wishlist
  seen-set, kids/adult genre flags, and the recency stamps `startedAt` / `playedAt`.
- **Home Continue = three rows**: Сериалы / Фильмы / Детям (`HomeData.continueSerials/Movies/Kids`).
  `Repository.continueWatching` builds candidates from: local `playedAt`/`startedAt` titles, then
  `/v1/history` (2 pages), subscribed serials (`watching/serials?subscribed=1`), unfinished movies
  (`watching/movies`). **Every** candidate is checked with `/v1/watching?id=` (no caps — a cap once
  dropped Peppa) and turned into a resume point by `resumeEntry`: first unfinished episode at/after
  the last touched one; for titles started explicitly here (`explicit=true`) a card appears even
  with zero progress. Sort key `lastActivityMs` = max(history last_seen, startedAt, playedAt),
  newest first. **kino.pub history is unreliable for recency** (it never listed Peppa), hence the
  local stamps; `playedAt` is refreshed on every progress save and cleared only by
  "Убрать из «Продолжить»".
- **Kids** = any genre starting with "мульт" or "детск" ("Семейный", "Аниме" deliberately excluded).
  Flags cached persistently; unknown titles classified in the background after Home renders.
- **Sport row** (`data/Sport.kt`, `AppContainer.sport`): kino.pub `GET /v1/tv` channels in a fixed
  lineup — Eurosport 1/2 → Okko Спорт/Прайм/Футбол/Чемпионат → Матч ТВ/Премьер/Арена/Игра/Футбол 1-3
  (`lineup`: kino.pub `name` → XMLTV id; Матч Премьер is `nashfootball`). Under each: what's on now
  from the free iptvx.one XMLTV "lite" guide (`epg_lite.xml.gz`, ~15 MB gz / ~170 MB XML, streamed
  with XmlPullParser, only our channels for −6 h…+36 h kept in `filesDir/epg.json`, refreshed when
  >12 h old or <3 h ahead). Card picture = a still frame: newest HLS segment, first 700 KB (Range),
  first keyframe decoded by MediaExtractor+MediaCodec → `filesDir/snaps/<name>-<ts>.jpg` (name
  changes so Coil reloads). Frames refresh every 5 min, per channel only when stale, and only while
  Home is composed **and the app is STARTED** (`repeatOnLifecycle`) — never during playback or with
  the TV off (Home stays composed in the background; it used to keep fetching). OK plays the live
  stream in `TrailerScreen`. **Live preview** (`ui/home/LivePreview.kt`): the focused sport card
  plays its channel muted in a TextureView after a 0.7 s dwell; one shared ExoPlayer, audio track
  type disabled, stopped on blur / app stop; the still frame stays until `onRenderedFirstFrame`. `SportCard` (`ui/common/Cards.kt`): channel name on the frame, the
  programme title in full under the card (up to 3 lines) + "до HH:mm" — the owner wants the whole title.
- **Wishlist** = bookmark folder `Wishlist` (also matches "Хочу посмотреть"), auto-created.
  "New" = not yet focused for 1.5 s on the TV. Removal repeats until `get-item-folders` confirms
  (kino.pub can store duplicates).
- **Every user action writes to kino.pub**: watched = `watching/toggle?status=1|0` (episode,
  season, all seasons); "Начал смотреть" = `togglewatchlist` (serials) / `marktime` + local stamp
  (movies — kino.pub ignores a 1-second marktime, so the local stamp is what makes it show);
  "Убрать из «Продолжить»" = `history/clear-for-item` + unsubscribe + clear local stamps.
- **Playback** (`PlayerController`, an AndroidViewModel owning ExoPlayer for a session that spans
  episodes): file = highest `h` ≤ `TrackChoice.maxHeight` (HEVC preferred at equal height), URL
  hls4 → hls → http, player pinned to the chosen quality (`setForceHighestSupportedBitrate`).
  **Voiceovers**: hls4 repeats every voice once per quality (GROUP-ID audio2160/1080/720/480), so
  ExoPlayer has one audio *TrackGroup per voiceover* with a copy per quality — an option is a
  group; overrides select the whole group; kino.pub `audios[]` metadata is matched by the "NN."
  prefix of the rendition NAME. Subtitles: sideloaded `SubtitleConfiguration`s, ordered
  remembered → RU → EN → rest. Picks are saved per title and re-applied on every episode.
- **Progress**: `marktime` every 30 s, on pause and on exit (via `AppContainer.appScope`);
  `toggle?status=1` at the end, then auto-advance (crossing seasons).
- **Player overlay levels** (rule: ↓ reveals the next level, ↑ hides the current one):
  NONE → ↑ FINE **mode** (top bar; ← → frame-step with EXACT seeks, pauses). Sticky: only ↓, OK (play/pause — returns to plain watching, no bar) or
  Back leave it; the bar hides after 3 s idle (`fineBar`) and returns on the next ← →/↑ ·
  NONE → ↓ OSD (seek bar with white position marker + stats line, **no hint text**; ← → scrub
  shows just the bar + target time — no preview frame, owner's call — commits ~0.9 s after
  key-up or on OK) → ↓ TRACKS panel (voiceover / subtitles / quality / next episode; ↑ from its
  top row → OSD). Focus must land in the panel at once: `TracksPanel` retries `requestFocus`
  until `hasFocus()` (it used to "succeed" while the panel was still expanding and cost an extra
  press). Panel open/close is ONE `expandVertically`/`shrinkVertically` — no outer
  `animateContentSize`, which lagged and made the bar "fall" after the panel vanished.
  NONE → ← → = **quick jump**: seeks at once, no seek bar, only a "+18 с · шаг 5 с" label
  (accumulated offset of the burst). Step grows by step count within a burst (presses < 1.5 s
  apart, or a hold throttled to one step per 220 ms): 3 s ×2 → 5 s ×2 → 10 s ×2 → 30 s ×2 →
  1 min ×2 → 5 min (`jumpStepMs`). Media ⏪/⏩ do the same.
  OK = pause/resume only: a big ❚❚ over a dimmed picture fades after 1.2 s into a small
  top-right badge. Pauses caused by frame stepping show **no** pause sign (it's a still frame
  you're studying) — tracked by `userPaused`; pause state comes from `playWhenReady`, not
  `isPlaying` (buffering after a seek isn't "playing"). OSD scrub steps grow with hold time
  10 s → 30 s → 1 min → 2 min → 5 min; fine 0.5 → 1 → 2 → 5 s. Overlays slide/fade
  (`AnimatedVisibility`). Back: panel → OSD, scrub → cancel, else exit.
- **Title card** (`DetailsScreen`): poster, facts, Russian plot (kino.pub) + English plot (IMDb) —
  the plot block is focusable, OK opens the full text; action row (play/resume, wishlist,
  "Начал смотреть"/"Смотрю ✓", watched); trailer chips (kino.pub `items/trailer` + IMDb trailers/
  teasers via `data/api/ImdbClient.kt`, unofficial GraphQL `caching.graphql.imdb.com` — imdb.com
  HTML is behind an anti-bot wall) played in `TrailerScreen`; season chips and episode rows.
  Long-press: episode → toggle watched; season chip → toggle the whole season.
- **Home** is compact: 92 dp two-column header (title | episode · time left · progress), 160 dp
  cards with one-line captions → ~3.5 rows visible. Cached on disk (`filesDir/home.json`) and shown
  instantly; fresh data replaces it. Back: first to the first card, then a second Back within
  2.5 s exits. Focus returns to the card you left (`HomeViewModel.lastFocusKey`).
- **Focus is the UI.** Every screen requests initial focus on its primary action; lazy rows compose
  late, so focus requests retry briefly.

## How to develop here

- Build: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:assembleRelease`. The **release**
  build (R8, signed with the debug key, ~5 MB) is what runs on the TV; debug Compose is far slower.
  Debug and release share the key, so they install over each other and keep the login.
- Deploy: `scripts/tv.sh deploy` (mDNS rediscovers the wireless-debugging port). Check
  `adb -s <tv> shell dumpsys audio | grep -c state:started` is 0 first — an install kills playback.
- Logs: `adb -s <tv> logcat -s denopub` (`continue:` lines explain the Continue rows;
  `bookmarks/remove-item` responses; HLS audio dump). Release is not debuggable — no `run-as`.
- Emulator: AVD `kino_tv` (android-34 android-tv arm64, 1920×1080), logged in to the owner's
  account. `adb shell input text` can't type Cyrillic. No tests yet.
- Add an endpoint: DTO in `data/api/Models.kt` (nullable/defaulted fields; Json ignores unknown
  keys) → method in `KinopubApi` → use from `Repository`. Update `docs/kinopub-api.md` if you
  verified something marked UNVERIFIED.

## Conventions

- Manual DI via `AppContainer`; no Hilt. Hand-written OkHttp client; no Retrofit.
- Design tokens in `ui/theme/Theme.kt` (`object K`); don't hardcode colors in screens.
- Russian counts via `plural(n, one, few, many)` (`ui/common/Cards.kt`) — "4 серии", not "4 серий".
- Russian UI strings inline (single-user app).
- App name **denopub**; package/applicationId stay `dev.dt.kinopub` (changing it = second app, lost login).

## Gotchas & known issues

- **A sleeping Chromecast has no network.** An install/launch while it slept made every Home call
  fail, Home saved that empty result over `home.json`, and after wake-up only the Sport row was
  left (focus stranded on «Поиск»). Now `Repository.home()` keeps a part's previous content when
  it fails *or comes back empty*, and Home reloads on every return to the foreground
  (`repeatOnLifecycle(STARTED)`, skipping the first start).
- **Never `runCatching` inside a loop tied to a composition**: it swallows `CancellationException`,
  so leaving Home kept `Sport.run()` spinning ("The coroutine scope left the composition" per
  channel). Use `Sport.attempt`, which rethrows cancellation.
- Sport frames older than 15 min are not shown (logo instead), and a failed grab drops the frame.
- Search hides kino.pub type `3D` items (owner: no 3D on the TV) — `Repository.search`.
- **Eurosport guide**: kino.pub carries the European feed, whose times match iptvx `eurosport1-pl`/
  `eurosport2-pl`. iptvx's Russian `eurosport1`/`eurosport2` list the same shows **shifted by a
  whole hour** (and with gaps), so they only lend Russian titles: `Sport.withTitlesFrom` finds the
  shift that lines up the most starts (self-calibrating across DST) and falls back to the Polish
  title. Verified against live frames 2026-10-19. `EpgCache.version` forces a re-download when
  the stored ids change.
- **Snapshot decoding**: software decoder preferred (`isSoftwareOnly`), so frames never touch the
  HW decoder. Android's TS `MediaExtractor` doesn't see the video of **interlaced** streams
  (Eurosport 2 is 1080i — it reported 1 track, audio): `Sport.videoPes` demuxes the H.264 PES
  itself as a fallback. `decode …: in= out=` log lines explain a "no frame".
- **Gradle**: Homebrew's `gradle` is 9.x and can't run `wrapper` in this project (AGP 8 uses an
  API removed in Gradle 9.6). Always `./gradlew` (8.14.3).
- **JDK**: use `openjdk@21` (keg-only). Don't add `jvmToolchain(17)` — no JDK 17 installed.
- `tv-material` chips are experimental → opt-in in `app/build.gradle.kts`.
- `watching/marktime` stores time but **does not change status** (stays -1): "in progress" =
  `time > 0 && status != 1` everywhere.
- Writes from `onCleared` must not use `viewModelScope` (cancelled) — use `AppContainer.appScope`.
- **Long-press opens dialogs while OK is still held**: `ActionMenu` swallows OK until a fresh
  key-down, or the release clicks the first item. Keep that guard.
- **`PlayerView` must be non-focusable** (`FOCUS_BLOCK_DESCENDANTS`) — it ate D-pad keys.
- `BasicTextField` swallows the D-pad — Search moves focus out on ↓ manually.
- An off-screen chip in a `LazyRow` isn't composed and can't take focus: rows scroll to the
  selected chip first. Chip rows need `contentPadding`, or the focus zoom is clipped.
- NavHost transitions are disabled: a SurfaceView can't fade (the player used to blink on Back).
- Quality labels go by **width** (3840×1920 is 4K).
- `items/fresh` vs `items/new` disagree between docs and clients — `fresh()` tries both.
- HDR marking in `files[]` is UNVERIFIED; we only prefer `codec == "h265"` at equal height.
- Client creds are the Kodi plugin's public ones (`xbmc`); if revoked, ask support@kino.pub.
- **Google TV integration dead ends** (verified 2026-10-17): Watch Next rows are accepted but not
  shown (allow-listed partners only); the Engage SDK says `isServiceAvailable=false` for a sideloaded
  app; there are no home-screen widgets. `system/WatchNext.kt` stays (harmless);
  `system/WakeService.kt` (auto-open on power-on) exists but the owner declined it — inert unless
  enabled in Accessibility settings.
- **The emulator and TV use the owner's real kino.pub account.** Never drive "mark watched" /
  "remove" flows blind with adb — screenshot before every OK (a blind sequence once marked a whole
  season watched).
- Splash: the Android TV image doesn't draw the system splash icon, so the branded splash is
  Compose (`ui/SplashOverlay.kt`) until `AppContainer.uiReady`, max 5 s. Brand sources in
  `docs/brand/`, prompts in `docs/brand-prompts.md`.

## What NOT to touch

- `gradle/wrapper/*` except via a deliberate wrapper upgrade.

## Secrets & data

- No secrets in the repo. OAuth tokens live only on the TV (DataStore `auth`).
- `local.properties`, `.tv-address` are gitignored and machine-local.

## Roadmap

1. Wishlist ingest: Telegram bot (phone → screenshot → vision model identifies the title →
   kino.pub search → `bookmarks/add` to `Wishlist`).
2. "Next episode in 10 s" card, skip-intro, voice search.
3. Recommendations from new releases + watch history.
4. Trim diagnostic logging once stable (see HANDOFF open items).

## Git / backup rule

Commit after every meaningful change, with docs updated in the same commit, and push to
`origin` = `github.com/deetan/kinopub-android-tv` (private, `main`).
