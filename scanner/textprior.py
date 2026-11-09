"""Text-block prior for low-contrast sheets (white paper on a white desk).

Edge detection fails when the border is 0-5 gray levels. The printed text is
~150 gray levels, so the text block is a far stronger anchor than the sheet
outline. This module:

  1. isolates glyphs (size/shape filtered connected components)
  2. picks the dominant text block and derives the page orientation
     from it (which also fixes 90-degree-rotated captures)
  3. rotates to a canonical, text-upright frame
  4. fits the page rectangle by searching margin hypotheses under the A4
     aspect constraint, scoring each against the observed seam map
     (black-hat response = the thin shadow seam at the paper edge)
  5. refines each side by a small local search, warps to A4 and enhances

The result is a quad in original-image coordinates plus diagnostics.
"""
from __future__ import annotations

from dataclasses import dataclass, field

import cv2
import numpy as np

A4_RATIO = 1.4142


# --------------------------------------------------------------------- step 1
def glyph_mask(bgr: np.ndarray):
    """Binary mask of printed glyphs, illumination-normalised and size filtered."""
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    # phone previews of a shadowed page can have text at only 20-30 gray levels;
    # local contrast equalisation brings it back into Otsu's range
    gray = cv2.createCLAHE(clipLimit=3.0, tileGridSize=(8, 8)).apply(gray)
    bg = cv2.GaussianBlur(gray, (0, 0), 41)
    norm = cv2.divide(gray, bg, scale=255)
    norm = cv2.GaussianBlur(norm, (3, 3), 0)
    _, th = cv2.threshold(norm, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)

    h, w = gray.shape
    n, lab, stats, _ = cv2.connectedComponentsWithStats(th, 8)
    dist = cv2.distanceTransform((th > 0).astype(np.uint8), cv2.DIST_L2, 5)
    cands = []
    for i in range(1, n):
        x, y, ww, hh, area = stats[i]
        if area < 40 or hh < 6 or ww < 2:
            continue
        if hh > 0.06 * h or ww > 0.10 * w:      # keyboard keys, stickers, blobs
            continue
        if ww / max(hh, 1) > 8 or ww / max(hh, 1) < 0.08:
            continue
        # printed glyphs are strokes (~0.2-0.45 fill); key caps, stickers and
        # other solid dark blobs are nearly filled rectangles
        if area / max(ww * hh, 1) > 0.78:
            continue
        # ... and a stroke is thin: its inscribed radius stays small relative to
        # its box, while a key cap is a solid slab
        if float(dist[y:y + hh, x:x + ww].max()) > 0.28 * min(ww, hh):
            continue
        cands.append((i, ww, hh))

    if not cands:
        return np.zeros_like(th), 10.0

    # second pass: keep one text scale. Keyboard keycaps are 3x the glyph
    # height, so this drops them without hurting real characters.
    med = float(np.median([c[2] for c in cands]))
    keep = np.zeros_like(th)
    for i, ww, hh in cands:
        if 0.45 * med <= hh <= 2.4 * med and ww <= 4.5 * med:
            keep[lab == i] = 255
    return keep, med


# --------------------------------------------------------------------- step 2
def _ring_stats(gray: np.ndarray, x: int, y: int, ww: int, hh: int, frac: float = 0.15):
    """Median brightness and std of a band just outside the block."""
    m = np.zeros(gray.shape, np.uint8)
    m[y:y + hh, x:x + ww] = 255
    k = max(int(max(ww, hh) * frac), 5) | 1
    band = cv2.dilate(m, np.ones((k, k), np.uint8)) - m
    v = gray[band > 0]
    if v.size == 0:
        return 0.0, 999.0
    return float(np.median(v)), float(np.std(v))


def text_block(mask: np.ndarray, glyph_h: float, gray: np.ndarray | None = None):
    """Largest glyph cluster that sits on a *bright* background.

    Printed worksheets are dark-on-white; a keyboard's key labels are
    light-on-dark. Without this check the densest cluster in the frame is
    often the keyboard, not the page.
    """
    k = max(int(glyph_h * 0.9), 3)
    merged = cv2.dilate(mask, cv2.getStructuringElement(cv2.MORPH_RECT, (k, k)))
    merged = cv2.morphologyEx(merged, cv2.MORPH_CLOSE,
                              cv2.getStructuringElement(cv2.MORPH_RECT, (int(glyph_h * 2), int(glyph_h * 2))))
    n, lab, stats, _ = cv2.connectedComponentsWithStats(merged, 8)
    order = np.argsort(stats[1:, 4])[::-1] + 1

    chosen = None
    for i in order[:8]:
        x, y, ww, hh, area = stats[i]
        if area < 2000:
            break
        if gray is not None:
            bg = gray[y:y + hh, x:x + ww]
            ink = mask[y:y + hh, x:x + ww] > 0
            vals = bg[~ink]
            if vals.size == 0 or float(np.median(vals)) < 100:
                continue                      # light-on-dark: keyboard, screen, UI
            # the block must sit on paper: a bright *and* uniform surround.
            # A keyboard's surround is bright-ish but has huge variance.
            med_bg, sd_bg = _ring_stats(gray, x, y, ww, hh)
            if med_bg < 160:
                continue
        chosen = int(i)
        break
    if chosen is None:
        return None, None, None

    blk = np.zeros_like(mask)
    blk[lab == chosen] = mask[lab == chosen]
    ys, xs = np.where(blk > 0)
    return blk, (int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max())), stats[chosen]


