# Build **lenapub** — a personal Android TV client for kino.pub — and put it on a Xiaomi TV stick

You are Claude Code running on a Mac that may have **nothing** installed (no Homebrew, no Java, no
Android SDK). The person at the keyboard is not necessarily a developer. Your job, end to end:

1. Set up this Mac for Android development.
2. Walk the person through putting their TV stick into developer mode and connecting it to the Mac.
3. Write a **new** Android TV app called **lenapub** from the spec below (new code, new project —
   there is no source to copy; this file and the files next to it are all you get).
4. Build it, install it on the stick, log in to kino.pub, verify every feature on the real TV.
5. Leave behind a documented project and a one-command way to update the app.

The app is a functional clone of **denopub**, a client the author built and uses daily on a
Chromecast with Google TV. lenapub must **behave and look the same** (layout, colours, sizes,
focus behaviour, remote-control mapping, Russian copy), only the name and logo differ. Every number
in this spec (sizes, delays, steps) is a tuned value from daily use: keep it unless the device
forces you to change it, and if you change one, say so in `HANDOFF.md`.

## 0. Files in this kit

| Path | What it is |
|---|---|
| `PROMPT.md` | this spec — the source of truth |
| `kinopub-api.md` | kino.pub API v1 reference with **verified** vs **UNVERIFIED** notes. Trust it over your memory. Copy it into the project as `docs/kinopub-api.md`. |
| `assets/res/` | ready Android resources: launcher icons (`mipmap-*`), adaptive icon XML, TV banner (`drawable-xhdpi/banner.png` 640×360, `drawable-xxhdpi/banner.png` 960×540), full-screen splash (`drawable-nodpi/splash_screen.jpg` 1920×1080), system-splash vector (`drawable/splash_mark.xml`). Copy into `app/src/main/res/`. |
| `assets/brand/` | sources: mark SVG/PNG, 1024 icon, 1280×720 banner, splash, `render_brand.py` (PIL script that produced them). Copy into `docs/brand/`. |
| `reference/` | **screenshots of the real denopub** (1920×1080, from its emulator) + its splash and banner — the look to match pixel-for-pixel apart from the logo: `home.png` (hero + Continue rows, first card focused), `home-wishlist.png` (wishlist row, "НОВОЕ" badges, focused poster), `longpress-menu.png`, `details-movie.png`, `details-serial.png` (trailer chips, seasons, episode list), `player-osd.png` (OSD with stats line), `player-tracks.png` (tracks panel, focus on the current voiceover), `player-quickjump.png` (quick-jump label), `player-paused-quickjump.png` (pause badge + label). Open them before you write UI code and compare your screenshots against them at every UI checkpoint. |

## 1. Ground rules

- **Talk to the person in the language they write to you.** All app UI text is **Russian** (exact
  strings below). Code, comments, docs, commit messages: English.
- **The person may not be technical.** Every TV step: say exactly which menu, which item, what they
  should see. One step at a time; wait for "done" before the next. Every question must make sense on
  its own: what it is, why you ask, what each answer changes, and which one you recommend.
- **You can see the TV yourself** once adb works: `adb exec-out screencap -p > shot.png`, then look at
  the image. Do that after every install and before declaring anything done. Don't ask the person
  "does it look right?" when you can check.
- **The kino.pub account is real.** Never send OK / long-press key events through adb without a fresh
  screenshot showing what is focused. A blind key sequence once marked a whole season as watched.
  D-pad arrows and Back are safe to send blind; OK / Enter is not. Never trigger "mark watched",
  "remove", wishlist changes etc. on the real account except to test them on a title the person
  agrees to use for testing, and undo them afterwards.
- Before every install over adb check nothing is playing (`adb shell dumpsys audio | grep -c state:started`
  must print 0) — installing kills playback.
- No Hilt, no Retrofit, no Room. Manual DI, hand-written OkHttp client, DataStore. Keep it small.
- **Functional over pretty**: D-pad focus always lands somewhere sensible, one press per action, no
  scrolling inside lists where avoidable, focus is unmistakable from 3 m away.
- Commit to git after every working step (local repo; ask whether they want a private GitHub backup).

## 2. Plan and checkpoints

Work in this order. Don't start writing app code before Phase B works — you need the real device's
Android version and capabilities to make several decisions.

| Phase | Done when |
|---|---|
| A. Mac setup | `java -version` says 21, `adb version` works, `sdkmanager` installed platform 36 + build-tools |
| B. TV setup | `adb devices` lists the stick as `device`; you know model, Android version (API level), RAM, screen size, HEVC/4K/HDR support |
| C. Skeleton | an empty Compose-for-TV app named lenapub with the brand icon/banner/splash builds as an R8 release APK, installs, launches, appears in the TV launcher |
| D. Login + Home | device-code login works; Home shows Continue rows, wishlist, new releases; screenshots match the spec |
| E. Title card, search, trailers | all actions work; screenshot-checked |
| F. Player | playback, voiceovers, subtitles, quality, overlays, progress sync — checked on the TV |
| G. Sport row | channels, guide, still frames, muted live preview — checked (or consciously degraded, see §11) |
| H. Hand-over | README, CLAUDE.md, HANDOFF.md, `scripts/tv.sh`; the person can update the app with one command |

---

## 3. Phase A — set up the Mac

Detect first: `uname -m` (arm64 = Apple Silicon → Homebrew prefix `/opt/homebrew`; x86_64 → `/usr/local`),
`sw_vers`, `which brew java adb`. Install only what's missing.

1. **Homebrew** needs the person's Mac password, which you can't type. Ask them to paste this into
   the Terminal app themselves and follow its prompts (it asks for the password and to press Return):
   `/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"`
   Then make sure `brew` is on PATH (the installer prints the `eval "$(…/brew shellenv)"` line to add
   to `~/.zprofile`).
2. `brew install openjdk@21` (keg-only: always use `JAVA_HOME=$(brew --prefix)/opt/openjdk@21`).
   Don't add `jvmToolchain(17)`; compile with JDK 21 targeting Java 17 bytecode.
3. `brew install --cask android-commandlinetools android-platform-tools`
4. `yes | sdkmanager --sdk_root=$(brew --prefix)/share/android-commandlinetools --licenses` then
   `sdkmanager --sdk_root=… "platforms;android-36" "build-tools;36.0.0" "platform-tools"`.
5. `local.properties` (gitignored): `sdk.dir=<brew prefix>/share/android-commandlinetools`.
6. **Gradle wrapper gotcha:** Homebrew's `gradle` is 9.x and cannot run `wrapper` inside an AGP 8
   project. Generate the wrapper in an **empty temp dir**: `brew install gradle`, then
   `cd $(mktemp -d) && gradle wrapper --gradle-version 8.14.3`, copy `gradlew`, `gradlew.bat`,
   `gradle/wrapper/*` into the project. From then on only `./gradlew`.
7. Optional: an Android TV emulator for safe UI testing (`system-images;android-34;android-tv;arm64-v8a`
   on Apple Silicon, ~1 GB). Not required — the real stick is the test device.

