#!/usr/bin/env python3
"""Import Dan Patrick's platform logos into app/src/main/assets/system_logos.

The source is the "v2.1 Recommended Versions (Normal) (1 Per Platform)" pack from the LaunchBox
Community Forums (https://forums.launchbox-app.com/profile/85709-dan-patrick/). Each style folder
gets one WebP per Xross platform id, scaled to fit MAX_W x MAX_H.

    python3 -I tools/system_logos/import_logos.py <unzipped pack folder>
"""
import re
import sys
from pathlib import Path

from PIL import Image

MAX_W, MAX_H = 512, 256

# Xross style folder -> folder inside the pack.
STYLES = {
    "color": "Light - Color",
    "mono": "Light - Black & White",
    "white": "Light - Just White",
    "dark_color": "Dark - Color",
    "dark_mono": "Dark - Black & White",
    "black": "Dark - Just Black",
    "outline": "White Outline Versions/Thin Outlines",
}

# Xross platform id -> logo names to try, in order (the pack's file stems, compared loosely).
LOGOS = {
    "nes": ["Nintendo Entertainment System"],
    "snes": ["Super Nintendo Entertainment System"],
    "n64": ["Nintendo 64"],
    "gb": ["Nintendo Game Boy", "Nintendo Game Boy (2)"],
    "gbc": ["Nintendo Game Boy Color"],
    "gba": ["Nintendo Game Boy Advance"],
    "nds": ["Nintendo DS"],
    "megadrive": ["Sega Genesis"],
    "mastersystem": ["Sega Master System"],
    "gamegear": ["Sega Game Gear"],
    "pcengine": ["NEC PC Engine"],
    "psx": ["Sony Playstation"],
    "ps2": ["Sony Playstation 2"],
    "ps3": ["Sony Playstation 3"],
    "psp": ["Sony PSP"],
    "gc": ["Nintendo GameCube"],
    "wii": ["Nintendo Wii"],
    "dreamcast": ["Sega Dreamcast"],
    "saturn": ["Sega Saturn"],
    "n3ds": ["Nintendo 3DS"],
    "switch": ["Nintendo Switch"],
    "scummvm": ["ScummVM"],
    "arcade": ["Arcade Classics", "MAME"],
}


def key(stem: str) -> str:
    """'NEC PC Engine -7-06' and 'Nintendo Game Boy Color-01' lose their export suffixes."""
    stem = re.sub(r"(\s*-\d*)+$", "", stem.strip()).rstrip(". ")
    return re.sub(r"[^a-z0-9()]", "", stem.lower())


def main(src: Path, out: Path) -> int:
    missing = []
    for style, folder in STYLES.items():
        files = {}
        for f in sorted((src / folder).rglob("*.png")):
            files.setdefault(key(f.stem), f)
        dest = out / style
        dest.mkdir(parents=True, exist_ok=True)
        for pid, names in LOGOS.items():
            found = next((files[key(n)] for n in names if key(n) in files), None)
            if found is None:
                missing.append(f"{style}/{pid}")
                continue
            im = Image.open(found).convert("RGBA")
            im = im.crop(im.getbbox() or (0, 0, im.width, im.height))
            im.thumbnail((MAX_W, MAX_H), Image.LANCZOS)
            im.save(dest / f"{pid}.webp", "WEBP", lossless=True, method=6)
    if missing:
        print("missing:", ", ".join(missing), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[2]
    sys.exit(main(Path(sys.argv[1]), root / "app/src/main/assets/system_logos"))
