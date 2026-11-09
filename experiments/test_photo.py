"""Run the tiered tracker on a camera-app screenshot (strips the app chrome)."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scanner.pipeline import Tracker, Config
from scanner import textprior as T

ROOT = Path(__file__).resolve().parents[1]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--image", default=str(ROOT / "data" / "real_exam.jpg"))
    ap.add_argument("--out", default="out/exam")
    ap.add_argument("--top", type=int, default=60)
    ap.add_argument("--bottom", type=int, default=2320)
    ap.add_argument("--frames", type=int, default=8)
    args = ap.parse_args()

    out = ROOT / args.out
    out.mkdir(parents=True, exist_ok=True)

    img = cv2.imread(args.image)
    crop = img[args.top:args.bottom, :]
    h, w = crop.shape[:2]
    print(f"frame {w}x{h} (aspect {w / h:.2f})")

    tr = Tracker(Config())
    for i in range(args.frames):
        st = tr.step(crop, 0.0)
        print(f"  frame {i}: tier={str(st['tier']):4} mode={st['mode']:<7} conf={st['conf']:<7} "
              f"ev={st['evidence']} score={st['score']:.2f} state={st['state']}")
        if st["quad"] is not None and i == args.frames - 1:
            sx, sy = w / tr.cur_aw, h / tr.cur_ah
            q = np.column_stack([st["quad"][:, 0] * sx, st["quad"][:, 1] * sy]).astype(int)
            vis = crop.copy()
            color = (255, 190, 60) if st["mode"] == "content" else (0, 220, 90)
            cv2.polylines(vis, [q], True, color, 6)
            cv2.putText(vis, f"{st['tier']} {st['mode']}", (30, 70),
                        cv2.FONT_HERSHEY_SIMPLEX, 1.6, (0, 0, 0), 6, cv2.LINE_AA)
            cv2.putText(vis, f"{st['tier']} {st['mode']}", (30, 70),
                        cv2.FONT_HERSHEY_SIMPLEX, 1.6, color, 3, cv2.LINE_AA)
            cv2.imwrite(str(out / "overlay.png"), cv2.resize(vis, (w // 2, h // 2)))

    tw = min(1600, w)
    th = int(round(h * tw / w))
    small = cv2.resize(crop, (tw, th), interpolation=cv2.INTER_AREA)
    loc = T.locate(small)
    if loc is None:
        print("  text block: NOT FOUND")
        return
    content = T.enhance(T.content_crop(loc, margin=0.03))
    cv2.imwrite(str(out / "content.png"), content)
    print(f"  text block: bbox={loc['rbbox']} angle={loc['angle']:.1f} -> content {content.shape[1]}x{content.shape[0]}")


if __name__ == "__main__":
    main()
