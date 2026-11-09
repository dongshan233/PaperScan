"""Text-block prior on a real white-on-white capture.

    python run_text_prior.py                 # uses data/real_white_desk.jpg
    python run_text_prior.py --image file.jpg --flip

What it does
------------
1. isolates printed glyphs (the sheet outline is 0-5 gray levels, the text is ~150)
2. finds the dominant text block and rotates the page so the text is upright
3. reports how much page-edge evidence actually exists around that block
4. writes an upright, illumination-corrected crop of the content

A 180-degree ambiguity remains without OCR; pass --flip or let a product decide
it from OCR confidence on the first line (essentially free once OCR is running).
"""
from __future__ import annotations

import argparse
from pathlib import Path

import cv2
import numpy as np

from scanner import textprior as T

OUT = Path(__file__).parent / "out" / "textprior"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--image", default=str(Path(__file__).parent / "data" / "real_white_desk.jpg"))
    ap.add_argument("--flip", action="store_true", help="rotate the canonical frame by 180 deg")
    ap.add_argument("--page", action="store_true", help="also try the full-page estimate")
    args = ap.parse_args()

    OUT.mkdir(parents=True, exist_ok=True)
    bgr = cv2.imread(args.image)
    if bgr is None:
        raise SystemExit(f"cannot read {args.image}")

    res = T.run(bgr)
    rot, rblk = res["rotated"], None
    if args.flip:
        res["rotated"] = cv2.rotate(rot, cv2.ROTATE_180)
        H, W = rot.shape[:2]
        x0, y0, x1, y1 = res["rbbox"]
        res["rbbox"] = (W - x1, H - y1, W - x0, H - y0)

    content = T.rectify_content(res, margin=0.03)
    content = T.enhance(content)
    cv2.imwrite(str(OUT / "content.png"), content)
    print(f"  content: {content.shape[1]}x{content.shape[0]} -> {OUT / 'content.png'}")

    # how much page-edge evidence is there around the block?
    seam = res["seam"]
    H, W = seam.shape
    x0, y0, x1, y1 = res["rbbox"]
    pad = 0.25 * max(x1 - x0, y1 - y0)
    win = (int(max(0, x0 - pad)), int(max(0, y0 - pad)), int(min(W - 1, x1 + pad)), int(min(H - 1, y1 + pad)))
    ring = seam.copy()
    ring[y0:y1, x0:x1] = 0
    peak = float(ring[win[1]:win[3], win[0]:win[2]].max())
    print(f"  strongest seam response around the block: {peak:.2f} "
          f"({'weak / no page edge in view' if peak < 0.35 else 'edge present'})")

    if args.page:
        print("  (full-page estimate -- unreliable when the sheet is cropped)")
        print(f"  page box: {[round(v, 1) for v in res['quad'].ravel()]}")

    print(f"\n  outputs in {OUT}")


if __name__ == "__main__":
    main()
