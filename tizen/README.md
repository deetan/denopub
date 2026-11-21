# Natapub — denopub for Samsung Tizen TVs

A web-app port of denopub for the owner's 2019 Samsung **UE43RU7402UXXH** (Tizen 5.0, Chromium 63
engine, 4K panel, app canvas 1920×1080). Samsung TVs can't run Android APKs, so this is a
separate TypeScript app, packaged as a Tizen `.wgt` and side-loaded over Wi-Fi. kino.pub stays
the source of truth, exactly as on Android.

| Folder | What |
|---|---|
| `spike/` | The playback spike (plain JS, no build): device-code login, AVPlay vs hls.js, audio/subtitle switching, logs to the Mac. Kept as a diagnostic tool. |
| `natapub/` | The app itself (Vite + TypeScript + Preact, built for Chrome 63). |

## Spike results (2026-11-21, on the TV)

- Packaged apps can call other origins: kino.watch and the movies service answer from the TV with
  no CORS headers needed (`<access origin="*">`). IMDb GraphQL answers **403** to the TV's
  request — IMDb calls must go through the movies service.
- **hls.js** (MSE) plays kino.pub `hls4`: 9 voiceovers listed and switched (`hls.audioTrack`),
  9 in-manifest subtitle tracks (`hls.subtitleTrack`), first frame in 2–4 s, seeks land on the
  segment keyframe (1:03 → 1:00). Non-fatal `bufferFullError` / `bufferStalledError` appear —
  keep the buffer modest. **Chosen engine** (also runs in desktop Chrome for development).
- **AVPlay** (`webapis.avplay`) opens the same stream in ~4 s and lists 9 AUDIO + 12 TEXT
  tracks; `setExternalSubtitlePath(url)` fails (`PLAYER_ERROR_INVALID_PARAMETER`, needs a local
  file). Track switching was not exercised. Fallback if hls.js ever falls short (e.g. 4K HEVC).

## One-time setup

### 1. TV: developer mode
1. Open **Apps** on the TV.
2. Type `12345`. The One Remote has no digits: press its **123** button for the on-screen pad.
3. Switch **Developer mode** on, set **Host PC IP** to the Mac's LAN address
   (`ipconfig getifaddr en0`), OK.
4. Power the TV fully off and on (hold power ~3 s).

Check from the Mac: `curl -s http://<tv>:8001/api/v2/ | grep -o '"developer[^,]*'` →
`developerMode":"1"` and your IP. The TV listens for `sdb` on port 26101. Developer mode
survives reboots; changing the Mac's IP means re-entering it.

### 2. Mac: Tizen CLI (no Samsung account)
```bash
curl -LO https://download.tizen.org/sdk/Installer/tizen-studio_6.1/web-cli_Tizen_Studio_6.1_macos-64.bin
bash web-cli_Tizen_Studio_6.1_macos-64.bin --accept-license ~/tizen-studio
```
~1 GB in `~/tizen-studio`, bundles its own JDK, runs under Rosetta on Apple Silicon. The
Samsung TV extension is **not** needed.

### 3. Mac: signing certificate (generic Tizen, no Samsung account)
```bash
T=~/tizen-studio/tools/ide/bin/tizen
PW=$(openssl rand -hex 12); mkdir -p ~/tizen-studio-data; (umask 077; echo "$PW" > ~/tizen-studio-data/denopub-author.pw)
$T certificate -a denopub -p "$PW" -c RS -n denopub -f denopub
$T security-profiles add -n denopub -a ~/tizen-studio-data/keystore/author/denopub.p12 -p "$PW"
```
The 2019 TV accepts the default Tizen distributor certificate in developer mode — no
Samsung-account certificate bound to the DUID was needed. Keys live in `~/tizen-studio-data`
only; `*.p12` and `*.wgt` are git-ignored (the repo is public).

**Updates must be signed with the same author certificate**, or the TV refuses to install over
the existing app (uninstall first). Back up `~/tizen-studio-data/keystore/author/denopub.p12` and
the `.pw` file.

### 4. Connect
```bash
~/tizen-studio/tools/sdb connect 192.168.0.10
~/tizen-studio/tools/sdb devices     # → 192.168.0.10:26101  device  UE43RU7402UXXH
```

## Deploy

- Spike: `tizen/spike/deploy.sh [tv-ip]` — package, sign, install, launch.
- Natapub: see `natapub/README.md`.

Logs: both apps POST log lines to `http://<mac>:8765/log` when a sink is listening:
`python3 tizen/tools/logsink.py /tmp/tv.log` then `tail -f /tmp/tv.log`. Without the sink the
posts fail silently.

## Gotchas

- `tizen package` names the `.wgt` after `<name>` (with spaces); the scripts rename it.
- Chromium 63: no `?.`, `??`, `Array.prototype.flat`, `Object.fromEntries`. The spike is
  hand-written ES2017; Natapub is compiled by Vite/esbuild to `chrome63`.
- The One Remote sends ArrowUp/Down/Left/Right (37–40), Enter (13) and Back (10009) without
  registration. Media and channel keys need `tizen.tvinputdevice.registerKey`.
- Tizen Studio is deprecated by Samsung but the 6.1 CLI still works for 2019 sets.
