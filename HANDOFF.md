# HANDOFF — denopub (as of 2026-10-18)

For the next agent picking this up. Read this, then `CLAUDE.md` (how the code works and the
gotchas), then `README.md`. Everything below is the state at commit time; trust the code if it
disagrees.

## What this is, in one paragraph

**denopub** is the owner's personal Android TV client for kino.pub, sideloaded onto a **Chromecast
with Google TV (Android 14)** plugged into a **Hisense U8Q running VIDAA**. One user, remote only.
The core job: switch on the TV and resume what you're watching in one click. It's built with
Kotlin, Compose for TV and Media3 ExoPlayer. The repo is `github.com/deetan/kinopub-android-tv`
(private, branch `main`).

## Current state: works, in daily use on the real TV

Verified on the real Chromecast by the owner:

- Login (OAuth device code), Home, title card, search and player.
- 4K HEVC HDR10 playback over hls4 (stats line shows `3840×1920 · HEVC HDR10`).
- **Voiceover switching works** ("Voice changes" — owner, 2026-10-17).
- **Home Continue** is three rows: Сериалы / Фильмы / Детям. Each is sorted by last activity, so the
  last-watched title comes first (owner confirmed with Peppa Pig).
- **"Начал смотреть"** puts a title into Continue, even with zero progress (fixed 2026-10-18 for the
  Spider-Man films).
- **Title card:**
  - Russian description plus the English one from IMDb; focus the description and press OK to read it in full.
  - IMDb and kino.pub trailers, with short labels.
  - Long-press an episode or season to toggle it watched.
- **Player levels:**
  - ↑ opens a fine-stepping bar at the top.
  - ↓ opens the seek bar, with a marker and a preview frame while scrubbing.
  - ↓ again opens the voiceover/subtitles/quality panel.
  - ↑ hides each level in turn.
  - Overlays animate in and out.
  - With no overlay, ← / → jump at once and show only "+N с" (steps 3 s → 5 s → 10 s → 30 s →
    1 min → 5 min over a burst/hold). Shipped 2026-10-18 at the owner's request; awaiting feedback.
  - OK pauses: a big ❚❚ fades into a small corner badge; frame stepping shows no pause sign.
- **Compact Home:** a two-column header and 160 dp cards, so 3.5 rows fit on screen.
- Long-press menus on every card, and wishlist add/remove. Every write goes to kino.pub.
- Brand art (icon, TV banner, splash) from the owner's Claude Design export, with sources in `docs/brand/`.

## Open items / next steps (most relevant first)

1. **Wishlist removal didn't stick** ("Brawn: The Impossible Formula 1 Story" came back after the
   owner removed it). It's fixed in `Repository.setWishlisted`: removal repeats until
   `bookmarks/get-item-folders` confirms, and every response is logged
   (`bookmarks/remove-item … ->`). **It's not confirmed yet**: the owner was asked to remove it once
   more. If it still comes back, check the logged responses. Fallbacks: call `remove-item` without
   `folder`, or use `toggle-item`.
1a. **Sport row (2026-10-18, new)**: Eurosport/Okko/Match from `/v1/tv` + now-playing (iptvx XMLTV)
   + still frames. Verified on the TV 2026-10-18: 13 channels, guide parse 13 s on the Chromecast,
   frames decode, live playback works. Check `adb logcat -s denopub | grep -E "sport|epg|snap|decode"`
   (EPG parse time on the Chromecast and whether MediaCodec gives a YUV image are the unknowns).
1b. Eurosport guide fixed 2026-10-19 (Polish times + Russian titles, see CLAUDE.md gotchas);
   Eurosport 2 frames fixed (interlaced → own TS demux). All 13 cards verified on the TV.
   Live muted preview in the focused card added 2026-10-19 — **not yet seen on the TV** (it was
   asleep); the player initialises without errors. Check it first next time.
2. **Player controls reworked 2026-10-18** (owner disliked scrubbing and the pause overlay):
   quick jump with a "+N с" label, progressive steps, fading pause sign, no pause sign while frame
   stepping. Deployed to the TV, not yet tested by the owner, who has more player feedback to
   dictate. Tuning knobs: `jumpStepMs`, the 220 ms hold throttle, the 1.2 s / 1.5 s fade delays.
3. **Diagnostic logging is still on** (tag `denopub`):
   - `continue: candidates`, `continue: dropped`, `continue: order` (with all sort keys) in `Repository.continueWatching`.
   - The HLS `#EXT-X-MEDIA` dump and audio-track list in `PlayerController`.
   - Remove or trim it once things are stable. It's useful while the owner is still reporting ordering issues.
