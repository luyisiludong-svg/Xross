#!/usr/bin/env python3
"""Bake an ASCII-art reference picture into the cell bitmap a pack shader embeds.

Each character cell of the output grid gets a class (which ink, 3 bits) and a brightness level
(3 bits). Five cells are packed per int, lowest bits first, rows from the bottom up.

  python3 bake_ascii.py <reference image> [debug.png]

Prints the GLSL constants. The crop and the sword rectangles below are in the reference's pixels.
"""
import sys, colorsys
import numpy as np
from PIL import Image

W, H = 68, 68                       # cells
CROP = (840, 40, 1940, 1452)        # the figure, ground at the bottom edge
# Sword parts (x0, y0, x1, y1): they fall as one piece, before the figure fades in.
BLADE = [(1240, 532, 1356, 1500)]
HILT = [(1283, 40, 1332, 500), (1160, 494, 1445, 532)]
RAYS_X = 1885                       # right of this is only light rays: left to the shader
RUBBLE_Y = 1410                     # below this only the front foot is kept, not the rubble
FOOT_X = 1235
CORE_RECTS = [(1535, 585, 1615, 735), (1515, 845, 1615, 955)]   # the bared core's lit blocks

NONE, WHITE, WARM, RED, GOLD, CORE, STEEL, TINT = range(8)   # TINT: pale armour in warm light

def main():
    im = np.asarray(Image.open(sys.argv[1]).convert('RGB')).astype(np.float32) / 255.0
    x0, y0, x1, y1 = CROP
    cw, ch = (x1 - x0) / W, (y1 - y0) / H
    cells = np.zeros((H, W, 2), np.int32)   # row 0 = bottom
    for r in range(H):
        for c in range(W):
            px0, px1 = x0 + c * cw, x0 + (c + 1) * cw
            py1 = y1 - r * ch
            py0 = py1 - ch
            blk = im[int(py0):int(py1), int(px0):int(px1)].reshape(-1, 3)
            lum = blk @ np.array([0.299, 0.587, 0.114], np.float32)
            ink = lum > 0.30
            frac = ink.mean()
            cx, cy = (px0 + px1) / 2, (py0 + py1) / 2
            inside = lambda rects: any(a <= cx < b2 and t <= cy < b for a, t, b2, b in rects)
            if frac < 0.09:
                continue
            col = (blk[ink] * lum[ink, None]).sum(0) / lum[ink].sum()
            h, s, v = colorsys.rgb_to_hsv(*col)
            h *= 360
            mean = lum.mean()
            # Brightness is the colour of the strokes (the reference packs every cell with heavy
            # glyphs), thinned a little where the cell is sparsely inked.
            stroke = np.sort(lum)[-max(1, lum.size // 8):].mean()
            lvl = int(np.clip(round(7 * max(stroke - 0.20, 0.0) / 0.62 * min(1.0, 0.72 + 1.5 * frac)), 1, 7))
            if inside(BLADE) or inside(HILT):
                cls = STEEL            # the shader tells blade from hilt by the row
            elif cx > RAYS_X or (cy > RUBBLE_Y and (cx > FOOT_X or frac < 0.2)):
                continue
            elif 150 <= h <= 270 or (s < 0.28 and not (12 <= h <= 75 and s > 0.10)):
                cls = WHITE
            elif 30 <= h < 75 and frac > 0.5 and inside(CORE_RECTS):
                cls = CORE
            elif h < 13 or h > 335:
                cls = RED
            elif s < 0.5 and not inside(CORE_RECTS):
                cls = TINT
            elif h < 31:
                cls = WARM
            else:
                cls = GOLD
                if frac < 0.14:
                    continue           # a stray '+' of the rays
            cells[r, c] = (cls, lvl)

    flat = [(int(cl) << 3) | int(lv) for cl, lv in cells.reshape(-1, 2)]
    words = []
    for i in range(0, len(flat), 5):
        w = 0
        for k, v in enumerate(flat[i:i + 5]):
            w |= v << (6 * k)
        words.append(w)
    print(f"const int BW = {W}, BH = {H};")
    print(f"const int B[{len(words)}] = int[{len(words)}](")
    for i in range(0, len(words), 10):
        tail = "," if i + 10 < len(words) else ""
        print("    " + ", ".join(str(w) for w in words[i:i + 10]) + tail)
    print(");")

    if len(sys.argv) > 2:
        pal = [(0, 0, 0), (200, 228, 245), (255, 140, 45), (235, 60, 45), (255, 200, 75),
               (255, 245, 150), (210, 222, 235), (255, 200, 170)]
        out = np.zeros((H * 8, W * 6, 3), np.uint8)
        for r in range(H):
            for c in range(W):
                cl, lv = cells[r, c]
                out[(H - 1 - r) * 8:(H - r) * 8 - 1, c * 6:c * 6 + 5] = np.array(pal[cl]) * (lv / 7)
        Image.fromarray(out).resize((W * 12, H * 16), Image.NEAREST).save(sys.argv[2])

if __name__ == "__main__":
    main()