# --------------------------------------------------------------------- step 3
def _rot(center, angle, size):
    M = cv2.getRotationMatrix2D(center, angle, 1.0)
    cos, sin = abs(M[0, 0]), abs(M[0, 1])
    nw = int(size[1] * sin + size[0] * cos) + 8
    nh = int(size[0] * sin + size[1] * cos) + 8
    M[0, 2] += nw / 2 - center[0]
    M[1, 2] += nh / 2 - center[1]
    return M, (nw, nh)


def canonicalize(bgr, mask, blk, glyph_h):
    """Rotate so that text lines are horizontal and the text is upright.

    Two orthogonal cues: row-projection periodicity picks the 90-degree family,
    paragraph indentation (Chinese body text indents ~2 characters) picks the
    direction within that family.
    """
    pts = np.column_stack(np.where(blk > 0)[::-1]).astype(np.float32)
    base = cv2.minAreaRect(pts)[2]
    h, w = bgr.shape[:2]
    best = None
    for cand in (base, base + 90, base + 180, base + 270):
        M, size = _rot((w / 2, h / 2), cand, (w, h))
        rm = cv2.warpAffine(blk, M, size, flags=cv2.INTER_NEAREST)
        prof = rm.sum(1).astype(np.float64)
        if prof.sum() == 0:
            continue
        period = prof.std() / max(prof.mean(), 1e-6)
        indent = _indent_score(rm, glyph_h)
        score = period + 0.35 * indent
        if best is None or score > best[0]:
            best = (score, cand, M, size, period, indent)
    score, ang, M, size, period, indent = best
    rot = cv2.warpAffine(bgr, M, size, flags=cv2.INTER_LINEAR)
    rblk = cv2.warpAffine(blk, M, size, flags=cv2.INTER_NEAREST)
    ys, xs = np.where(rblk > 0)
    bbox = (int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max()))
    return rot, rblk, bbox, ang, M, size, period, indent


def _indent_score(rblk, glyph_h):
    """Count text lines that start ~2 characters in (paragraph indents)."""
    prof = rblk.sum(1)
    on = prof > max(prof.max() * 0.12, 1)
    lines, y = [], 0
    H = len(on)
    while y < H:
        if on[y]:
            y2 = y
            while y2 < H and on[y2]:
                y2 += 1
            if y2 - y >= glyph_h * 0.6:
                lines.append((y, y2))
            y = y2
        else:
            y += 1
    if len(lines) < 3:
        return 0.0
    cw = glyph_h
    indented = 0
    for y0, y1 in lines:
        cols = np.where(rblk[y0:y1].any(0))[0]
        if len(cols) == 0:
            continue
        first = cols.min()
        if 0.9 * cw < first < 3.0 * cw:
            indented += 1
    return indented / len(lines)


# --------------------------------------------------------------------- step 4
def seam_map(gray: np.ndarray, exclude: np.ndarray | None = None) -> np.ndarray:
    """Thin dark lines (the shadow seam at the paper edge), illumination free.

    Text must be excluded first: glyphs are dark thin strokes too, and would
    otherwise dominate every score.
    """
    k = 15
    bh = cv2.morphologyEx(gray, cv2.MORPH_BLACKHAT,
                          cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))
    bh = cv2.GaussianBlur(bh.astype(np.float32), (0, 0), 1.2)
    if exclude is not None:
        d = cv2.dilate(exclude, np.ones((9, 9), np.uint8))
        bh[d > 0] = 0.0
    ref = np.percentile(bh, 99.5)
    return bh / max(ref, 1e-6)


