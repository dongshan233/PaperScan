"""High-resolution capture: re-locate corners, perspective-warp to A4, enhance."""
from __future__ import annotations

import numpy as np
import cv2

A4_W, A4_H = 1240, 1754        # 150 dpi A4


def _snap_corners(gray: np.ndarray, quad: np.ndarray) -> np.ndarray:
    """Sub-pixel corner refinement on the full-resolution frame."""
    pts = quad.reshape(-1, 1, 2).astype(np.float32)
    try:
        crit = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 40, 0.01)
        refined = cv2.cornerSubPix(gray, pts.copy(), (9, 9), (-1, -1), crit)
        out = refined.reshape(4, 2)
        if np.all(np.isfinite(out)) and cv2.isContourConvex(out.astype(np.float32)):
            delta = np.linalg.norm(out - quad, axis=1).max()
            if delta < 18.0:            # reject wild jumps
                return out.astype(np.float32)
    except cv2.error:
        pass
    return quad


def enhance(bgr: np.ndarray) -> np.ndarray:
    """Shadow removal + local contrast + mild unsharp."""
    lab = cv2.cvtColor(bgr, cv2.COLOR_BGR2LAB)
    L = lab[..., 0].astype(np.float32)
    bg = cv2.GaussianBlur(L, (0, 0), 40)
    L = np.clip(L / np.maximum(bg, 1.0) * 232.0, 0, 255)
    lab[..., 0] = L.astype(np.uint8)
    out = cv2.cvtColor(lab, cv2.COLOR_LAB2BGR)

    blur = cv2.GaussianBlur(out, (0, 0), 1.6)
    out = cv2.addWeighted(out, 1.45, blur, -0.45, 0)
    return np.clip(out, 0, 255).astype(np.uint8)


def clarity(gray: np.ndarray) -> float:
    return float(cv2.Laplacian(gray, cv2.CV_64F).var())


def capture_scan(frame_bgr: np.ndarray, quad: np.ndarray, enhance_doc: bool = True,
                 snap: bool = True):
    """Warp the quad to an upright A4 page. Returns (scan, warped_raw, metrics)."""
    gray = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2GRAY)
    q = _snap_corners(gray, quad) if snap else quad.copy()

    # sanity: keep the order [tl,tr,br,bl]
    from .pipeline import order_quad
    q = order_quad(q)

    dst = np.array([[0, 0], [A4_W - 1, 0], [A4_W - 1, A4_H - 1], [0, A4_H - 1]], np.float32)
    M = cv2.getPerspectiveTransform(q.astype(np.float32), dst)
    warped = cv2.warpPerspective(frame_bgr, M, (A4_W, A4_H),
                                 flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)

    scan = enhance(warped) if enhance_doc else warped
    g_before = cv2.cvtColor(warped, cv2.COLOR_BGR2GRAY)
    g_after = cv2.cvtColor(scan, cv2.COLOR_BGR2GRAY)

    # tilt: how much the source quad deviated from a rectangle
    e = np.array([q[(i + 1) % 4] - q[i] for i in range(4)], np.float32)
    ln = np.linalg.norm(e, axis=1)
    metrics = dict(
        corners=[[float(v) for v in p] for p in q],
        clarity_before=clarity(g_before),
        clarity_after=clarity(g_after),
        aspect_src=float(ln[1] / max(ln[0], 1e-6)),
        white_ratio=float(np.mean(g_after > 235)),
    )
    return scan, warped, metrics
