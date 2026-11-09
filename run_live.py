"""Live webcam demo of the same pipeline (no synthetic scene).

    python run_live.py --cam 0            # ESC to quit, SPACE to force a shot

Aim the camera at any sheet of paper. With no IMU available the stability gate
falls back to corner-velocity only, which is exactly what the Android build does
on devices without a gyro sample in the analysis callback.
"""
from __future__ import annotations

import argparse
import time
from collections import deque
from pathlib import Path

import cv2
import numpy as np

from scanner.pipeline import Tracker, Config
from scanner import viz
from scanner.capture import capture_scan


def open_cam(index: int, w: int, h: int):
    backend = getattr(cv2, "CAP_DSHOW", 0)
    cap = cv2.VideoCapture(index, backend)
    if not cap.isOpened():
        cap = cv2.VideoCapture(index)
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, w)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, h)
    return cap


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cam", type=int, default=0)
    ap.add_argument("--width", type=int, default=1280)
    ap.add_argument("--height", type=int, default=960)
    ap.add_argument("--out", default="live_out")
    args = ap.parse_args()

    out = Path(args.out)
    out.mkdir(exist_ok=True)

    cap = open_cam(args.cam, args.width, args.height)
    if not cap.isOpened():
        raise SystemExit(f"cannot open camera {args.cam}")

    tr = Tracker(Config())
    ring: deque = deque(maxlen=20)
    dt_ema, shot, last = 1 / 30, 0, 0.0

    def shoot(reason: str):
        nonlocal shot
        if not ring:
            return
        best = max(ring, key=lambda r: r[2])          # best-scoring recent frame
        frame_b, quad_a, score = best
        sx = frame_b.shape[1] / tr.cfg.aw
        sy = frame_b.shape[0] / tr.cfg.ah
        q = np.column_stack([quad_a[:, 0] * sx, quad_a[:, 1] * sy])
        scan, _, met = capture_scan(frame_b, q)
        shot += 1
        path = out / f"scan_{shot:02d}.png"
        cv2.imwrite(str(path), scan)
        print(f"[{reason}] saved {path}  score={score:.2f}  "
              f"clarity {met['clarity_before']:.0f} -> {met['clarity_after']:.0f}")

    while True:
        ok, frame = cap.read()
        if not ok:
            break
        t0 = time.perf_counter()
        st = tr.step(frame, 0.0)                      # no IMU on desktop
        dt_ema = 0.9 * dt_ema + 0.1 * (time.perf_counter() - t0)

        if st["quad"] is not None:
            ring.append((frame.copy(), st["quad"].copy(), st["score"]))

        sx = frame.shape[1] / tr.cfg.aw
        sy = frame.shape[0] / tr.cfg.ah
        vis = viz.draw_hud(frame, st, scale=(sx, sy),
                           note=f"{1000 * dt_ema:.1f} ms/frame" + (f"  已拍 {shot} 张" if shot else ""))
        cv2.imshow("document auto-capture  [ESC quit / SPACE shoot]", vis)

        if st["triggered"]:
            shoot("auto")
        key = cv2.waitKey(1) & 0xFF
        if key == 27:
            break
        if key == 32:
            shoot("manual")

    cap.release()
    cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
