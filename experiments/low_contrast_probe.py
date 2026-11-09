"""Is the sheet border still *locatable* when the desk is white?

Measures, per scenario:
  step      local gray step across the border
  canny35   % of border samples that land on a Canny(35,105) pixel   (baseline)
  canny5    same with a permissive Canny(5,16)
  lines     % of border covered by detected line segments (LSD or Hough)
  tex       local texture energy outside / inside  (material cue)
"""
from __future__ import annotations

import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scanner import scene as S
from experiments.edge_probe import frame_at, contrast_of

AW, AH = 480, 360
SCENARIOS = [("wood", 0.38), ("gray", 0.30), ("white", 0.30), ("white", 0.08), ("white", 0.0)]


def border_samples(gt, n=60, step=1.0):
    pts = []
    for i in range(4):
        p, q = gt[i], gt[(i + 1) % 4]
        L = np.linalg.norm(q - p)
        for t in np.linspace(0, 1, max(int(L / step), n)):
            pts.append(p + (q - p) * t)
    return np.array(pts, np.float32)


def coverage_by_map(pts, emap, tol=2.0):
    h, w = emap.shape
    hit = 0
    for x, y in pts:
        xi, yi = int(round(x)), int(round(y))
        x0, x1 = max(0, xi - 2), min(w, xi + 3)
        y0, y1 = max(0, yi - 2), min(h, yi + 3)
        if emap[y0:y1, x0:x1].any():
            hit += 1
    return hit / len(pts)


def _pt_seg_dist(P, A, B):
    """Distance from points P (N,2) to segments A-B (M,2) -> (N,M)."""
    d = B - A
    L2 = np.maximum((d ** 2).sum(1), 1e-9)
    t = np.clip(((P[:, None, :] - A[None]) * d[None]).sum(2) / L2[None], 0, 1)
    proj = A[None] + t[..., None] * d[None]
    return np.linalg.norm(P[:, None, :] - proj, axis=2)


def detect_segments(gray):
    if hasattr(cv2, "createLineSegmentDetector"):
        try:
            lsd = cv2.createLineSegmentDetector(cv2.LSD_REFINE_STD)
            seg = lsd.detect(gray)[0]
            if seg is not None:
                return seg.reshape(-1, 2, 2)
        except Exception:
            pass
    g = cv2.GaussianBlur(gray, (3, 3), 0)
    e = cv2.Canny(g, 8, 24)
    lines = cv2.HoughLinesP(e, 1, np.pi / 360, threshold=25, minLineLength=60, maxLineGap=6)
    if lines is None:
        return np.zeros((0, 2, 2), np.float32)
    return lines.reshape(-1, 2, 2)


def segment_coverage(pts, segs, tol=3.0):
    if len(segs) == 0:
        return 0.0
    d = _pt_seg_dist(pts, segs[:, 0], segs[:, 1])
    return float((d.min(1) < tol).mean())


def texture_ratio(bgr, gt):
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY).astype(np.float32)
    hp = np.abs(gray - cv2.GaussianBlur(gray, (0, 0), 3))
    mask = np.zeros(gray.shape, np.uint8)
    cv2.fillPoly(mask, [np.round(gt).astype(np.int32)], 1)
    inner = cv2.erode(mask, np.ones((25, 25), np.uint8))
    outer = cv2.dilate(mask, np.ones((19, 19), np.uint8)) - cv2.dilate(mask, np.ones((41, 41), np.uint8))
    a = float(hp[inner > 0].mean()) if inner.any() else 1e-6
    b = float(hp[outer > 0].mean()) if outer.any() else 1e-6
    return b / max(a, 1e-6)


def main():
    print(f"{'desk':<7}{'shadow':>7}{'step':>7}{'canny35':>9}{'canny5':>8}{'lines':>8}{'tex_out/in':>11}")
    for kind, sh in SCENARIOS:
        sc = S.Scene(desk=kind, shadow=sh)
        bgr, gt = frame_at(sc, 68)
        gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
        g = cv2.GaussianBlur(gray, (5, 5), 0)
        bg = cv2.GaussianBlur(g, (0, 0), 25)
        norm = cv2.divide(g, bg, scale=128)
        pts = border_samples(gt)

        step = contrast_of(bgr, gt)
        c35 = coverage_by_map(pts, cv2.Canny(norm, 35, 105))
        c5 = coverage_by_map(pts, cv2.Canny(norm, 5, 16))
        segs = detect_segments(gray)
        lin = segment_coverage(pts, segs)
        tex = texture_ratio(bgr, gt)
        print(f"{kind:<7}{sh:>7.2f}{step:>7.1f}{c35:>8.0%}{c5:>8.0%}{lin:>8.0%}{tex:>11.2f}")


if __name__ == "__main__":
    main()