def _side_score(seam, x0, y0, x1, y1, tol=2):
    """Mean seam response along a rectangle border, tolerant to +/- tol px."""
    H, W = seam.shape
    vals = []
    xs = np.clip(np.round(np.linspace(x0, x1, 96)).astype(int), 1, W - 2)
    ys = np.clip(np.round(np.linspace(y0, y1, 96)).astype(int), 1, H - 2)
    # top / bottom (horizontal edges, y fixed)
    for yy in (int(y0), int(y1)):
        yy = int(np.clip(yy, tol, H - 2 - tol))
        band = seam[yy - tol:yy + tol + 1, :][:, xs]
        vals.append(float(band.max(0).mean()))
    # left / right (vertical edges, x fixed)
    for xx in (int(x0), int(x1)):
        xx = int(np.clip(xx, tol, W - 2 - tol))
        band = seam[:, xx - tol:xx + tol + 1][ys, :]
        vals.append(float(band.max(1).mean()))
    return float(np.mean(vals)), vals


def fit_page(seam, bbox, aspect=A4_RATIO, verbose=False):
    """Search (margin, top-gap, aspect) for the A4 rect that best explains the seams."""
    bx0, by0, bx1, by1 = bbox
    Wt, Ht = bx1 - bx0, by1 - by0
    H, W = seam.shape
    best = None
    for a in np.linspace(aspect - 0.22, aspect + 0.22, 9):
        for m in np.linspace(0.015, 0.22, 21):          # side margin / page width
            Wp = Wt / (1 - 2 * m)
            Hp = Wp * a
            if Hp < Ht * 1.02:
                continue
            x0 = bx0 - m * Wp
            x1 = x0 + Wp
            for t in np.linspace(0.0, 0.22, 12):        # top gap / page height
                y0 = by0 - t * Hp
                y1 = y0 + Hp
                if x0 < 2 or y0 < 2 or x1 > W - 3 or y1 > H - 3:
                    continue
                s, _ = _side_score(seam, x0, y0, x1, y1)
                if best is None or s > best[0]:
                    best = (s, x0, y0, x1, y1, a, m, t)
    if verbose and best:
        print("  fit: score=%.2f rect=(%.0f,%.0f)-(%.0f,%.0f) aspect=%.2f margin=%.3f top=%.3f" % best)
    return best


def refine_sides(seam, rect, span=6):
    """Snap each side to the strongest local seam ridge within +/- span px."""
    x0, y0, x1, y1 = rect
    H, W = seam.shape
    out = [x0, y0, x1, y1]

    def best_pos(fixed_axis, lo, hi, side, span):
        best, bestv = fixed_axis, -1.0
        for d in range(-span, span + 1):
            if side in ("t", "b"):
                yy = int(round(fixed_axis + d))
                if not (2 <= yy < H - 2):
                    continue
                v = seam[yy, int(lo):int(hi)].mean()
            else:
                xx = int(round(fixed_axis + d))
                if not (2 <= xx < W - 2):
                    continue
                v = seam[int(lo):int(hi), xx].mean()
            if v > bestv:
                bestv, best = v, fixed_axis + d
        return best, bestv

    out[1], s_t = best_pos(y0, x0, x1, "t", span)
    out[3], s_b = best_pos(y1, x0, x1, "b", span)
    out[0], s_l = best_pos(x0, y0, y1, "l", span)
    out[2], s_r = best_pos(x1, y0, y1, "r", span)
    return out, dict(top=s_t, bottom=s_b, left=s_l, right=s_r)


# --------------------------------------------------------------------- step 5
def run(bgr: np.ndarray, verbose=True) -> dict:
    mask, gh = glyph_mask(bgr)
    gray_src = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    blk, bbox, st = text_block(mask, gh, gray_src)
    if blk is None:
        raise RuntimeError("no paper-like text block found")
    rot, rblk, rbbox, ang, M, size, period, indent = canonicalize(bgr, mask, blk, gh)
    gray = cv2.cvtColor(rot, cv2.COLOR_BGR2GRAY)
    seam = seam_map(gray, exclude=rblk)

    fit = fit_page(seam, rbbox)
    if fit is None:
        raise RuntimeError("page fit failed")
    score, x0, y0, x1, y1, asp, m, t = fit
    rect, sides = refine_sides(seam, (x0, y0, x1, y1))

    Wp = rect[2] - rect[0]
    Hp = rect[3] - rect[1]
    if Wp <= 0 or Hp <= 0:
        raise RuntimeError("degenerate page rect")

    out_w = 1654
    out_h = int(out_w * Hp / Wp)
    src = np.array([[rect[0], rect[1]], [rect[2], rect[1]],
                    [rect[2], rect[3]], [rect[0], rect[3]]], np.float32)
    dst = np.array([[0, 0], [out_w - 1, 0], [out_w - 1, out_h - 1], [0, out_h - 1]], np.float32)
    P = cv2.getPerspectiveTransform(src, dst)
    scan = cv2.warpPerspective(rot, P, (out_w, out_h), flags=cv2.INTER_CUBIC,
                               borderMode=cv2.BORDER_REPLICATE)

    # map the page rect back to the original photo (for the overlay)
    Minv = cv2.invertAffineTransform(M)
    quad = cv2.transform(np.array([[[rect[0], rect[1]], [rect[2], rect[1]],
                                    [rect[2], rect[3]], [rect[0], rect[3]]]], np.float32), Minv)[0]

    if verbose:
        print(f"  glyph_h={gh:.1f} block={rbbox} orient={ang:.2f} "
              f"periodicity={period:.2f} indent={indent:.2f}")
        print(f"  page rect in canonical=({rect[0]:.0f},{rect[1]:.0f})-({rect[2]:.0f},{rect[3]:.0f}) "
              f"aspect={asp:.2f} seam_score={score:.2f}")
        print(f"  seam snap: " + " ".join(f"{k}={v:.2f}" for k, v in sides.items()))

    return dict(scan=scan, quad=quad, rotated=rot, rect=rect, seam=seam,
                glyph_h=gh, rbbox=rbbox, angle=ang, canvas=size, score=score,
                indent=indent)


