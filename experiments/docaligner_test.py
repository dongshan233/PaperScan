"""DocAligner lcnet100 (ONNX) — faithful port of the official preprocessing/postprocessing."""
from __future__ import annotations

import sys
import time
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / "models" / "docaligner_lcnet100.onnx"
OUT = ROOT / "out" / "docaligner"
THR = 0.30
SIZE = 256


def infer(sess, bgr):
    h, w = bgr.shape[:2]
    x = cv2.resize(bgr, (SIZE, SIZE), interpolation=cv2.INTER_LINEAR).astype(np.float32)
    x = np.transpose(x, (2, 0, 1))[None] / 255.0          # BGR, /255, no mean/std
    hm = sess.run(None, {"img": x})[0][0]                # (4,128,128)

    pts = []
    for c in range(4):
        m = cv2.resize(hm[c], (w, h), interpolation=cv2.INTER_LINEAR)
        mask = (m >= THR).astype(np.uint8)
        if mask.sum() == 0:
            continue
        n, lab, stats, cent = cv2.connectedComponentsWithStats(mask, 8)
        if n < 2:
            continue
        i = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
        pts.append((float(cent[i][0]), float(cent[i][1])))
    return pts, hm


def order_quad(pts):
    pts = np.array(pts, np.float32)
    c = pts.mean(0)
    ang = np.arctan2(pts[:, 1] - c[1], pts[:, 0] - c[0])
    pts = pts[np.argsort(ang)]
    k = int(np.argmin(pts.sum(1)))
    return np.roll(pts, -k, axis=0)


def run(sess, img_path: Path, tag: str):
    bgr = cv2.imread(str(img_path))
    if bgr is None:
        print("skip", img_path)
        return
    h, w = bgr.shape[:2]
    t0 = time.perf_counter()
    pts, hm = infer(sess, bgr)
    ms = (time.perf_counter() - t0) * 1000
    peaks = [round(float(hm[c].max()), 3) for c in range(4)]

    vis = bgr.copy()
    if len(pts) == 4:
        q = order_quad(pts)
        cv2.polylines(vis, [q.astype(np.int32)], True, (0, 220, 90), 6)
        for p in q:
            cv2.circle(vis, (int(p[0]), int(p[1])), 10, (0, 220, 90), -1)
        # fill ratio of the quad vs frame
        area = cv2.contourArea(q) / (w * h)
        print(f"{tag:<16} 4/4  peaks={peaks}  area={area:.2f}  {ms:.0f} ms")
    else:
        print(f"{tag:<16} {len(pts)}/4 peaks={peaks}  {ms:.0f} ms")
    OUT.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(OUT / f"{tag}.png"), cv2.resize(vis, (w // 2, h // 2)))


def main():
    sess = ort.InferenceSession(str(MODEL), providers=["CPUExecutionProvider"])
    run(sess, ROOT / "data" / "real_white_desk.jpg", "white_desk")
    run(sess, ROOT / "data" / "real_laptop.jpg", "laptop")
    run(sess, ROOT / "data" / "real_exam.jpg", "exam")

    sys.path.insert(0, str(ROOT))
    from scanner import scene as S
    OUT.mkdir(parents=True, exist_ok=True)
    for kind, shadow, name in (("wood", 0.38, "synth_wood"), ("white", 0.30, "synth_white")):
        sc = S.Scene(desk=kind, shadow=shadow)
        p = OUT / f"_{name}.jpg"
        cv2.imwrite(str(p), sc.render(68))
        run(sess, p, name)


if __name__ == "__main__":
    main()
