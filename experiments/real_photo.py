"""Run the detectors on the user's real photo (white sheet on a white desk)."""
from __future__ import annotations

import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scanner.pipeline import Tracker, Config, order_quad, poly_area, edge_support, quad_iou

IMG = Path(__file__).resolve().parents[1] / "data" / "real_white_desk.jpg"
OUT = Path(__file__).resolve().parents[1] / "out" / "real"


def label(img, text, org, color=(0, 255, 0)):
    cv2.putText(img, text, org, cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 0, 0), 4, cv2.LINE_AA)
    cv2.putText(img, text, org, cv2.FONT_HERSHEY_SIMPLEX, 1.0, color, 2, cv2.LINE_AA)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    bgr = cv2.imread(str(IMG))
    H, W = bgr.shape[:2]
    small = cv2.resize(bgr, (480, 360), interpolation=cv2.INTER_AREA)
    gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
    sx, sy = W / 480, H / 360

    for name, cfg in (("baseline", Config()), ("low_contrast", Config(low_contrast=True))):
        tr = Tracker(cfg)
        quad, meta, _ = tr.detect(gray)
        vis = bgr.copy()
        if quad is None:
            label(vis, f"{name}: NO QUAD", (40, 80), (0, 0, 255))
        else:
            q = np.column_stack([quad[:, 0] * sx, quad[:, 1] * sy]).astype(np.int32)
            cv2.polylines(vis, [q], True, (0, 255, 0), 6, cv2.LINE_AA)
            ar = poly_area(quad) / (480 * 360)
            label(vis, f"{name}: area={ar:.2f} support={meta[0]:.2f}" if meta else name, (40, 80))
        cv2.imwrite(str(OUT / f"detect_{name}.png"), cv2.resize(vis, (W // 2, H // 2)))
        print(name, "->", "None" if quad is None else np.round(quad, 1).tolist(), meta)

    # quick statistics: gray levels of the sheet area vs the desk
    g = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    print("\nimage gray: min %d max %d mean %.1f" % (g.min(), g.max(), g.mean()))
    print("percentiles", [int(np.percentile(g, p)) for p in (1, 10, 50, 90, 99)])


if __name__ == "__main__":
    main()
