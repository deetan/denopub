#!/usr/bin/env python3
"""Screensaver painting catalog: app/src/main/assets/art/paintings.json.

The TV re-reads this file from GitHub `main` once a day, so pushing a change here updates the
screensaver without a release.

  scripts/art-catalog.py check            schema, unique ids, duplicates, text lengths
  scripts/art-catalog.py check --images   also fetch every image URL the TV would request (slow, polite)
  scripts/art-catalog.py merge a.json b.json ...   append entries (new ids only), then check
  scripts/art-catalog.py stats            counts by artist
"""
import json
import sys
import time
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path

CATALOG = Path(__file__).resolve().parent.parent / "app/src/main/assets/art/paintings.json"
UA = "denopub/1.0 (Android TV screensaver; https://github.com/deetan/denopub) Mozilla/5.0"
REQUIRED = ["id", "titleRu", "titleEn", "date", "artistRu", "artistEn", "artistLife", "museumRu", "story", "w", "h"]


def load(p=CATALOG):
    return json.loads(Path(p).read_text(encoding="utf-8"))


def save(items):
    CATALOG.write_text(json.dumps(items, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


def image_url(p):
    """Same rule as Painting.imageUrl() in data/Art.kt."""
    if p.get("commons"):
        aspect = p["w"] / p["h"] if p.get("w") and p.get("h") else 1.5
        width = 3840 if aspect > 2.3 else 1280 if aspect < 1 else 1920
        name = urllib.parse.quote(p["commons"].replace(" ", "_"))
        base = f"https://commons.wikimedia.org/wiki/Special:FilePath/{name}"
        return base if p.get("w", 0) and p["w"] < width else f"{base}?width={width}"
    return p.get("url")


def check(items, images=False):
    errors = []
    ids = Counter(p.get("id") for p in items)
    errors += [f"duplicate id {i}" for i, n in ids.items() if n > 1]
    keys = Counter((p.get("artistEn", "").lower(), p.get("titleEn", "").lower()) for p in items)
    errors += [f"duplicate work {a} / {t}" for (a, t), n in keys.items() if n > 1]
    for p in items:
        pid = p.get("id", "?")
        missing = [k for k in REQUIRED if not p.get(k)]
        if missing:
            errors.append(f"{pid}: missing {missing}")
        if not (p.get("commons") or p.get("url")):
            errors.append(f"{pid}: no commons/url")
        story = p.get("story", "")
        if story and not 200 <= len(story) <= 900:
            errors.append(f"{pid}: story length {len(story)}")
    if images:
        for i, p in enumerate(items):
            url = image_url(p)
            try:
                req = urllib.request.Request(url, headers={"User-Agent": UA, "Range": "bytes=0-2047"})
                with urllib.request.urlopen(req, timeout=30) as r:
                    ctype = r.headers.get("Content-Type", "")
                    head = r.read(12)
                    # Some museums (Tate) serve real JPEGs as application/octet-stream; Android decodes by bytes.
                    magic = head[:3] == b"\xff\xd8\xff" or head[:8] == b"\x89PNG\r\n\x1a\n" or head[8:12] == b"WEBP"
                    if not (ctype.startswith("image/") or magic):
                        errors.append(f"{p['id']}: {ctype} from {url}")
            except Exception as e:  # noqa: BLE001
                errors.append(f"{p['id']}: {e} from {url}")
            time.sleep(1.0)
            if i % 50 == 49:
                print(f"  …{i + 1}/{len(items)} images", file=sys.stderr)
    return errors


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "check"
    if cmd == "merge":
        items = load() if CATALOG.exists() else []
        have = {p["id"] for p in items}
        for f in sys.argv[2:]:
            new = [p for p in load(f) if p["id"] not in have]
            have |= {p["id"] for p in new}
            items += new
            print(f"{f}: +{len(new)}")
        save(items)
        cmd = "check"
    items = load()
    if cmd == "stats":
        for a, n in Counter(p["artistRu"] for p in items).most_common():
            print(f"{n:3} {a}")
        print(f"{len(items)} paintings")
        return
    errors = check(items, images="--images" in sys.argv)
    for e in errors:
        print(e)
    print(f"{len(items)} paintings, {len(errors)} problems")
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
