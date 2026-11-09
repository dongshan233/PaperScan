"""Exercise the D1 -> D2 -> D3 escalation on both the synthetic scene and the real photo."""
from __future__ import annotations

import sys
import time
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scanner import scene as S
from scanner.pipeline import Tracker, Config

OUT = Path(__file__).resolve().parents[1] / "out" / "tiers"


def draw(bgr, quad, text, color=(0, 220, 90)):
    vis = bgr.copy()
    if quad is not None:
        q = np.round(quad).astype(int)
        cv2.polylines(vis, [q], True, color, 5)
    cv2.putText(vis, text, (20, 50), cv2.FONT_HERSHEY_SIMPLEX, 1.1, (0, 0, 0), 5, cv2.LINE_AA)
    cv2.putText(vis, text, (20, 50), cv2.FONT_HERSHEY_SIMPLEX, 1.1, color, 2, cv2.LINE_AA)
    return vis


def main():
    OUT.mkdir(parents=True, exist_ok=True)

    print("== synthetic (wood desk, strong contrast) ==")
    sc = S.Scene(desk="wood", shadow=0.38)
    frame = sc.render(68)
    tr = Tracker(Config())
    t0 = time.perf_counter()
    st = tr.step(frame, sc.omega(68))
    dt = (time.perf_counter() - t0) * 1000
    print(f"  tier={st['tier']} mode={st['mode']} conf={st['conf']} evidence={st['evidence']} "
          f"sides={st['comps'].get('sides')} {dt:.0f}ms score={st['score']:.2f}")
    cv2.imwrite(str(OUT / "synthetic.png"),
                draw(frame, st["quad"] * (frame.shape[1] / tr.cfg.aw),
                     f"{st['tier']} {st['conf']}"))

    print("\n== real photo (white sheet on white desk) ==")
    img = cv2.imread(str(Path(__file__).resolve().parents[1] / "data" / "real_white_desk.jpg"))
    tr = Tracker(Config())
    for i in range(6):
        t0 = time.perf_counter()
        st = tr.step(img, 0.0)
        dt = (time.perf_counter() - t0) * 1000
        print(f"  frame {i}: tier={st['tier']} mode={st['mode']} conf={st['conf']} "
              f"evidence={st['evidence']} miss={tr.miss} {dt:5.0f}ms score={st['score']:.2f}")
        if st["quad"] is not None:
            tag = f"{st['tier']} {st['mode']} {st['conf']}"
            vis = draw(img, st["quad"] * (img.shape[1] / tr.cfg.aw),
                       tag, (255, 170, 60) if st["mode"] == "content" else (0, 220, 90))
            cv2.imwrite(str(OUT / f"real_{i:02d}.png"), cv2.resize(vis, (886, 1182)))

    print(f"\noutputs in {OUT}")


if __name__ == "__main__":
    main()
