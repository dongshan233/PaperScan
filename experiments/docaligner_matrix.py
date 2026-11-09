"""Matrix test: lcnet100 vs fastvit_t8, with/without center crop, on the hard photos."""
from __future__ import annotations

import sys
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "out" / "docaligner"
THR = 0.30
SIZE = 256
MODELS = {
    "lcnet100": ROOT / "models" / "docaligner_lcnet100.onnx",
    "fastvit_t8": ROOT / "models" / "docaligner_fastvit_t8.onnx",
}


def infer(sess, bgr, center_crop=False):
    h, w = bgr.shape[:2]
    ox, oy = 0, 0
    if center_crop:
        s = min(h, w)
        ox, oy = (w - s) // 2, (h - s) // 2
        bgr = bgr[oy:oy + s, ox:ox + s]
        h, w = bgr.shape[:2]
    x = cv2.resize(bgr, (SIZE, SIZE)).astype(np.float32)
    x = np.transpose(x, (2, 0, 1))[None] / 255.0
    hm = sess.run(None, {"img": x})[0][0]
    pts, peaks = [], []
    for c in range(4):
        m = cv2.resize(hm[c], (w, h))
        mask = (m >= THR).astype(np.uint8)
        peaks.append(round(float(hm[c].max()), 3))
        if mask.sum() == 0:
            continue
        n, lab, stats, cent = cv2.connectedComponentsWithStats(mask, 8)
        if n < 2:
            continue
        i = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
        pts.append((float(cent[i][0]) + ox, float(cent[i][1]) + oy))
    return pts, peaks


def test(sess, path, tag, center_crop):
    bgr = cv2.imread(str(path))
    h, w = bgr.shape[:2]
    pts, peaks = infer(sess, bgr, center_crop)
    if len(pts) == 4:
        q = np.array(pts, np.float32)
        area = abs(cv2.contourArea(q)) / (w * h)
        return f"{len(pts)}/4 area={area:.2f} peaks={peaks}"
    return f"{len(pts)}/4 peaks={peaks}"


def main():
    from PIL import Image, ImageDraw
    sys.path.insert(0, str(ROOT))
    from scanner import page

    scenarios = [
        ("white_desk", ROOT / "data" / "real_white_desk.jpg"),
        ("laptop", ROOT / "data" / "real_laptop.jpg"),
        ("exam", ROOT / "data" / "real_exam.jpg"),
    ]
    rows = []
    for mname, mpath in MODELS.items():
        sess = ort.InferenceSession(str(mpath), providers=["CPUExecutionProvider"])
        for tag, p in scenarios:
            for cc in (False, True):
                r = test(sess, p, tag, cc)
                rows.append((mname, tag, cc, r))
                print(f"{mname:<11} {tag:<11} crop={str(cc):<5} {r}")


if __name__ == "__main__":
    main()