def locate(bgr: np.ndarray) -> dict | None:
    """Cheap entry point: the text block's quad in the input image coordinates.

    No page fitting -- this is the D3 fallback of the tracker, used only when
    edge-based detection finds nothing.
    """
    mask, gh = glyph_mask(bgr)
    if gh < 5 or int(mask.sum()) == 0:
        return None
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    blk, bbox, st = text_block(mask, gh, gray)
    if blk is None or int((blk > 0).sum()) < 200:
        return None
    rot, rblk, rbbox, ang, M, size, period, indent = canonicalize(bgr, mask, blk, gh)
    x0, y0, x1, y1 = rbbox
    if x1 - x0 < 20 or y1 - y0 < 20:
        return None
    # fail loudly instead of returning a fragment: a real worksheet block is a
    # meaningful share of the frame and has several text lines
    ih, iw = bgr.shape[:2]
    if (x1 - x0) * (y1 - y0) < 0.02 * ih * iw:
        return None
    rows = (rblk.sum(1) > max(rblk.sum() / max(y1 - y0, 1) * 0.5, 1)).astype(np.uint8)
    lines = 0
    for y in range(y0, min(y1, len(rows))):
        if rows[y] and (y == 0 or not rows[y - 1]):
            lines += 1
    if lines < 3:
        return None
    box = np.array([[x0, y0], [x1, y0], [x1, y1], [x0, y1]], np.float32).reshape(-1, 1, 2)
    Minv = cv2.invertAffineTransform(M)
    quad = cv2.transform(box, Minv).reshape(4, 2).astype(np.float32)
    return dict(quad=quad, angle=ang, glyph_h=gh, rbbox=rbbox,
                period=period, indent=indent, rotated=rot)


def content_crop(loc: dict, margin: float = 0.03) -> np.ndarray:
    """Upright crop from a `locate()` result (no page fit needed)."""
    x0, y0, x1, y1 = loc["rbbox"]
    rot = loc["rotated"]
    H, W = rot.shape[:2]
    mx = int((x1 - x0) * margin)
    my = int((y1 - y0) * margin)
    return rot[max(0, y0 - my):min(H, y1 + my), max(0, x0 - mx):min(W, x1 + mx)]


def rectify_content(res: dict, margin: float = 0.02) -> np.ndarray:
    """Crop the upright text block -- the part of the page we can actually trust."""
    x0, y0, x1, y1 = res["rbbox"]
    rot = res["rotated"]
    H, W = rot.shape[:2]
    mx = int((x1 - x0) * margin)
    my = int((y1 - y0) * margin)
    crop = rot[max(0, y0 - my):min(H, y1 + my), max(0, x0 - mx):min(W, x1 + mx)]
    return crop


def enhance(bgr: np.ndarray) -> np.ndarray:
    lab = cv2.cvtColor(bgr, cv2.COLOR_BGR2LAB)
    L = lab[..., 0].astype(np.float32)
    bg = cv2.GaussianBlur(L, (0, 0), 40)
    L = np.clip(L / np.maximum(bg, 1.0) * 232.0, 0, 255)
    lab[..., 0] = L.astype(np.uint8)
    out = cv2.cvtColor(lab, cv2.COLOR_LAB2BGR)
    blur = cv2.GaussianBlur(out, (0, 0), 1.6)
    return np.clip(cv2.addWeighted(out, 1.4, blur, -0.4, 0), 0, 255).astype(np.uint8)
