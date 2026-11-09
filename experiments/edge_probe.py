"""Probe: which edge map still finds the sheet when the desk is white?

Run:  python experiments/edge_probe.py
"""
from __future__ import annotations

import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scanner import scene as S
from scanner.pipeline import order_quad, poly_area, edge_support

AW, AH = 480, 360


def frame_at(sc, t, shadow=None):
    f = sc.render(t)
    gt = sc.quad(t) * (AW / S.FW)
    return cv2.resize(f, (AW, AH), interpolation=cv2.INTER_AREA), gt


def quads_from_edges(edges, shape, min_area=0.16, min_sup=0.40):
    h, w = shape
    e = cv2.morphologyEx(edges, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
    ed = cv2.dilate(e, np.ones((5, 5), np.uint8))
    cnts, _ = cv2.findContours(e, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    best, best_s = None, 0.0
    for c in cnts:
        if cv2.contourArea(c) < 0.10 * w * h:
            continue
        peri = cv2.arcLength(c, True)
        ap = cv2.approxPolyDP(c, 0.02 * peri, True)
        if len(ap) != 4 or not cv2.isContourConvex(ap):
            continue
        q = order_quad(ap)
        ar = poly_area(q) / (w * h)
        if ar < min_area:
            continue
        sup = edge_support(q, ed)
        if sup < min_sup:
            continue
        s = 0.55 * sup + 0.45 * min(ar / 0.55, 1)
        if s > best_s:
            best, best_s = (q, sup, ar), s
    return best


def method_maps(bgr):
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    g = cv2.GaussianBlur(gray, (5, 5), 0)
    bg = cv2.GaussianBlur(g, (0, 0), 25)
    norm = cv2.divide(g, bg, scale=128)

    gx = cv2.Sobel(g, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(g, cv2.CV_32F, 0, 1, ksize=3)
    mag = cv2.magnitude(gx, gy)
    loc = cv2.GaussianBlur(mag, (0, 0), 12) + 1e-3
    rel = mag / loc

    lab = cv2.cvtColor(bgr, cv2.COLOR_BGR2LAB).astype(np.float32)
    ch = []
    for i in (1, 2):
        c = cv2.GaussianBlur(lab[..., i], (0, 0), 2)
        cx = cv2.Sobel(c, cv2.CV_32F, 1, 0, ksize=3)
        cy = cv2.Sobel(c, cv2.CV_32F, 0, 1, ksize=3)
        ch.append(cv2.magnitude(cx, cy))
    chroma = np.maximum(ch[0], ch[1])

    return {
        "canny35": cv2.Canny(norm, 35, 105),
        "canny10": cv2.Canny(norm, 10, 28),
        "canny5": cv2.Canny(norm, 5, 16),
        "rel1.4": ((rel > 1.4) & (mag > 4)).astype(np.uint8) * 255,
        "rel1.8": ((rel > 1.8) & (mag > 6)).astype(np.uint8) * 255,
        "chroma": ((chroma > np.percentile(chroma, 99.0))).astype(np.uint8) * 255,
    }


def contrast_of(bgr, gt):
    """Local gray step across the paper border (inside minus outside), not a
    global page-vs-corner comparison, which would mostly measure lighting."""
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY).astype(np.float32)
    h, w = gray.shape
    mask = np.zeros((h, w), np.uint8)
    cv2.fillPoly(mask, [np.round(gt).astype(np.int32)], 1)
    vals_in, vals_out = [], []
    for i in range(4):
        p, q = gt[i], gt[(i + 1) % 4]
        d = (q - p) / (np.linalg.norm(q - p) + 1e-6)
        n = np.array([-d[1], d[0]], np.float32)
        for t in np.linspace(0.15, 0.85, 9):
            m = p + (q - p) * t
            for s in (3, 5, 7):
                for sign in (1, -1):
                    x, y = m + n * s * sign
                    xi, yi = int(round(x)), int(round(y))
                    if 0 <= yi < h and 0 <= xi < w:
                        (vals_in if mask[yi, xi] else vals_out).append(gray[yi, xi])
    if not vals_in or not vals_out:
        return 0.0
    return float(np.median(vals_in) - np.median(vals_out))


def main():
    scenarios = [("wood", 0.38), ("gray", 0.30), ("white", 0.30),
                 ("white", 0.12), ("white", 0.0), ("purewhite", 0.0)]
    rows = []
    for kind, shadow in scenarios:
        sc = S.Scene(desk=kind, shadow=shadow)
        for t in (35, 68):
            bgr, gt = frame_at(sc, t)
            d = contrast_of(bgr, gt)
            for name, em in method_maps(bgr).items():
                r = quads_from_edges(em, (AH, AW))
                if r is None:
                    rows.append((kind, shadow, d, t, name, None, None, None))
                    continue
                q, sup, ar = r
                err = float(np.linalg.norm(q - gt, axis=1).mean())
                rows.append((kind, shadow, d, t, name, err, sup, ar))

    print(f"{'desk':<10}{'shad':>5}{'contrast':>9}{'t':>4} {'method':<9}{'err(px)':>9}{'support':>9}{'area':>7}")
    for kind, sh, d, t, name, err, sup, ar in rows:
        e = "  FAIL  " if err is None else f"{err:7.2f}"
        s = "     - " if sup is None else f"{sup:8.2f}"
        a = "     - " if ar is None else f"{ar:6.2f}"
        print(f"{kind:<10}{sh:>5.2f}{d:>9.1f}{t:>4} {name:<9}{e:>9}{s:>9}{a:>7}")

    # save the settled frame of each scenario for eyeballing
    out = Path(__file__).parent.parent / "out" / "contrast"
    out.mkdir(parents=True, exist_ok=True)
    for kind, shadow in scenarios:
        sc = S.Scene(desk=kind, shadow=shadow)
        bgr, _ = frame_at(sc, 68)
        cv2.imwrite(str(out / f"desk_{kind}.png"), bgr)
        for name, em in method_maps(bgr).items():
            cv2.imwrite(str(out / f"edge_{kind}_{name.replace('.', '_')}.png"), em)
    print(f"\nwrote {out}")


if __name__ == "__main__":
    main()
