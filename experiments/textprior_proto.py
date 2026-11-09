"""Prototype: isolate the printed text of a photo, ignoring keyboard / stickers."""
from __future__ import annotations

import sys
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "out" / "textprior"


def glyph_mask(bgr):
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    bg = cv2.GaussianBlur(gray, (0, 0), 41)
    norm = cv2.divide(gray, bg, scale=255)
    norm = cv2.GaussianBlur(norm, (3, 3), 0)
    _, th = cv2.threshold(norm, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)

    h, w = gray.shape
    n, lab, stats, _ = cv2.connectedComponentsWithStats(th, 8)
    keep = np.zeros_like(th)
    hs = []
    for i in range(1, n):
        x, y, ww, hh, area = stats[i]
        if area < 40 or hh < 6 or ww < 2:
            continue
        if hh > 0.06 * h or ww > 0.10 * w:          # keyboard keys / stickers / blobs
            continue
        ar = ww / max(hh, 1)
        if ar > 8 or ar < 0.08:
            continue
        keep[lab == i] = 255
        hs.append(hh)
    return keep, np.median(hs) if hs else 10.0


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    bgr = cv2.imread(str(ROOT / "data" / "real_white_desk.jpg"))
    m, hg = glyph_mask(bgr)
    print("glyph height median", hg, "coverage %.3f" % (m.mean() / 255))
    cv2.imwrite(str(OUT / "glyphs.png"), m)

    # merge glyphs into lines/blocks
    k = max(int(hg * 0.9), 3)
    merged = cv2.dilate(m, cv2.getStructuringElement(cv2.MORPH_RECT, (k, k)))
    merged = cv2.morphologyEx(merged, cv2.MORPH_CLOSE,
                              cv2.getStructuringElement(cv2.MORPH_RECT, (int(hg * 2), int(hg * 2))))
    cv2.imwrite(str(OUT / "blocks.png"), merged)

    n, lab, stats, _ = cv2.connectedComponentsWithStats(merged, 8)
    order = np.argsort(stats[1:, 4])[::-1][:5] + 1
    for i in order:
        print("block", stats[i, :5].tolist())
    big = np.zeros_like(merged)
    if len(order):
        big[lab == order[0]] = 255
    ys, xs = np.where(big > 0)
    print("main block bbox x[%d..%d] y[%d..%d]" % (xs.min(), xs.max(), ys.min(), ys.max()))


if __name__ == "__main__":
    main()