## 4. Phase B — the TV stick (walk the person through it)

The target is "a Xiaomi stick/box" — the exact model is unknown. Likely: **Xiaomi TV Stick 4K**
(Android TV 11), **Mi TV Stick** 1080p (Android TV 9), **Mi Box S** gen 1 (Android TV 9) or gen 2
(Google TV, Android 12). Menus differ slightly; adapt the wording to what they report.

1. **Find the model and version.** Android TV: Settings (gear, top right) → Device Preferences →
   About. Google TV: Settings → System → About. Ask them to read out *Model* and *Android TV OS version*.
2. **Enable developer mode.** In that About screen, move to **Build** (Android TV) / **Android TV OS
   build** (Google TV) and press OK **7 times**; a toast says "You are now a developer".
3. **Enable debugging.** Back → Developer options (Android TV: Device Preferences → Developer options;
   Google TV: System → Developer options) → turn on **USB debugging**. If there is **Network
   debugging** or **Wireless debugging**, turn it on too.
4. **Find the IP.** Settings → Network & Internet → their Wi-Fi network → IP address. The Mac must be
   on the same Wi-Fi.
5. **Connect.**
   - Android 9–11 (no "Wireless debugging" entry): `adb connect <ip>:5555`. The TV shows "Allow USB
     debugging?" → tell them to tick **Always allow from this computer** and press OK. Re-run
     `adb devices` until it says `device` (not `unauthorized`).
   - Wireless debugging (Android 11+/Google TV): Developer options → Wireless debugging → **Pair
     device with pairing code**. The TV shows `ip:port` + a 6-digit code: `adb pair <ip:pairport> <code>`.
     Then `adb mdns services` shows the connect port (`_adb-tls-connect`), `adb connect <ip:port>`.
     The port changes after every reboot — your deploy script must rediscover it via `adb mdns services`.
   - If `adb connect` is refused on 5555 and there's no Wireless debugging: some Xiaomi firmwares only
     open 5555 after a reboot with USB debugging on — ask them to restart the stick (Settings → Device
     Preferences → About → Restart) and retry.
   - Last-resort fallback if adb never works: install via the "Send files to TV" app (phone → TV) +
     a file manager, with "Unknown sources" allowed for it. Tell the person this means every update is
     manual.
