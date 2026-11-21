# Natapub

denopub for the owner's Samsung TV: a Tizen web app that ports the Android TV app's Continue rows,
wishlist, search, title card and player, with the same look. kino.pub stays the source of truth.

**The interface is in English** (owner's call; denopub's is Russian). Every UI string, English
plurals (`plural(n, 'episode')` → "1 episode" / "4 episodes") and duration formats live in one
module, [`src/ui/strings.ts`](src/ui/strings.ts) — adjust copy there. kino.pub content stays as the
API gives it, except titles: kino.pub sends "Русское / English", and `displayTitle(raw)`
(`src/format.ts`) shows the part with Latin letters (Russian-only titles stay Russian; the other
part is `otherTitle`). Cards, hero, title card, Similar, player, episodes and the tracks panel all
go through it.

`status: phase 1, installed on the TV` · `TypeScript · Preact · hls.js · Vite` ·
`runs on: Samsung UE43RU7402UXXH (2019, Tizen 5.0, Chromium 63, armv7), side-loaded`

TV setup (developer mode, Tizen CLI, the no-Samsung-account certificate) is in
[`../README.md`](../README.md). Behaviour spec = the "Mental model" in the root `CLAUDE.md`.

## Architecture

```
src/
  main.tsx            boot: error hooks, zoom-to-fit, Tizen key registration, render <App/>
  log.ts              console + POST to the Mac's log sink (tizen/tools/logsink.py, :8765)
  platform.ts         key codes, exit, screensaver off, Tizen info (all try/catch for desktop)
  keys.ts             one global key dispatcher (handler stack), Press (OK click / long-press), Hold (← → repeat)
  format.ts           plural(), formatMinutes(), splitTitle(), clock()
  api/http.ts         XHR + throttle: ≤ 4 calls at once, background lane shares 2, 429 → 3/6/12 s cooldown + retry
  api/kinopub.ts      kino.watch API v1 + OAuth device flow, tokens in localStorage, single-flight refresh
  api/models.ts       response types (Models.kt)
  data/prefs.ts       LocalPrefs: TrackChoice, kids flags, startedAt/playedAt, wishlist seen, Home cache
  data/repo.ts        Repository.kt: home(), continueWatching(), resumeEntry, wishlist, user actions
  player/controller.ts PlayerController.kt on hls.js: file choice, tracks, TrackChoice, progress writes
  ui/App.tsx          screen stack (Home → title card → player); hidden screens stay mounted, take no keys
  ui/Auth.tsx         device-code login
  ui/Home.tsx         hero + Continue rows, wishlist, new films / serials, popular; long-press menus
  ui/Details.tsx      title card
  ui/Player.tsx       overlays (quick jump, OSD, fine mode, tracks panel)
  ui/common.tsx       cards, rows (lazy FixedRow, measured ChipRow), ActionMenu, toast, vertical scroll
  ui/app.css          tokens and shapes from Theme.kt / Cards.kt
  dev/fixture.ts      desktop-only fake kino.pub (never in the TV build)
public/               config.xml, icon.png (own icon, drawn by scripts/make-icon.py), hls.min.js (the spike's exact 1.5.20 build)
scripts/check-dist.mjs  fails the build on syntax/APIs/CSS newer than Chrome 63
```

