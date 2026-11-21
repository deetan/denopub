#!/usr/bin/env python3
"""Draws Natapub's own launcher icon (public/icon.png, 512x512): a bold amber "N" on the app's
dark background with a thin amber underline, so it can't be mistaken for denopub's tile.
Run: python3 scripts/make-icon.py  (needs Pillow; uses a macOS system bold font)."""
import os
from PIL import Image, ImageDraw, ImageFont

BG, ACCENT, SIZE = (14, 15, 18), (242, 179, 61), 512
FONTS = ['/System/Library/Fonts/Supplemental/Arial Bold.ttf', '/System/Library/Fonts/Helvetica.ttc']

img = Image.new('RGB', (SIZE, SIZE), BG)
d = ImageDraw.Draw(img)
d.rounded_rectangle([24, 24, SIZE - 24, SIZE - 24], radius=96, fill=(26, 28, 33))
font = next(ImageFont.truetype(f, 340) for f in FONTS if os.path.exists(f))
box = d.textbbox((0, 0), 'N', font=font)
w, h = box[2] - box[0], box[3] - box[1]
d.text(((SIZE - w) / 2 - box[0], (SIZE - h) / 2 - box[1] - 24), 'N', font=font, fill=ACCENT)
d.rounded_rectangle([156, 392, 356, 408], radius=8, fill=ACCENT)
out = os.path.join(os.path.dirname(__file__), '..', 'public', 'icon.png')
img.save(out)
print('wrote', os.path.normpath(out))