6. **Record the device facts** (you'll need them; put them in HANDOFF.md):
   `adb shell getprop ro.product.model`, `ro.build.version.release`, `ro.build.version.sdk`,
   `adb shell cat /proc/meminfo | head -1`, `adb shell wm size`,
   `adb shell dumpsys display | grep -iE "hdr|supportedHdrTypes|mode"`,
   `adb shell dumpsys media.player | grep -iE "hevc|avc|dolby|vp9" | head` (or check
   `MediaCodecList` in-app later). Also: does the remote have a Search / Assistant button,
   ⏪ ⏩ keys, an audio-track key?
7. Tell the person: when the stick is asleep it has no network — wake it (press any remote key) before
   each deploy. CEC: if their TV turns the stick on and off with the TV remote, fine; nothing to set.

## 5. Phase C — project and stack

- Project dir: `~/Projects/lenapub` (ask if they prefer another place). `git init`.
- App name **lenapub**. `applicationId`/namespace: **`dev.lena.lenapub`** — choose once, never change
  (changing it installs a second app and loses the login).
- **minSdk = the stick's API level or lower** (Android 9 = 28; denopub used 31 for the Chromecast).
  Use 28 unless you have a reason. targetSdk/compileSdk 36. Landscape only.
- Versions known to build together (Gradle 8.14.3):

  | lib | version | lib | version |
  |---|---|---|---|
  | AGP | 8.13.0 | Kotlin (+compose, serialization plugins) | 2.2.20 |
  | Compose BOM | 2025.09.01 | androidx.tv:tv-material | 1.0.1 |
  | activity-compose | 1.11.0 | lifecycle-runtime-compose / viewmodel-compose | 2.9.4 |
  | navigation-compose | 2.9.5 | media3 (exoplayer, exoplayer-hls, ui, datasource-okhttp) | 1.8.0 |
  | okhttp (+ logging-interceptor) | 4.12.0 | kotlinx-serialization-json | 1.9.0 |
  | kotlinx-coroutines-android | 1.10.2 | coil3 (coil-compose, coil-network-okhttp) | 3.3.0 |
  | datastore-preferences | 1.1.7 | core-ktx / core-splashscreen | 1.17.0 / 1.0.1 |

- `tv-material` chips/cards are experimental: add compiler opt-in `androidx.tv.material3.ExperimentalTvMaterial3Api`.
- **Release build is what runs on the TV**: `isMinifyEnabled = true`, `isShrinkResources = true`,
  signed with the **debug** key (`signingConfig = signingConfigs.getByName("debug")`). Debug Compose
  is several times slower on a TV stick. Debug and release then share a key and install over each
  other. R8 rules: keep kotlinx-serialization serializers of your DTO packages
  (`-keepclassmembers class <pkg>.data.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }`,
  `-keep,includedescriptorclasses class <pkg>.data.**$$serializer { *; }`, `-dontwarn org.slf4j.**`).
- `gradle.properties`: `org.gradle.jvmargs=-Xmx4g`, caching + configuration cache on, `android.useAndroidX=true`.
- Manifest: `INTERNET`; `uses-feature android.software.leanback required=true`, touchscreen
  `required=false`; `android:banner=@drawable/banner`, `android:icon=@mipmap/ic_launcher`;
  one `MainActivity`, `exported`, `launchMode=singleTask`, `screenOrientation=landscape`, intent filter
  `MAIN` + `LEANBACK_LAUNCHER` + `LAUNCHER`; `configChanges` covering keyboard/navigation/screenSize/uiMode;
  `allowBackup=false`.
- Themes: app theme `android:Theme.Material.NoActionBar`, window background `#0E0F12`; launch theme
  `Theme.SplashScreen` with background `#0E0F12`, animated icon `@drawable/splash_mark`,
  `postSplashScreenTheme` = app theme. `installSplashScreen()` in `onCreate`.
- `@color/brand_bg = #FF0E0F12` (used by the adaptive icon).
- Logging tag: `lenapub`.

Suggested layout (mirrors denopub; adapt freely):

```
app/src/main/java/dev/lena/lenapub/
  LenapubApp.kt         Application + AppContainer (manual DI: OkHttp, Coil image loader on the same client)
  MainActivity.kt
  data/api/             KinopubApi (OkHttp), Models.kt (DTOs), ImdbClient
  data/auth/            TokenStore (DataStore "auth"), DeviceRegistration
  data/prefs/           LocalPrefs (DataStore "local")
  data/Repository.kt    home assembly, resume logic, wishlist, actions
  data/Sport.kt         live channels + XMLTV guide + still frames
  ui/theme/Theme.kt     tokens (object K) + MaterialTheme (tv-material darkColorScheme)
  ui/common/            cards, ActionMenu, plural(), splitTitle()
  ui/Navigation.kt, ui/SplashOverlay.kt
  ui/auth, ui/home, ui/details, ui/search, ui/player
scripts/tv.sh
docs/kinopub-api.md, docs/brand/
```

`AppContainer` holds: `appScope` (SupervisorJob + IO, for writes that must outlive a screen),
OkHttp (connect 15 s, read 30 s; HTTP logging BASIC in debug only), TokenStore, KinopubApi,
LocalPrefs, Repository, DeviceRegistration, ImdbClient, Sport, and `uiReady: MutableStateFlow<Boolean>`
(splash stays until the first screen has content).

---

## 6. Look and feel

**10-foot UI, dark only, D-pad only.** Design for 1920×1080 (Compose dp ≈ 960×540 on most sticks — test
on the real device). Exactly one focused element; focus = 3 dp amber border + scale.

### Design tokens (`object K`) — use these, never hardcode colours in screens

| Token | Value | Role |
|---|---|---|
| `bg` | `#0E0F12` | background |
| `surface` | `#1A1C21` | cards, panels, dialogs |
| `surfaceHi` | `#262931` | raised / selected-not-focused, toasts |
| `accent` | `#F2B33D` | focus border, primary action, progress, highlights |
| `onAccent` | `#14161A` | text on accent |
| `text` | `#EDEEF0` | primary text |
| `muted` | `#9AA0A6` | secondary text |
| `badge` | `#E5484D` | "new" badges, errors |
| `screenPadH` / `screenPadV` | 48 dp / 27 dp | overscan-safe insets |
| `rowGap` / `cardGap` | 28 dp / 16 dp | between rows / between cards |

Typography (tv-material `Typography`): displaySmall 36 sp SemiBold · headlineSmall 24 sp SemiBold ·
titleMedium 18 sp Medium · bodyMedium 15 sp · labelMedium 13 sp Medium. Colour scheme:
`darkColorScheme(primary=accent, onPrimary=onAccent, background=bg, surface=surface, onSurface=text,
surfaceVariant=surfaceHi, onSurfaceVariant=muted, border=surfaceHi, borderVariant=surfaceHi)`.

### Components

- **Card shape** rounded 8 dp; focused border `3 dp accent`; tv-material `Card` with `onClick` +
  `onLongClick`.
- **Badge**: pill, rounded 4 dp, `badge` red background, white labelMedium text, padding 6×2 dp.
- **ProgressLine**: full-width track `white @ 18 %`, fill `accent`, default height 4 dp.
- **PosterCard** (2:3): width 150 dp, focused scale 1.06; image `ContentScale.Crop` on `surface`;
  optional badge top-end (6 dp inset); under it title (bodyMedium, `text`, 1 line, ellipsis, 8 dp top
  padding) and optional subtitle (labelMedium, `muted`, 1 line).
- **ContinueCard** (16:9): width 160 dp on Home, focused scale 1.05; image (wide poster) cropped;
  vertical gradient transparent at 35 % → black 92 % at the bottom; optional badge top-end (8 dp);
  bottom-start (padding 8×6 dp): title (bodyMedium, 1 line) and `"S2·E5 · ещё 23 мин"` (labelMedium,
  `muted`, 1 line); ProgressLine along the bottom edge.
- **SportCard** (16:9): width 220 dp, focused scale 1.05; still frame (or channel logo) cropped; slot
  for a live TextureView over it; gradient transparent at 55 % → black 90 %; channel title bottom-start;
  ProgressLine = programme progress. Under the card: programme title in **full** (labelMedium,
  `text`, up to 3 lines, 6 dp top padding) and `"до HH:mm"` (labelSmall, `muted`). The owner wants the
  whole programme title — don't truncate to one line.
- **Section header**: row title headlineSmall `text`, optional Badge 12 dp to the right; 12 dp above
  the row; rows are `LazyRow` with `contentPadding(horizontal = 48 dp)`, spacing 16 dp.
- **ActionMenu** (long-press menu): `Dialog`, column 520 dp wide, rounded 12 dp, `surface`, padding
  16 dp; title (titleMedium, 2 lines max); tv-material `ListItem`s (headline + optional supporting
  hint), focused container `accent` / content `onAccent`; first item focused (retry `requestFocus` up
  to 10× every 30 ms). **It opens while OK is still held**: swallow OK key events until a *fresh*
  key-down with `repeatCount == 0` arrives, otherwise releasing the button runs the first action.
- **Toast-like message**: bottom-centre, 24 dp from the bottom, `surfaceHi` rounded 8 dp, padding
  16×10 dp, bodyMedium `text`, auto-hides after 2.5 s. Every action reports success
  ("Отмечено просмотренным") or `"Не получилось: <message>"`.
- **Buttons/chips**: tv-material `Button` (primary) / `OutlinedButton` (secondary) with their **default
  colours** — focused = near-white fill with dark text (see `reference/details-*.png`), an unfocused
  `Button` is a dark filled pill (the selected season chip), `OutlinedButton` is a thin outline;
  `FilterChip` colours: container transparent, content `muted`; selected `surfaceHi`/`text`; focused
  and focused-selected `accent`/`onAccent`.
- **Russian plurals** everywhere: `plural(n, one, few, many)` (n%100 in 11..14 → many; n%10==1 → one;
  2..4 → few; else many): "4 серии", "5 серий", "1 новая".
- **Titles**: kino.pub titles are `"Русское / Original"`. `splitTitle` → (ru, original?). Cards show
  the Russian part.
- `formatMinutes(sec)`: `"47 мин"` or `"1 ч 12 мин"`.
- **Focus is the UI.** Every screen requests initial focus on its primary element; lazy rows compose
  late, so focus requests retry (e.g. 20× every 50 ms). An off-screen item in a LazyRow isn't composed
  and can't take focus: scroll the row to it first. Chip/button rows need `contentPadding`, or the
  focus zoom is clipped.
- **Navigation**: Compose `NavHost` with **all transitions disabled** (`EnterTransition.None` etc.):
  a SurfaceView video can't fade and the player blinked on Back.

### Splash
System splash (mark on `#0E0F12`) bridges process start; Android TV images often don't draw its icon,
so the real branded splash is a Compose overlay: full-screen `splash_screen.jpg` (ContentScale.Crop on
`bg`), visible until `uiReady` (Home has data, or the login code is shown) or 5 s max, fades out in
250 ms. Launch then goes logo → Home with focus already on the first card.

---

## 7. Data layer — kino.pub (read `kinopub-api.md` first)

**kino.pub is the source of truth** for progress, watched status and the wishlist. Every user action
writes to kino.pub immediately so the website and official apps see the same state. The TV keeps only
what the API has no slot for (`LocalPrefs`).

### Client
- Base `https://api.service-kp.com/v1/`, OAuth `https://api.service-kp.com/oauth2/device` and
  `/oauth2/token`. Client credentials: the Kodi plugin's public ones, `client_id=xbmc`,
  `client_secret=cgg3gtifu46urtfp2zp1nqtba0k2ezxh` (if revoked, the person must ask support@kino.pub).
- Json: `ignoreUnknownKeys`, `coerceInputValues`, `explicitNulls=false`; DTO fields nullable/defaulted.
- Every `/v1` call: `Authorization: Bearer <access>`; before it, refresh if the token expires within
  60 s (behind a Mutex; on refresh failure clear tokens → login screen). 401 → NotAuthorized.
  Always store the returned refresh token (it rotates).
- POST bodies are form-urlencoded. Many "watching" mutations are GET.

### Auth screen (device code)
Loop: `POST oauth2/device grant_type=device_code` → show `user_code`; poll `grant_type=device_token&code=`
every `interval` s until success (`authorization_pending`/`slow_down` = keep polling); on expiry fetch a
new code; on error show it in `badge` red and retry after 5 s. Layout, centred column, 20 dp gaps:
"Войти в kino.pub" (headlineSmall) · "Откройте kino.pub/device и введите код" (titleMedium, `muted`) ·
the code at **72 sp, letter-spacing 12 sp, accent** (placeholder `······`). Set `uiReady` once the code
shows. After login → `DeviceRegistration.afterLogin()` → Home (pop auth).

### DeviceRegistration (after login)
1. `POST device/notify` title `"lenapub (<Build.MODEL>)"`, hardware `"<MANUFACTURER> <MODEL>"`,
   software `"Android <release> / lenapub <versionName>"`.
2. `GET device/info` → id; `GET references/streaming-type` → id of `code == "hls4"` (never hardcode).
3. `POST device/{id}/settings` with `supportSsl=1`, `mixedPlaylist=1`, `streamingType=<hls4 id>`, and
   **capability-dependent** flags (denopub sent all 1s; a Xiaomi stick may not support them):
   `supportHevc` = a HEVC decoder exists (MediaCodecList); `support4k` = HEVC/AVC decoder supports
   3840×2160 **and** the display mode list has a ≥ 3840 wide mode; `supportHdr` = display reports HDR10
   (`Display.hdrCapabilities` / `Display.Mode.supportedHdrTypes`). Log what you sent. Without these
   settings kino.pub serves only H.264 1080p; with flags the device can't decode, playback fails.

### LocalPrefs (DataStore "local")
- `TrackChoice` per title (`tracks_<id>`, JSON) **and** as a global default (`tracks_default`):
  `audioLang`, `audioAuthor` (studio, e.g. "LostFilm" — strongest signal), `audioType`,
  `subtitleLang` (null = off), `maxHeight` (quality cap, null = best).
- Kids/adult classification: two string sets `kids_ids`, `adult_ids` (genres never change).
- `started_at`: set of `"id:epochMs"` — "Начал смотреть" pressed here.
- `played_at`: set of `"id:epochMs"` — last playback here; refreshed on every progress save;
  cleared only by "Убрать из «Продолжить»".
- `wishlist_seen`: item ids already seen on this TV.

### Home data (`Repository.home()`)
Five parts loaded in parallel: continue (below), wishlist, `items/fresh?type=movie`,
`items/fresh?type=serial`, `items/hot?type=movie`. (`fresh` vs `new` disagree between docs and clients:
try `items/fresh`, fall back to `items/new`.) **A part that fails *or comes back empty* keeps its
previous content** from the on-disk cache (`filesDir/home.json`, kotlinx JSON of the whole HomeData),
then the result is saved back. Reason: a sleeping stick has no network; an install/launch while asleep
once wiped Home and saved the empty result over the cache.

Home is rendered **instantly from `home.json`**, fresh data replaces it.

### Continue watching (the core feature)
Three rows: **Сериалы** (serials, not kids), **Фильмы** (movies, not kids), **Детям** (kids).
Each newest first. Algorithm:

1. In parallel: `history` pages 1 and 2 (`perpage=50`; one binge fills a page), `watching/serials`
   with `subscribed=1` **and** `subscribed=0` (two disjoint lists; mark the first as subscribed),
   `watching/movies`.
2. Recency per title = max(history `last_seen`×1000 (max over entries), `started_at` (only if < 30
   days old), `played_at`). **kino.pub history is unreliable for recency** (it never listed one kids'
   serial at all), hence the local stamps.
3. Candidates (ordered, dedup by id): titles in `played_at` + `started_at` (from the watching lists,
   else `GET items/{id}`), then history items, then subscribed serials, then all unfinished movies.
   **No cap that can drop candidates** (a cap of 30 once dropped a kids' serial); limit 80 is fine.
4. For **every** candidate, in parallel: `GET watching?id=` → resume entry:
   - Serial (has seasons): flatten seasons/episodes sorted by number. `lastTouched` = last index with
     `status == 1 || time > 0` (**marktime stores time without changing status**, so "in progress" =
     `time > 0 && status != 1` everywhere). If none touched and not explicit (not in started/played)
     and no new episodes → drop. Target = first episode with `status != 1` at/after `lastTouched`
     (none → drop: fully watched). Start = that episode's `time`. Remaining = count of `status != 1`.
     New episodes = `new` from watching/serials.
   - Movie: first part with `status != 1 && (status == 0 || time > 0)`; if explicit and none, first
     part with `status != 1`; else drop.
   - Poster: `posters.wide ?: big ?: medium`.
5. Sort by recency descending (stable). Kids flag from the persistent cache only; Home never waits
   on `/items` for genres. After Home renders, classify unknown ids in the background
   (`GET items/{id}`, kids = any genre title starting with **"мульт"** or **"детск"**, case-insensitive;
   "Семейный" and "Аниме" deliberately excluded), save flags, move kids' titles to the Детям row.
6. Log one line per candidate/drop/order with tag `lenapub` — the owner's "why is X missing/misplaced"
   questions are answered from these logs.

### Wishlist
A bookmark folder titled `Wishlist` (also match existing folders named "wishlist", "хочу посмотреть",
"посмотреть", trimmed, case-insensitive); auto-create it if missing. Items in API order (newest first).
`isNew` = id not in `wishlist_seen`. A card counts as seen after it holds focus for **1.5 s**
(cancel on blur); the badge stays until the next refresh so the row doesn't jump under the cursor.
Add: only if `get-item-folders` doesn't already list the folder. **Remove: repeat `remove-item`
(item + folder) up to 4× until `get-item-folders` no longer lists it** — kino.pub can store duplicates;
removal "didn't stick" before this. Log each response.

### Actions (all write to kino.pub, then show a message and reload)
- Watched episode/part: `watching/toggle?id&season&video&status=1|0` (`status` makes it idempotent).
  Season: omit `video`. Whole title: every season (serial) / every part (movie).
- "Начал смотреть": stamp `started_at`; serial → `watching/togglewatchlist?id=` only if current
  `in_watchlist` differs (it **toggles** — never retry blindly); movie → `marktime` 1 s on the first part
  (kino.pub ignores a 1-second marktime, so the local stamp is what makes it show).
- "Убрать из «Продолжить»": clear `started_at` and `played_at`, `POST history/clear-for-item?id=`,
  and for a subscribed serial toggle the watchlist off.
- Progress: `watching/marktime?id&video=<number>&time=<sec>&season=<n>` (season mandatory for serials).

### Search
`items/search?q=&perpage=30`; drop type `3D` (no 3D on the TV); filter Всё / Фильмы / Сериалы
(serial types = serial, docuserial, tvshow).

### IMDb (title card extras)
`POST https://caching.graphql.imdb.com/` with header `x-imdb-client-name: imdb-web-next`, JSON
`{"query": "{ title(id: \"tt0123456\") { plot { plotText { plainText } } ratingsSummary { aggregateRating } primaryVideos(first: 10) { edges { node { id name { value } runtime { value } contentType { displayName { value } } thumbnail { url } playbackURLs { url videoMimeType videoDefinition } } } } } }"}`.
`tt` id = kino.pub numeric `imdb` padded to 7 digits. Keep only videos whose type or name contains
Trailer/Teaser; URL preference DEF_1080p → DEF_AUTO → DEF_720p → first. Cache per id in memory.
Unofficial (imdb.com HTML is behind an anti-bot wall; this endpoint isn't) — failures are silent.
kino.pub's own trailer: `GET items/trailer?id=` — response may be an object or an array; take
`trailer.files[]` with the highest numeric `quality`, else `trailer.url`.

---

## 8. Screens

### Home
Vertical `LazyColumn` (bottom padding 27 dp, 28 dp between rows), top to bottom:

1. **Continue block** (only if any entries): a **92 dp hero** for the focused continue card, then the
   three Continue rows, 6 dp spacing.
   - Hero: the focused title's wide poster cropped as background, horizontal gradient `bg` 100 % at 0 →
     `bg` 85 % at 0.6 → `bg` 35 % at 1. Bottom-start row (start 48 dp, end 200 dp, bottom 4 dp,
     32 dp gap): left column 420 dp — Russian title (headlineSmall, 1 line) and original title
     (bodyMedium, `muted`); right column — `"S2·E5  ·  ещё 23 мин"` (bodyMedium) + badge
     `"+2 новые"` if new episodes; `"ещё 7 серий впереди"` (labelMedium, `muted`) for serials with > 1
     remaining; ProgressLine 260 dp wide, 3 dp high.
   - Row labels "Сериалы" / "Фильмы" / "Детям" — **small** (labelMedium, `muted`), not section headers.
   - Card meta: `ещё <formatMinutes(left)>` if start > 0; else `след. серия` (serial) / `сначала` (movie).
     Badge `+N` for new episodes.
   - **OK on a Continue card plays immediately** at the exact second. Long-press → menu:
     "Открыть карточку" · "Отметить S2·E5 просмотренной" (hint "продолжить со следующей", serials) ·
     "Отметить весь сериал просмотренным" / "Отметить просмотренным" · "Убрать из «Продолжить»"
     (hint "очистить историю на kino.pub") · "Добавить в вишлист".
   This compact layout fits ~3.5 rows on screen — keep it compact.
2. **Спорт** row (section header) — see §10.
3. **Вишлист** (header badge `"3 новых"` via plural новое/новых/новых): PosterCards, subtitle = year,
   badge "НОВОЕ" when new. OK → title card. Long-press → "Открыть карточку", "Убрать из вишлиста",
   "Начал смотреть" (hint "добавить в «Продолжить»"), "Отметить просмотренным".
4. **Новые фильмы**, **Новые сериалы**, **Популярное**: PosterCards, subtitle `"2024 · IMDb 7.3"`.
   Long-press menu as wishlist but "Добавить в вишлист".

Behaviour:
- **Initial focus: first card of the first non-empty Continue row** (else first wishlist card), so OK =
  resume. Focus it as soon as cached data shows, and again when fresh data lands — unless the user
  already pressed a key.
- **Returning** from a title card / search / player: focus goes back to the card you left (remember a
  key like `c:<id>`, `w:<id>`, `<row>:<id>` in the ViewModel; lazy states keep scroll).
- **Search entry**: an `OutlinedButton` "Поиск" floating top-end (padding 27 dp top, 48 dp end); ↑ from
  the first row reaches it; ↓ or ← from it always go back to the first card (otherwise focus gets stuck
  there). The remote's Search key also opens Search.
- **Back**: if focus isn't on the first card → scroll to top and focus it; on the first card → show
  "Нажмите «Назад» ещё раз, чтобы выйти" (titleMedium on `surfaceHi`, rounded 10 dp, 40 dp from the
  bottom); a second Back within 2.5 s exits.
- **Reload** Home every time it becomes visible again and every time the app returns to the foreground
  (`repeatOnLifecycle(STARTED)`, skipping the very first start).
- Loading state (no cache): centred "Загрузка…" (`muted`); error "Не загрузилось: …" (`badge`).

### Title card (Details)
Column with 48/27 dp padding, 14 dp gaps:
- Header row (28 dp gap): poster 110 dp wide 2:3, rounded 8 dp. Right: Russian title (headlineSmall,
  1 line); facts line `"Original  ·  2021  ·  IMDb 7.9  ·  КП 7.5  ·  4K"` (titleMedium, `muted`,
  `quality ≥ 2160 → 4K` else `<q>p`); genres comma-separated (labelMedium, `muted`); a **focusable plot
  block** (clickable Surface, transparent, focused `surfaceHi`, no scale, rounded 8 dp, padding 8×4 dp):
  Russian plot (bodyMedium, `text` 85 %, 3 lines) + `"IMDb · <English plot>"` (bodyMedium, `muted`, 2
  lines). OK opens a dialog (900 dp wide, `surface`, rounded 12 dp, padding 28 dp) with the full title,
  Russian plot (titleMedium) and IMDb plot; ↑↓ scroll it by 160 px, OK/Back close.
- Action row (12 dp gap), **primary focused on open**:
  - Button: "Смотреть" / "Продолжить S1·E3 с 27 мин" / "Смотреть S1·E4" / "Продолжить с 1 ч 5 мин".
    Resume target = same rule as Continue (first unfinished at/after last touched).
  - OutlinedButtons: "В вишлист" ↔ "В вишлисте ✓"; "Начал смотреть" ↔ "Смотрю ✓" (watching = subscribed
    serial, or any progress and not fully watched; pressing "Смотрю ✓" = remove from Continue);
    "Отметить просмотренным" ↔ "Просмотрено ✓".
  - ↓ from the action row goes to the trailers row explicitly (`focusProperties { down = … }`) —
    geometry alone skipped it to the seasons.
- Trailers row (if any): OutlinedButtons `"▶ Трейлер · kino.pub"`, IMDb ones shortened:
  `"Silo | Official Trailer - Season 2"` → `"▶ Трейлер S2 · 2:31"` (Teaser → "Тизер"; otherwise first
  18 chars). OK → trailer player.
- Seasons row (serials): Button for the selected season, OutlinedButton for the others, `"Сезон 2"` +
  `" ✓"` if fully watched. Initially the resume season. **Long-press a season = toggle the whole season
  watched** (no menu).
- Episodes: `LazyColumn` of tv-material `ListItem`s, `selected` = the resume episode (scrolled so it's
  second from top): headline `"3. Title"` (or "Серия 3"); supporting `просмотрено` /
  `остановились на 27 мин из 58 мин` + ProgressLine (half width, 3 dp) / duration; trailing `✓` in
  accent if watched. OK → play from its saved position. **Long-press = toggle watched immediately.**
- Every action → message → re-read the item so statuses are real.

### Search
Row: text field 520 dp (BasicTextField, 22 sp, `surface` background, 2 dp border `accent` when focused
else `surfaceHi`, rounded 8 dp, padding 16×12, placeholder "Название фильма или сериала", cursor
accent) + FilterChips "Всё" "Фильмы" "Сериалы". Field focused on open. **BasicTextField swallows the
D-pad**: on ↓ move focus down manually; on → move right only when empty. Debounce 400 ms, min 2 chars;
status "Ищу…" / "Ничего не нашлось" / "Ошибка: …". Results: `LazyVerticalGrid(Adaptive(150 dp))`,
PosterCards with `"2019 · сериал · IMDb 8.1"`; OK → title card.

### Trailer / live player
Bare full-screen ExoPlayer (PlayerView, no controller, not focusable). Title top-start (headlineSmall)
for 3 s and while paused. OK/play-pause toggles, ← → ±10 s, Back exits, exits at the end. Error text
"Не воспроизводится: …". Used for kino.pub/IMDb trailers and for sport channels.

---

## 9. Player (the most tuned part — follow exactly)

### Playback engine (a ViewModel owning ExoPlayer for a session that spans episodes)
- ExoPlayer with `DefaultRenderersFactory(…).setExtensionRendererMode(ON)`, media source factory on the
  shared OkHttp (`OkHttpDataSource.Factory`). PlayerView = **SurfaceView** (default) — required for
  HDR. `useController=false`, `RESIZE_MODE_FIT`, keepScreenOn. **PlayerView must be non-focusable and
  `descendantFocusability = FOCUS_BLOCK_DESCENDANTS`** — it ate D-pad keys.
- File choice: files usable if any of hls4/hls/http; cap `h ≤ TrackChoice.maxHeight` (if nothing fits,
  use all); pick max `h`, at equal height prefer `codec == "h265"`. URL: **hls4 → hls → http**.
  Pin the player to that quality: `setForceHighestSupportedBitrate(true)` and, when a cap is set,
  `setMaxVideoSize(MAX, f.h)` (ABR otherwise sat at 1080p on a 4K file).
- Subtitles: sideloaded `SubtitleConfiguration`s from `video.subtitles` with non-null url (mime by
  extension: .vtt → VTT, .ass/.ssa → SSA, else SubRip; label = `LANG` + " (forced)").
- Start at `startSec`; `playWhenReady = true`. Opening the player stamps `played_at`.
- **Progress**: `marktime` every 30 s while playing, on every pause (`onIsPlayingChanged(false)`) and on
  exit (skip if < 5 s); each also refreshes `played_at`. Writes go through `appScope`, **not**
  `viewModelScope` (dead in `onCleared`).
- End of episode: `watching/toggle status=1`, then auto-advance to the next episode, crossing seasons.
- **Voiceovers** — kino.pub hls4 repeats every voice once per quality (GROUP-ID audio2160/1080/720/480),
  so ExoPlayer exposes **one audio TrackGroup per voiceover**, containing a copy per quality. An option
  is a *group*; selecting = `TrackSelectionOverride(group.mediaTrackGroup, all supported track indices)`.
  Mapping by track position picked the wrong voice. Match kino.pub `audios[]` metadata by the `NN.`
  prefix of the rendition label (`"02. Дубляж. Невафильм (RUS)"` → index 2), fall back to order.
  Label = `author.title` ?: `type.title` ?: label without the `NN.` prefix ?: language ?: "Дорожка N";
  detail = `"RU · 5.1 · AC3"` (lang upper-case, channels 1 моно/2 2.0/6 5.1/8 7.1, codec upper-case).
- **Subtitle order**: remembered language → RU → EN → rest alphabetically; names normalised (strip a
  trailing `#12`, upper-case), duplicates numbered ("ENG 2"). First option "Выкл".
- **Remembering picks**: every manual audio/subtitle/quality pick is saved as the title's TrackChoice
  (and the global default). On each new episode, once tracks are known, re-apply without re-saving:
  audio scored author match +4, language +2, type +1, pick the best if > 0; subtitles off if
  `subtitleLang == null`, else the best name/language match.
- Quality change = save `maxHeight`, reload the same episode at the current position.
- Quality labels go by **width** (3840×1600 is 4K): w ≥ 3200 or h ≥ 2000 → "4K"; ≥ 1800/1000 → "1080p";
  ≥ 1200/700 → "720p"; else `<h>p`. Detail = codec upper-case.
- Stats line for the OSD: `"3840×1600 24fps  ·  HEVC HDR10  ·  поток 18.2 Мбит/с  ·  сеть 85 Мбит/с  ·  буфер 42 с  ·  звук EAC3 5.1"`
  (Dolby Vision / HEVC / AVC from the video mime; HDR10 for ST2084, HLG; network from the
  `DefaultBandwidthMeter` singleton).
- Log the HLS `#EXT-X-MEDIA` lines and the audio-group list on load (debugging voiceovers).

### Overlay levels and the remote
Rule: **↓ reveals the next level, ↑ hides the current one.** Levels: NONE, FINE, OSD, TRACKS. The
player opens with the **OSD** visible. Outside TRACKS the root Box owns focus (requestFocus retried) so
the D-pad always works.

| State | Key | Action |
|---|---|---|
| NONE | ← / → | **Quick jump**: seek at once (EXACT seek), no seek bar; show only a label top-centre (48 dp from top, black 55 %, rounded 12 dp, padding 28×12): `"+18 с"` / `"−1:30"` at 44 sp white, under it `"шаг 5 с  ·  12:04 / 47:10"` (labelMedium, `muted`). The step grows with its number in a burst: **3 s ×2 → 5 s ×2 → 10 s ×2 → 30 s ×2 → 1 min ×2 → 5 min**. A burst = presses < 1.5 s apart in the same direction; holding the key auto-repeats, throttled to one step per **220 ms**. Label fades 1.5 s after the last step. |
| NONE | ↑ | enter **FINE** |
| NONE | ↓ | **OSD** |
| NONE | OK | pause/resume only (no seek bar) |
| OSD | ← / → | **scrub**: move a target marker, no seeking yet. Step by how long the key is held: < 0.6 s 10 s, < 1.5 s 30 s, < 3 s 1 min, < 5 s 2 min, then 5 min. Show the target time (titleMedium accent) above the marker and `"+30 с"` hint in the middle of the time row. **Commit ~0.9 s after key-up**, or at once on OK. **No preview frame** — owner's call. |
| OSD | ↓ | **TRACKS** (cancels scrub) |
| OSD | ↑ | NONE (cancels scrub) |
| OSD | OK | commit scrub if any, else pause/resume |
| FINE | ← / → | frame-step: pause, EXACT seek, step by hold time 0.5 s → (≥ 0.6 s) 1 s → (≥ 1.5 s) 2 s → (≥ 3 s) 5 s; label `"+0,5 с"` |
| FINE | ↑ | just brings the bar back (FINE is **sticky**) |
| FINE | ↓ / Back | leave to NONE |
| FINE | OK | pause/resume |
| TRACKS | D-pad | handled by the panel; ↑ from its top row → OSD |
| any | Back | TRACKS → OSD; FINE → NONE; scrubbing → cancel; else exit (progress saved) |
| any | ⏯ / Space | pause/resume; ⏵ play; ⏸ pause |
| any | ⏪ / ⏩ | quick jump (as ← → in NONE) |
| any | audio-track key | next voiceover, show OSD |
| any | captions key | subtitles off ↔ last language, show OSD |
| any | next key | next episode |

Visuals:
- **OSD** (bottom, full width, black 60 %, padding 48×20 dp, 10 dp gaps; slides up + fades in 240 ms,
  out 200 ms): **full** kino.pub title, e.g. "Укрытие / Silo" (headlineSmall) + `"S1·E5 · Сын уборщика"` (titleMedium, `muted`) on one baseline;
  [scrub target time]; **SeekBar**; time row `12:04` … `−35:06` (labelMedium, `muted`); stats line
  (labelMedium, `muted` 80 %) only in OSD. **No hint text.** Auto-hides after 4 s without input if
  playing and not scrubbing.
- **SeekBar**: 24 dp tall box; ProgressLine 8 dp (OSD/FINE, "emphasized") or 4 dp (TRACKS); a **white
  4×22 dp marker** at the position; an accent 4×22 dp marker at the scrub target.
- **FINE bar** (top, full width, black 55 %, padding 48×14 dp; slides down + fades 220 ms / 180 ms):
  emphasized SeekBar, then `12:04,3` (titleMedium accent, tenths) left and the step label
  (titleMedium `muted`) right. Hides after 3 s idle and returns on the next ← → / ↑; FINE mode itself
  stays until ↓ or Back.
- **TRACKS panel**: appears *inside* the OSD column under the stats line with **one**
  `expandVertically`/`shrinkVertically` (200 ms, from/towards top) — no outer `animateContentSize` (it
  lagged and made the bar "fall" after the panel vanished). Panel: rounded 10 dp, `surface` 92 %,
  padding 16 dp, rows 10 dp apart: "Озвучка", "Субтитры", "Качество", and "Дальше" with one chip
  "Следующая серия ›" (if there is a next episode). Row = label (labelMedium `muted`, 96 dp wide) +
  `LazyRow` of FilterChips (12 dp gap, padding 6×4 dp), chip text `"LostFilm · RU · 5.1 · AC3"`,
  **no focus zoom** (scaled chips overlapped on the TV). **Every option visible at once**, no lists.
  Focus lands on the **currently selected voiceover** immediately: scroll its row to it first, then
  retry `requestFocus` every 40 ms (up to 30×) **until the panel really has focus** (`hasFocus`) — it
  used to "succeed" while the panel was still expanding and cost an extra key press. While the panel
  is open but focus isn't inside, D-pad keys re-trigger that focus request instead of leaking.
- **Pause sign**: when the user pauses (OK/⏯), a big `❚❚` (120 sp, white 90 %) over the picture dimmed
  by black 35 % fades in (150 ms), then after 1.2 s fades out (700 ms) into a small top-right badge
  (`❚❚` 26 sp white 80 % on black 40 %, rounded 8 dp, 28 dp from top, 36 dp from right) shown while
  paused and no overlay. **Pauses caused by frame stepping show no pause sign** (track a `userPaused`
  flag). Pause state comes from `playWhenReady`, not `isPlaying` (buffering after a seek isn't "playing").
- Errors: centred "Ошибка воспроизведения: <code>" in `badge`.
- Keep the screen on while the player is shown.

---

## 10. Sport row (Home)

kino.pub live TV: `GET /v1/tv` → `channels[{id, name, title, logos{s,m}, stream, …}]`. `stream` is a plain
HTTPS HLS URL (one 1080p H.264 variant → media playlist → 6 s MPEG-TS segments) that ExoPlayer plays
as is. `current` is **always empty** — the guide comes from elsewhere.

**Lineup** (kino.pub `name` → iptvx.one XMLTV channel id), in this order; skip channels missing or
with a blank stream:
`eurosport1→eurosport1-pl`, `eurosport2→eurosport2-pl`, `okkosport→okko-sport`,
`okkoprime→okko-sport-2`, `okkofootball→okko-football`, `okkochampionat→okko-chemp`,
`matchtv→match-tv`, `nashfootball→match-premier` (kino.pub calls Матч Премьер "nashfootball"),
`matcharena→match-arena`, `matchigra→match-igra`, `football1→match-football1`,
`football2→match-football2`, `football3→match-football3`.

**Guide**: `https://iptvx.one/epg/epg_lite.xml.gz` (~15 MB gzip, ~170 MB XML). Stream it through
`GZIPInputStream` + `XmlPullParser` (never load it whole), keep only wanted channel ids and programmes
overlapping now−6 h … now+36 h (first `<title>` only; times `yyyyMMddHHmmss Z`). Store as
`filesDir/epg.json` with fetch time and a schema version (bump to force a re-download when ids change).
Re-download when > 12 h old or when the guide reaches < 3 h ahead. HTTP read timeout 120 s.
**Eurosport**: kino.pub carries the European feed whose times match `eurosport1-pl`/`eurosport2-pl`;
iptvx's Russian `eurosport1`/`eurosport2` list the same shows **shifted by a whole hour** (with gaps),
so use them only for Russian titles: find the shift in −3 h…+3 h (30-min steps) that lines up the most
programme starts (≥ 2 matches), copy titles by shifted start, otherwise keep the Polish title.
Self-calibrating across DST. Log parse time — on a weak stick it matters (13 s on the Chromecast).

**Still frames**: per channel, master playlist → media playlist → **newest segment** → download the
first **700 KB** (HTTP Range) → decode the first keyframe → JPEG (85 % NV21→JPEG, then re-encode at
½ size, 75 %) → `filesDir/snaps/<name>-<timestamp>.jpg` (new name every time so the image loader
reloads; delete the old one). Decoder: MediaExtractor + MediaCodec, output `COLOR_FormatYUV420Flexible`,
read `getOutputImage` (handle any row/pixel strides and the crop rect), 5 s deadline. **Prefer a
software decoder** (`MediaCodecInfo.isSoftwareOnly`, API 29+; on older APIs pick names starting with
`OMX.google.`/`c2.android.`) so snapshots never touch the hardware decoder. Android's TS extractor
doesn't see the video of **interlaced** streams (Eurosport 2 is 1080i — it reports only the audio
track): then demux it yourself — walk 188-byte TS packets, PAT → PMT pid, PMT → first stream type
`0x1B` (H.264) pid, collect PES payloads (one PES = one access unit, skip the PES header
`9 + header_length` bytes, drop the last, truncated one), feed them to an AVC decoder configured as
1920×1080 with timestamps `n × 40 ms`. Log `decode <codec>: in=N out=M <reason>` on failure.
Refresh every **5 min**, per channel only when its frame is older than 5 min. A failed grab drops the
old frame (logo instead). Frames older than **15 min** are never shown.

**When it runs**: only while Home is composed **and** the app is STARTED (`repeatOnLifecycle(STARTED)`),
never during playback or with the TV off (Home stays composed in the background — it used to keep
fetching there). **Never `runCatching` inside a loop tied to a composition**: it swallows
`CancellationException`, so leaving Home kept the loop spinning. Use a helper that rethrows
cancellation.

**Card**: channel title on the frame, programme title in full under it + "до HH:mm", ProgressLine =
programme elapsed fraction (refresh "now" every 30 s). OK → plays the live stream in the trailer
player.

**Live preview**: when a sport card keeps focus for **0.7 s**, play its channel **muted** inside the
card: one shared ExoPlayer for the whole row, audio track type **disabled** (not just volume 0),
rendering into a `TextureView` (a SurfaceView can't sit inside a scaled card); the TextureView stays
transparent until `onRenderedFirstFrame`, so the still frame shows until then. Stop on blur, on
leaving Home and when the app stops. Release the player with Home.

---

## 11. Adapting to the Xiaomi stick

- **Performance**: these sticks have 1–2 GB RAM and slower CPUs than the Chromecast. Always test the
  R8 release build. Measure: Home time-to-first-focus (from cache should be < 1 s after splash), EPG
  parse time, snapshot decode time, memory (`adb shell dumpsys meminfo dev.lena.lenapub`).
  If the EPG parse takes > 60 s or the app gets killed, parse in a background WorkManager-free
  coroutine on `appScope` with a lower thread priority, or drop the Russian-title merge. If a muted live
  preview stutters or blocks the main player's decoder (e.g. playing a title right after a preview),
  release the preview player before opening any player screen; if still bad, disable live preview
  (keep still frames) and record that in HANDOFF.
- **Codec/HDR**: register only what the device decodes (§7). On a 1080p stick 4K/HDR must be off.
- **Remote**: Xiaomi remotes often lack ⏪ ⏩, Search, audio-track and captions keys — the D-pad map
  covers everything; extra keys are a bonus. The Assistant/Search key may be intercepted by the system.
- **API level**: on Android 9 avoid APIs above 28 without version checks (e.g. `isSoftwareOnly`, some
  `Display.Mode` HDR calls). Splash: core-splashscreen handles old APIs.
- **Display**: check `adb shell wm size` / density. Compose dp sizing above assumes ~960×540 dp; if the
  stick reports a different density, check text fits (screenshots) and adjust only if broken.

## 12. Dead ends — don't build these

- Google TV "Continue watching"/Watch Next rows and the Engage SDK: accepted but never shown for a
  sideloaded app (allow-listed partners only).
- Home-screen widgets: Android TV has none.
- Auto-opening the app when the TV turns on (accessibility-service trick): the owner declined it.
- Screensaver, launcher replacement: declined.
- Scrub preview thumbnails: kino.pub has no sprites; decoding frames was built and then removed by the
  owner's choice.

## 13. Deploy script and hand-over

`scripts/tv.sh` (bash, `set -euo pipefail`, sets `JAVA_HOME`, `ANDROID_HOME`, PATH):
- `pair <ip:port> <code>` · `connect [ip[:port]]` (remembers `.tv-address`, gitignored; default port 5555)
- `deploy` (default): ensure connected (rediscover the wireless-debugging port via `adb mdns services`
  if the stick uses it; reconnect; fail with a clear message if the TV is off), refuse if something is
  playing, `./gradlew --quiet :app:installRelease`, launch `dev.lena.lenapub/.MainActivity`.
- `run`, `logs` (`adb logcat --pid=$(adb shell pidof -s <pkg>)`), `shot [file.png]`.

Write to the doc standard: **README.md** (what/why, features, architecture diagram, setup from zero,
TV setup steps, deploy, project layout), **CLAUDE.md** (TL;DR, read order, mental model, how to
develop, conventions, gotchas — include every gotcha from this spec you actually hit), **HANDOFF.md**
(state, device facts from Phase B, deviations from this spec and why, open items, how to resume).
`.gitignore`: `local.properties`, `.tv-address`, build dirs, `*.apk`, `*.png` screenshots in the root.

Tell the person, in plain words, at the end:
- how to update the app later: open Terminal, `cd ~/Projects/lenapub && scripts/tv.sh deploy`
  (or ask Claude Code to do it);
- that the TV must be awake, and that after a stick reboot the wireless-debugging port changes
  (the script handles it) or, on Android 9–11, `scripts/tv.sh connect <ip>` again;
- how to log out/in (Settings → Apps → lenapub → Clear data, then the login code appears).
- Optionally pin lenapub as the first app on the TV home: long-press its tile → Move.

## 14. Acceptance checklist (verify on the real TV with screenshots before saying "done")

- [ ] lenapub tile with the lenapub banner in the TV launcher; launch shows the lenapub splash, then Home.
- [ ] Login with the code; device appears in kino.pub's device list as "lenapub (<model>)"; device
      settings sent match the stick's real capabilities (log).
- [ ] Home: hero + Сериалы/Фильмы/Детям rows ordered by recency; first card focused on launch; OK resumes
      at the right second; Back behaviour; focus returns to the left card; Search button reachable.
- [ ] Relaunch with Wi-Fi off (or stick just woken): Home shows the cached content, nothing wiped.
- [ ] Long-press menus on every card type; each action reflects on kino.pub (check the website) and the
      message appears. Test only on a title the person agreed to, undo afterwards.
- [ ] Wishlist: add/remove sticks (repeat-until-confirmed); "new" badge clears after 1.5 s focus +
      refresh.
- [ ] Title card: plot dialog, trailers (kino.pub + IMDb) play, season/episode long-press toggles,
      primary button label correct.
- [ ] Search with filters; no 3D items.
- [ ] Player: best allowed quality starts; stats line shows sensible codec/resolution; voiceover switch
      really changes the voice; subtitles; quality change resumes at the same spot; quick jump steps
      and label; OSD scrub commit; FINE mode; TRACKS focus lands in one press; pause sign behaviour;
      progress visible on the kino.pub website after exit; next episode auto-advance.
- [ ] Sport: channels in the right order with current programmes (Eurosport titles in Russian when
      available, times correct), fresh frames, OK plays live, muted preview after 0.7 s, no network
      activity for frames while a film plays or the TV is off (check logcat).
- [ ] `scripts/tv.sh deploy` works from a fresh Terminal; docs written; git committed.
