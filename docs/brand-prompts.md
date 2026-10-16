# denopub — brand art prompts

The art in the repo is a placeholder: an amber rounded square with a dark play triangle plus a
"denopub" wordmark. Use the prompts below in ChatGPT (image
generation) or Claude Design to get the real art, then drop the files into the paths listed under
**Where files go**.

Shared brief (paste first in every prompt):

> Brand: **denopub**, a personal Android TV app for watching movies and serials. Mood: calm,
> cinematic, functional, a home cinema at night. Not playful, no mascots, no film-reel or popcorn
> clichés. Palette: near-black background `#0E0F12`, warm amber accent `#F2B33D`, off-white
> `#EDEEF0`. Flat vector, no gradients beyond a very subtle one, no photo textures, no text other
> than what is asked. Must read clearly at small size from 3 metres away on a TV.

## 1. Mark (the symbol, used everywhere)

> Design a simple geometric **symbol** for denopub: a single shape that combines a "play" triangle
> with a subtle letter **d** (for example the counter of a lowercase d forming the play triangle,
> or a play triangle with a rounded vertical stem on its left). Amber `#F2B33D` on transparent
> background, centred, bold, 1 colour. Must still be recognisable at 48×48 px. Output: square
> 1024×1024 PNG with transparent background, plus an SVG if possible. Give 4 variations side by
> side first.

## 2. Launcher icon (Android, square)

> Using the chosen mark, make an **Android app icon**: 1024×1024, background `#0E0F12` filling the
> whole square (no rounded corners — Android masks it), mark in amber centred and occupying ~55%
> of the width (keep everything inside the central 66% safe zone). No text.

## 3. Android TV banner (the tile in the TV launcher)

> Using the chosen mark, make an **Android TV launcher banner**: exactly **1280×720 px**
> (16:9, shown at 320×180 dp). Background `#0E0F12`, optionally a very subtle warm vignette.
> Left: the mark in amber, height ~45% of the banner. Right of it: the wordmark **denopub** in
> lowercase, a clean geometric sans (like Inter / Manrope / SF Rounded), off-white `#EDEEF0`,
> cap-height about 30% of the banner. Keep 10% padding on all sides; nothing touches the edges.
> Flat, crisp, high contrast.

## 4. Splash mark

> Using the chosen mark, export a **splash icon**: 1152×1152 px, transparent background, the mark
> centred and fitting inside a centred circle of diameter 768 px (Android 12 splash safe area).
> Amber only. Also export a monochrome off-white version.

## 5. Optional: Claude Design version

> Create a small brand sheet for **denopub** (personal Android TV movie app): the mark, launcher
> icon, 1280×720 TV banner, splash screen at 1920×1080 (mark centred, "denopub" wordmark under it
> on `#0E0F12`), and the colour + type tokens. Style: functional, cinematic, dark, amber accent.
> Use the shared brief above.

## Where files go

| Asset | File | Notes |
|---|---|---|
| TV banner | `app/src/main/res/drawable-xhdpi/banner.png` | resize to 640×360 (xhdpi); or add `drawable-xxhdpi/banner.png` at 960×540 |
| Launcher icon | `app/src/main/res/mipmap-{xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` | 96 / 144 / 192 px |
| Splash mark | `app/src/main/res/drawable/splash_mark.xml` | convert the SVG to a VectorDrawable (288 dp canvas, mark inside the 192 dp circle), or replace with a PNG and update `SplashOverlay.kt` + `themes.xml` |

Then run `scripts/tv.sh deploy`.