- **Sizes are Android dp.** `#app` is laid out at 960×540 — Android TV's dp canvas — and
  CSS-`zoom`ed to the window (×2 on the TV's 1920×1080). So every number in the CSS/TSX is the
  number in the Kotlin code (160 dp continue cards, 150 dp posters, 48 dp side inset, 24 sp
  headlines). `zoom`, not `transform: scale`, so text stays sharp.
- **Focus is ours.** No DOM focus: each screen keeps "row + column" state and draws class `f`
  (3 dp accent border + 1.05/1.06 zoom, transform only). ↑ ↓ on Home pick the card nearest to the
  focused card's centre in the next row, like Compose's 2D focus search. Rows are translated with
  transforms, never native scrolling; long rows render only the cards near the visible window.
- **Keys.** One `document` listener → the newest active handler (a dialog swallows everything).
  OK clicks on release; held ≥ 600 ms it is a long-press. A menu opened by a long-press gets the
  release but its own `Press` never saw the press, so nothing runs (Android's held-OK guard).
  ← → holds repeat from timers (the TV never sent auto-repeat).
- **Playback**: hls.js (classic script, `window.Hls`). kino.pub's hls4 manifest lists every
  quality and every voiceover; the level matching the chosen file is pinned (no ABR), voiceovers
  are `hls.audioTrack`, subtitles `hls.subtitleTrack` (in-manifest WebVTT rendered by the video
  element). Voiceover labels come from kino.pub `audios[]`, matched by the "NN." prefix of the
  rendition name.

## Dev loop

```bash
cd tizen/natapub
npm install
npm run dev                # http://localhost:5173 — real kino.pub via the Vite proxy (/kw → kino.watch)
                           # http://localhost:5173/?fixture — fake data, in-memory writes, a public test stream
npm run build              # typecheck + build + Chrome 63 check → dist/
```

Keys on the desktop: arrows, Enter (hold for long-press), Escape/Backspace = Back, Space =
play/pause. In fixture mode `window.fixtureWrites` lists every write the app made.

## Deploy

```bash
python3 tizen/tools/logsink.py /tmp/tv.log &   # optional: see the TV's log lines
tizen/natapub/deploy.sh [tv-ip]                # build → package → sign (profile denopub) → install → launch
```

Package id `natapubTV1`, app id `natapubTV1.Natapub`. Updates must be signed with the same author
certificate. A fresh install has no token: the TV shows a device code (also logged as
`auth: device code …`) to enter at kino.pub/device. After login the app names the device
"Natapub (Samsung TV)" in the kino.pub cabinet and asks for hls4.

## What is ported

- Login (device code; refreshes single-flight; a rejected refresh returns to the login screen).
- Throttled API client (`KinopubApi.throttled`), HTML error pages → "kino.pub is not responding (HTTP n)".
- Home: compact hero, Continue Series / Movies / Kids with the same candidates, resume rule and
  recency (`startedAt` / `playedAt` in localStorage), kids rule and background classification,
  Wishlist with "NEW" (seen after 1.5 s of focus), New movies / series, Popular. Cached Home
  first; a failing or empty part keeps its old content. Back → first card, Back again within
  2.5 s exits. Focus stays on the card you left; Home reloads on every return and on
  `visibilitychange`.
- Long-press menus on Home (Continue and poster cards), writing to kino.pub.
- Search (`src/ui/Search.tsx`, Android `ui/search`): the Search button top-right of Home (↑ from
  the first row, ↓/Back returns to the cards). A plain `<input>`; DOM focus opens the Tizen
  on-screen keyboard. ↓, OK or the IME's Done (65376) leave the field for the results, Cancel
  (65385) closes the keyboard and stays on the field; OK on the field reopens it. All / Movies /
  Series chips; 5-column grid of poster cards; kino.pub type `3D` hidden. OK opens the title card;
  Back returns with the query and focus kept (the screen stays mounted under it). While the field
  has DOM focus, `keys.ts` passes typing keys (Backspace, ← →) to the input instead of the screens.
- Title card: poster, facts, genres, Russian plot (OK → full text), play/resume, wishlist,
  Started watching / Watching ✓, watched; seasons (long-press = whole season) and episodes
  (long-press = toggle watched); Similar from kino.pub `items/similar`; self-retry when kino.pub
  is down.
- Player: file = highest `h` ≤ remembered max (HEVC preferred at equal height); quick jump with the
  growing step table and the signed distance; OK pause sign fading to a corner badge; ↓ OSD with
  scrub (step grows with hold, commits 0.9 s after release or on OK) and stats; ↓ tracks panel
  (previous/next episode stills, long-press → all episodes; voiceover, subtitles, quality);
  ↑ fine mode (0.5 → 1 → 2 → 5 s steps, sticky, ↓/Back resume). TrackChoice per title (and as the
  default), re-applied on every episode. `marktime` every 30 s, on pause and on exit, serialized;
  watched at the end or when switching episodes in the last 5 % / last minute; auto-advance across
  seasons. TV screensaver off while the player is open.

## Not ported / differs from Android

- Out of scope: Sport row (and its live preview), recommendations, IMDb (rating, English plot,
  trailers, "More like this" — IMDb answers 403 to the TV), kino.pub trailers, skip intro/credits,
  paintings screensaver and weather, self-update (no Paintings button).
- Device settings: only hls4 + SSL are requested; **HEVC / 4K / HDR stay off** because only H.264
  ≤ 1080p was proven through hls.js on this TV. Turning them on is a kino.pub device setting plus a
  playback test.
- "Left in the credits" uses the last 5 % / last minute only (no intro/credits databases).
- No scrub preview frame (Android dropped it too) and no audio-track / CC remote shortcuts.
- Seasons → ↓ lands on the resume episode (Android lands on the nearest visible one).
- Tizen has no "Background" coroutine context: the background lane is an explicit flag.

## Gotchas

- Chromium 63 traps are enforced by `scripts/check-dist.mjs` (see `../README.md` Gotchas): never
  use flex `gap`, `aspect-ratio`, `inset`, `?.`/`??` in output, `Array.flat`, `Object.fromEntries`…
- Natapub has its **own icon** (amber "N", `scripts/make-icon.py`): the first build reused denopub's
  and the owner took the tile for denopub and removed it. After a reinstall, adding the tile to the
  TV's home row is a manual step on the TV.
- The bundle must stay a classic IIFE script; `vite.config.ts` rewrites the module script tag.
- `localStorage` holds everything local; the Home cache is slimmed (posters/titles only) to fit.
- A start position at or past the end of the file makes the video end at once and auto-advance
  (seen only with the fixture's short test stream; kino.pub positions are always inside the file).
- The fixture puts a fake token in the dev browser's localStorage; a later non-fixture run just
  fails the refresh and shows the login screen.