4. **The Roadmap in `CLAUDE.md`:**
   - Telegram bot: send a screenshot from the phone to add a title to the wishlist. A proposal card was offered earlier; it was never started.
   - A "next episode in 10 s" card, skip intro, voice search.
   - Recommendations.
5. **Declined or dead ends, don't redo:**
   - Google TV's own "Continue watching" row (Watch Next and the Engage SDK both filter out
     sideloaded apps).
   - Home-screen widgets: Google TV has none.
   - A screensaver: offered and declined.
   - Opening denopub automatically when the TV turns on (`WakeService`): the owner chose not to.
     The code exists but stays inert unless it's enabled in Accessibility settings.

## How to resume work (exact steps)

```bash
cd /Users/dt/Projects/kinopub-android-tv
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:assembleRelease
```

Deploy to the TV:

```bash
scripts/tv.sh deploy
```

- `tv.sh` finds the wireless-debugging port over mDNS. If the adb server restarted, run
  `adb connect 192.168.1.106:<port>`; `adb mdns services` prints the port.
- **Before every install**, check that nothing is playing:
  `adb -s <tv> shell dumpsys audio | grep -c state:started` must print 0. Installing kills playback.
- If the TV is asleep (`dumpsys power | grep mWakefulness`), the install still works, but you can't
  check the screen. Use the emulator instead.
- Logs: `adb -s <tv> logcat -s denopub`. Screenshots: `adb -s <tv> exec-out screencap -p > x.png`.
- Emulator: AVD `kino_tv` (Android TV 14, 1080p), already logged in to the owner's account.
- **Both the TV and the emulator use the owner's real kino.pub account.** Never send OK or long-press
  through adb without looking at a screenshot first. A blind key sequence once marked a whole
  season watched.

The release build has `debuggable=false`, so `run-as` can't read DataStore. Diagnose through logs
instead.

## Owner's preferences (how to work with them)

- **The owner writes in Russian, often by voice, so expect dictation typos.** Reply in English
  unless they ask otherwise. All app UI text is Russian.
- **They test on the real TV right away and report in short messages, often with photos of the
  screen.** Deploy each fix straight to the TV and check it with a screenshot before saying it's done.
- **They value "functional over pretty":**
  - D-pad focus must always land somewhere sensible.
  - One press per action.
  - No scrolling inside lists.
  - Clear on-screen hints for the controls.
- **Commit and push after every meaningful change, with the docs updated in the same commit.**
  The owner asks for "закоммить" regularly.
- **Ask before any action that changes the owner's kino.pub state outside what they asked for.**

## Hardware and setup facts

- **Chromecast "Living Room TV":** Android 14, IP 192.168.1.106 on Wi-Fi `wifi_deetan-5G`.
  Wireless debugging is already paired with this Mac.
- **Hisense U8Q, VIDAA:**
  - Default Startup Page = **Last Accessed Source**, so turning the TV on lands on the dongle (HDMI 1).
  - CEC is on at both ends. The Hisense remote controls the dongle over CEC.
  - The TV does not wake the dongle by itself; it relies on Last Accessed Source.
- **kino.pub account:**
  - The device is registered as "denopub (Chromecast)", using the Kodi public client creds `xbmc`.
  - The wishlist is the bookmark folder `Wishlist`.

## Decision log (condensed, newest last)

- **2026-10-16:**
  - The stack, the device-code auth, and the wishlist as a kino.pub bookmark folder.
  - Wireframes approved.
  - Renamed to denopub; the applicationId stays `dev.dt.kinopub`.
  - The R8 release build is the build that runs on the TV.
  - Long-press actions, sync with kino.pub, and search.
- **2026-10-17:**
  - The TV is VIDAA, not Google TV, so the dongle stays.
  - Option 2 chosen: stay an app (no launcher replacement); auto-open on power-on rejected.
  - The Google TV row and the Engage SDK were tried and removed.
  - Voiceover root cause found: hls4 repeats each voice per quality.
  - The player overlay level model added.
  - Continue split into three rows, sorted by recency.
  - IMDb description and trailers added.
- **2026-10-18:**
  - kino.pub history is unreliable as a recency signal, so a local `playedAt` stamp was added.
  - "Начал смотреть" shows titles in Continue even without progress.
  - Long-press toggles episode/season watched.
  - The full description opens with OK.
  - Wishlist removal is retried until it's confirmed.
