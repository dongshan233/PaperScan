"""Real-time document edge detection + auto-capture decision pipeline.

Mirrors the shipping design:

    frame -> gray 480p -> illumination-normalise -> Canny -> contours
          -> quadrilateral candidates -> sub-pixel line refinement
          -> temporal tracking (EMA + IoU gate)
          -> composite stability score -> state machine -> trigger
"""
from __future__ import annotations

from dataclasses import dataclass, field
from collections import deque
import numpy as np
import cv2


# --------------------------------------------------------------------- utils
def order_quad(pts: np.ndarray) -> np.ndarray:
    """Return corners as [tl, tr, br, bl] (clockwise on screen)."""
    pts = np.asarray(pts, np.float32).reshape(4, 2)
    c = pts.mean(0)
    ang = np.arctan2(pts[:, 1] - c[1], pts[:, 0] - c[0])
    pts = pts[np.argsort(ang)]
    k = int(np.argmin(pts.sum(1)))
    return np.roll(pts, -k, axis=0).astype(np.float32)


def poly_area(q: np.ndarray) -> float:
    return float(abs(cv2.contourArea(q.astype(np.float32))))


def quad_iou(a: np.ndarray, b: np.ndarray, shape=(360, 480)) -> float:
    m1 = np.zeros(shape, np.uint8)
    m2 = np.zeros(shape, np.uint8)
    cv2.fillPoly(m1, [np.round(a).astype(np.int32)], 1)
    cv2.fillPoly(m2, [np.round(b).astype(np.int32)], 1)
    inter = np.count_nonzero(m1 & m2)
    union = np.count_nonzero(m1 | m2)
    return inter / union if union else 0.0


def _line_intersect(l1, l2):
    vx1, vy1, x1, y1 = [float(v) for v in np.ravel(l1)]
    vx2, vy2, x2, y2 = [float(v) for v in np.ravel(l2)]
    den = vx1 * vy2 - vy1 * vx2
    if abs(den) < 1e-8:
        return None
    s = ((x2 - x1) * vy2 - (y2 - y1) * vx2) / den
    return np.array([x1 + s * vx1, y1 + s * vy1], np.float32)


def edge_support(quad: np.ndarray, edges_dil: np.ndarray, n: int = 56) -> float:
    """Fraction of samples along the 4 edges that land on an edge pixel."""
    h, w = edges_dil.shape
    hit = tot = 0
    for i in range(4):
        p, q = quad[i], quad[(i + 1) % 4]
        for t in np.linspace(0.04, 0.96, n):
            x, y = p + (q - p) * t
            xi, yi = int(round(x)), int(round(y))
            if 0 <= yi < h and 0 <= xi < w:
                tot += 1
                hit += 1 if edges_dil[yi, xi] else 0
    return hit / max(tot, 1)


def _refine(gray: np.ndarray, contour: np.ndarray, quad: np.ndarray) -> np.ndarray:
    """Fit a line to each edge's edge-pixels and intersect adjacent lines."""
    pts = contour.reshape(-1, 2).astype(np.float32)
    lines = []
    for i in range(4):
        p, q = quad[i], quad[(i + 1) % 4]
        d = q - p
        L = float(np.linalg.norm(d))
        if L < 24:
            return quad
        ap = pts - p
        t = np.clip((ap @ d) / (L * L), 0, 1)
        dist = np.linalg.norm(pts - (p + t[:, None] * d), axis=1)
        sel = pts[(dist < 2.5) & (t > 0.06) & (t < 0.94)]
        if len(sel) < 12:
            return quad
        vx, vy, x0, y0 = np.ravel(cv2.fitLine(sel, cv2.DIST_L2, 0, 0.01, 0.01))
        lines.append((vx, vy, x0, y0))
    out = []
    for i in range(4):
        pt = _line_intersect(lines[i], lines[(i + 1) % 4])
        if pt is None:
            return quad
        out.append(pt)
    refined = order_quad(np.array(out, np.float32))
    if not cv2.isContourConvex(refined.astype(np.float32)):
        return quad
    if quad_iou(refined, quad, shape=gray.shape) < 0.75:
        return quad
    if poly_area(refined) < 0.5 * poly_area(quad):
        return quad
    return refined


def _background(gray: np.ndarray, k: int = 21, ds: int = 4) -> np.ndarray:
    """Large-radius blur via a pyramid: sigma-25 Gaussian costs ~20 ms at 480p,
    this is equivalent for background estimation and ~10x faster."""
    h, w = gray.shape
    small = cv2.resize(gray, (max(w // ds, 8), max(h // ds, 8)), interpolation=cv2.INTER_AREA)
    small = cv2.blur(small, (k, k))
    return cv2.resize(small, (w, h), interpolation=cv2.INTER_LINEAR)


def warp_roi(gray: np.ndarray, quad: np.ndarray, size=(240, 336)) -> np.ndarray:
    w, h = size
    dst = np.array([[0, 0], [w - 1, 0], [w - 1, h - 1], [0, h - 1]], np.float32)
    M = cv2.getPerspectiveTransform(quad.astype(np.float32), dst)
    return cv2.warpPerspective(gray, M, (w, h), flags=cv2.INTER_AREA)


def _subpixel_peak(vals: np.ndarray, i: int) -> float:
    """Parabolic interpolation of the peak at index i (returns fractional offset)."""
    if i <= 0 or i >= len(vals) - 1:
        return float(i)
    a, b, c = vals[i - 1], vals[i], vals[i + 1]
    den = 2.0 * (a - 2 * b + c)
    if abs(den) < 1e-9:
        return float(i)
    return float(i) + (a - c) / den


def refine_quad_lines(gray: np.ndarray, quad: np.ndarray, band: int = 6,
                      samples: int = 90, min_points: int = 14):
    """Snap a coarse quad to sub-pixel edges by fitting a line to each side.

    The coarse quad usually comes from a downscaled image, so its corners carry
    a few pixels of error. Here each side is re-located at full resolution by
    scanning along its normal for the gradient ridge, then adjacent lines are
    intersected. Returns (refined_quad, side_support) where side_support is the
    fraction of samples that found a ridge.
    """
    g = cv2.GaussianBlur(gray, (3, 3), 0)
    gx = cv2.Sobel(g, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(g, cv2.CV_32F, 0, 1, ksize=3)
    mag = cv2.magnitude(gx, gy)
    H, W = gray.shape

    lines, support = [], []
    for i in range(4):
        p, q = quad[i].astype(np.float32), quad[(i + 1) % 4].astype(np.float32)
        d = q - p
        L = float(np.linalg.norm(d))
        if L < 16:
            return quad, [0.0] * 4
        d = d / L
        n = np.array([-d[1], d[0]], np.float32)

        pts, hits = [], 0
        offsets = np.arange(-band, band + 1, dtype=np.float32)
        for t in np.linspace(0.05, 0.95, samples):
            base = p + d * (t * L)
            probe = base[None] + offsets[:, None] * n[None]
            xs = np.clip(np.round(probe[:, 0]).astype(int), 0, W - 1)
            ys = np.clip(np.round(probe[:, 1]).astype(int), 0, H - 1)
            vals = mag[ys, xs]
            k = int(np.argmax(vals))
            if vals[k] < 8.0:                     # flat: no edge here
                continue
            hits += 1
            off = offsets[0] + _subpixel_peak(vals, k)
            pts.append(base + n * off)
        support.append(hits / samples)
        if len(pts) < min_points:
            return quad, support + [0.0] * (4 - len(support))

        pts = np.array(pts, np.float32)
        # two-pass robust fit: drop the worst 20% of residuals
        vx, vy, x0, y0 = np.ravel(cv2.fitLine(pts, cv2.DIST_L2, 0, 0.01, 0.01))
        dirv = np.array([vx, vy], np.float32)
        resid = np.abs((pts[:, 0] - x0) * dirv[1] - (pts[:, 1] - y0) * dirv[0])
        keep = resid <= np.percentile(resid, 80)
        if keep.sum() >= min_points:
            vx, vy, x0, y0 = np.ravel(cv2.fitLine(pts[keep], cv2.DIST_L2, 0, 0.01, 0.01))
        lines.append((float(vx), float(vy), float(x0), float(y0)))

    out = []
    for i in range(4):
        pt = _line_intersect(lines[i], lines[(i + 1) % 4])
        if pt is None:
            return quad, support
        out.append(pt)
    refined = order_quad(np.array(out, np.float32))

    # sanity: convex, similar size, no wild jump
    if not cv2.isContourConvex(refined):
        return quad, support
    a0, a1 = poly_area(quad), poly_area(refined)
    if a1 <= 0 or not (0.7 < a1 / a0 < 1.3):
        return quad, support
    if np.linalg.norm(refined - quad, axis=1).max() > 0.12 * np.linalg.norm(quad[2] - quad[0]):
        return quad, support
    return refined, support


# --------------------------------------------------------------------- config
@dataclass
class Config:
    aw: int = 480
    ah: int = 360
    canny_lo: int = 35
    canny_hi: int = 105
    low_contrast: bool = False      # union of a permissive edge map + strict geometry
    min_area_ratio: float = 0.16
    min_support: float = 0.45
    ema_alpha_fast: float = 0.55
    ema_alpha_slow: float = 0.22
    iou_gate: float = 0.45
    hold_max: int = 3
    # score gates
    score_on: float = 0.74
    stable_frames: int = 6
    countdown_frames: int = 18
    cooldown_frames: int = 40
    imu_gate: float = 2.2
    # tiered detection
    escalate_after: int = 2          # D1 misses before trying D2
    escalate_text_after: int = 4     # D1+D2 misses before trying the text block
    text_every: int = 4              # D3 is expensive: run at most every N frames
    text_width: int = 1600          # glyphs need resolution: below ~1200 the mask fragments
    content_score_on: float = 0.72
    evidence_weak: float = 0.35      # side gradient below this = unsupported
    roi_margin: float = 0.22         # ROI = quad bbox grown by this fraction
    refine_band: int = 6             # +/- px searched around each side at full res


class Tracker:
    """One instance per capture session."""

    def __init__(self, cfg: Config | None = None):
        self.cfg = cfg or Config()
        self.prev: np.ndarray | None = None
        self.smooth: np.ndarray | None = None
        self.lost = 0
        self.vel = 0.0
        self.meas_sup = 0.0
        self.score = 0.0
        self.stable = 0
        self.countdown = 0
        self.cooldown = 0
        self.state = "SEARCH"
        self.frame_idx = -1
        self.miss = 0                 # consecutive frames without a D1 detection
        self.tier = None
        self.quad_mode = "page"       # mode of the quad currently being tracked
        self.last_text_quad = None
        self.last_text_frame = -10 ** 6
        self.cur_aw, self.cur_ah = self.cfg.aw, self.cfg.ah
        self._ev_idx = -99
        self._ev_val = (0, [])

    # ------------------------------------------------------------------
    def detect(self, gray: np.ndarray, low: bool = False, offset=(0, 0)):
        cfg = self.cfg
        low = low or cfg.low_contrast
        h, w = gray.shape
        g = cv2.GaussianBlur(gray, (5, 5), 0)
        bg = _background(g)
        norm = cv2.divide(g, bg, scale=128)            # kill shadows / uneven light
        edges = cv2.Canny(norm, cfg.canny_lo, cfg.canny_hi)
        if low:
            # white paper on a white desk: the border step can be 5-15 gray levels,
            # far below the standard hysteresis. Union a permissive map and let the
            # geometric checks below do the rejection.
            edges = cv2.bitwise_or(edges, cv2.Canny(norm, 8, 24))
        edges = cv2.morphologyEx(edges, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
        k = 3 if low else 5
        edges_dil = cv2.dilate(edges, np.ones((k, k), np.uint8))

        cnts, _ = cv2.findContours(edges, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
        best, best_s, best_meta = None, 0.0, None
        for c in cnts:
            if cv2.contourArea(c) < cfg.min_area_ratio * w * h * 0.55:
                continue
            peri = cv2.arcLength(c, True)
            ap = cv2.approxPolyDP(c, 0.02 * peri, True)
            if len(ap) != 4 or not cv2.isContourConvex(ap):
                continue
            quad = order_quad(ap)
            ar = poly_area(quad) / (w * h)
            if ar < cfg.min_area_ratio:
                continue
            sup = edge_support(quad, edges_dil)
            if sup < cfg.min_support:
                continue
            # a permissive edge map makes the frame border itself a strong
            # "quadrilateral"; never let that beat a real sheet
            margin = min(quad[:, 0].min(), quad[:, 1].min(),
                         w - quad[:, 0].max(), h - quad[:, 1].max()) / w
            pen = 1.0
            if ar > 0.92:
                pen *= 0.15
            if margin < 0.01:
                pen *= 0.25
            cand = (0.55 * sup + 0.45 * min(ar / 0.55, 1.0)) * pen
            if cand > best_s:
                best, best_s = _refine(gray, c, quad), cand
                best_meta = (sup, ar)

        if best is None:
            return None, None, edges_dil
        if offset != (0, 0):
            best = best + np.array(offset, np.float32)
        return best, best_meta, edges_dil

    @staticmethod
    def _touches_border(quad, roi, shape) -> bool:
        """A quad clipped by the ROI border is not trustworthy."""
        x0, y0, x1, y1 = roi
        return bool(quad[:, 0].min() <= x0 + 2 or quad[:, 1].min() <= y0 + 2
                    or quad[:, 0].max() >= x1 - 2 or quad[:, 1].max() >= y1 - 2)

    # ------------------------------------------------------------------ tiers
    def _roi(self, w: int, h: int):
        """Search window around the tracked quad: cheaper and far fewer decoys."""
        cfg = self.cfg
        if self.smooth is None or self.lost > cfg.hold_max:
            return None
        x0, y0 = self.smooth.min(0)
        x1, y1 = self.smooth.max(0)
        m = cfg.roi_margin * max(x1 - x0, y1 - y0)
        rx0, ry0 = int(max(0, x0 - m)), int(max(0, y0 - m))
        rx1, ry1 = int(min(w, x1 + m)), int(min(h, y1 + m))
        if rx1 - rx0 < 40 or ry1 - ry0 < 40:
            return None
        if (rx1 - rx0) * (ry1 - ry0) > 0.85 * w * h:
            return None
        return (rx0, ry0, rx1, ry1)

    def refine_full(self, frame_bgr: np.ndarray, quad=None):
        """Sub-pixel refinement of the tracked quad on the full-resolution frame."""
        qa = self.smooth if quad is None else np.asarray(quad, np.float32)
        if qa is None:
            return None, []
        fh, fw = frame_bgr.shape[:2]
        sx, sy = fw / self.cur_aw, fh / self.cur_ah
        q = np.column_stack([qa[:, 0] * sx, qa[:, 1] * sy]).astype(np.float32)
        gray = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2GRAY)
        return refine_quad_lines(gray, q, band=self.cfg.refine_band)

    def _text_quad(self, frame_bgr: np.ndarray):
        """D3: locate the printed text block (works when the sheet edge is invisible)."""
        from . import textprior

        h, w = frame_bgr.shape[:2]
        tw = min(self.cfg.text_width, w)
        th = max(int(round(h * tw / max(w, 1))), 1)
        small = cv2.resize(frame_bgr, (tw, th), interpolation=cv2.INTER_AREA)
        try:
            r = textprior.locate(small)
        except cv2.error:
            return None
        if r is None:
            return None
        sx, sy = self.cur_aw / tw, self.cur_ah / th
        return (r["quad"] * np.array([sx, sy], np.float32)).astype(np.float32)

    def _detect_tiered(self, frame_bgr, gray, roi=None):
        """D1 -> D2 -> D3, escalating only when cheaper tiers find nothing."""
        cfg = self.cfg
        if roi is not None:
            x0, y0, x1, y1 = roi
            crop = gray[y0:y1, x0:x1]
            ch, cw = crop.shape
            # keep a constant pixel budget: a small ROI is processed at a higher
            # effective resolution, which is what buys sub-pixel edge quality
            k = float(np.sqrt(self.cfg.aw * self.cfg.ah / max(cw * ch, 1)))
            k = float(np.clip(k, 0.6, 2.0))
            if 0.95 <= k <= 1.05:
                work, kx, ky = crop, 1.0, 1.0
            else:
                interp = cv2.INTER_CUBIC if k > 1 else cv2.INTER_AREA
                work = cv2.resize(crop, (max(int(cw * k), 16), max(int(ch * k), 16)),
                                  interpolation=interp)
                kx, ky = cw / work.shape[1], ch / work.shape[0]
            q, meta, _ = self.detect(work, low=False)
            if q is not None:
                q = (q * np.array([kx, ky], np.float32)
                     + np.array([x0, y0], np.float32)).astype(np.float32)
            if q is not None and not self._touches_border(q, roi, gray.shape):
                self.miss = 0
                self.used_roi = True
                return q, meta, "D1"
            self.used_roi = False
        else:
            self.used_roi = False

        q, meta, _ = self.detect(gray, low=False)
        if q is not None:
            self.miss = 0
            return q, meta, "D1"
        self.miss += 1
        if self.miss >= cfg.escalate_after:
            q, meta, _ = self.detect(gray, low=True)
            if q is not None:
                return q, meta, "D2"
        if self.miss >= cfg.escalate_text_after:
            age = self.frame_idx - self.last_text_frame
            if age >= cfg.text_every:
                tq = self._text_quad(frame_bgr)
                if tq is not None:
                    if self.last_text_quad is not None:
                        # the glyph mask can collapse or split between frames;
                        # ignore implausible jumps instead of chasing them
                        a_new, a_old = poly_area(tq), poly_area(self.last_text_quad)
                        if a_old > 1 and not (0.75 < a_new / a_old < 1.33):
                            tq = self.last_text_quad.copy()
                    self.last_text_quad = tq.copy()
                    self.last_text_frame = self.frame_idx
                    return tq, None, "D3"
            elif self.last_text_quad is not None and age <= cfg.text_every * 2:
                # coast on the last text-block result: D3 is too expensive to run
                # every frame, but the block is static while the user holds still
                return self.last_text_quad.copy(), None, "D3c"
        return None, None, None

    def _evidence(self, gray: np.ndarray, quad: np.ndarray):
        """How many of the four sides have real *paper-edge* support.

        Measured on a heavily blurred gradient: text is high frequency and would
        otherwise make every quad around a paragraph look like a perfect page.
        """
        g = _background(gray, k=9, ds=2).astype(np.float32)
        gx = cv2.Sobel(g, cv2.CV_32F, 1, 0, ksize=3)
        gy = cv2.Sobel(g, cv2.CV_32F, 0, 1, ksize=3)
        mag = cv2.magnitude(gx, gy)
        ref = float(np.percentile(mag, 95)) + 1e-6
        H, W = gray.shape
        vals = []
        for i in range(4):
            p, q = quad[i], quad[(i + 1) % 4]
            d = q - p
            L = float(np.linalg.norm(d))
            if L < 10:
                vals.append(0.0)
                continue
            d = d / L
            n = np.array([-d[1], d[0]], np.float32)
            best = 0.0
            for off in np.arange(-4.0, 4.01, 1.0):
                pts = (p + n * off)[None] + d[None] * np.linspace(0.08, 0.92, 40)[:, None] * L
                xs = np.clip(np.round(pts[:, 0]).astype(int), 0, W - 1)
                ys = np.clip(np.round(pts[:, 1]).astype(int), 0, H - 1)
                best = max(best, float(mag[ys, xs].mean()) / ref)
            vals.append(best)
        strong = sum(1 for v in vals if v > self.cfg.evidence_weak)
        return strong, [round(v, 2) for v in vals]

    # ------------------------------------------------------------------
    def step(self, frame_bgr: np.ndarray, omega: float) -> dict:
        cfg = self.cfg
        self.frame_idx += 1
        fh, fw = frame_bgr.shape[:2]
        # keep the analysis aspect: portrait phone frames are 3:4, not 4:3
        self.cur_aw, self.cur_ah = (cfg.aw, cfg.ah) if fw >= fh else (cfg.ah, cfg.aw)
        h, w = self.cur_ah, self.cur_aw
        small = cv2.resize(frame_bgr, (self.cur_aw, self.cur_ah), interpolation=cv2.INTER_AREA)
        gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)

        roi = self._roi(w, h)
        meas, meta, tier = self._detect_tiered(frame_bgr, gray, roi)
        coasted = tier == "D3c"
        if meas is not None:
            self.quad_mode = "content" if (tier or "").startswith("D3") else "page"

        # ---------------- tracking
        if meas is not None:
            self.meas_sup = float(meta[0]) if meta else 0.55
            if self.smooth is None or (self.prev is not None
                                       and quad_iou(meas, self.prev, shape=(h, w)) < cfg.iou_gate):
                self.smooth, self.vel, self.lost = meas.copy(), 0.0, 0
            else:
                # Only speed the filter up when the device is genuinely moving.
                # Low-contrast detections jitter corner by corner; a
                # velocity-adaptive filter reads that jitter as motion and
                # amplifies it into visible shake.
                moving = omega > 4.0 or self.vel > 2.5
                a = cfg.ema_alpha_fast if moving else cfg.ema_alpha_slow
                new = (a * meas + (1 - a) * self.smooth).astype(np.float32)
                self.vel = 0.6 * self.vel + 0.4 * float(np.linalg.norm(new - self.smooth, axis=1).mean())
                self.smooth = new
                self.lost = 0
            self.prev = meas.copy()
            detected = True
        else:
            self.lost += 1
            detected = False

        quad = self.smooth if (self.smooth is not None and self.lost <= cfg.hold_max) else None
        if self.smooth is not None and self.lost > cfg.hold_max:
            self.smooth = None
            self.prev = None
        mode = self.quad_mode if quad is not None else "page"

        # ---------------- scoring
        comps = dict(support=0.0, area=0.0, stability=0.0, imu=0.0,
                     sharp=0.0, geom=0.0, glare=0.0, glare_raw=0.0, margin=0.0, sharp_raw=0.0)
        raw_score = 0.0
        evidence, side_scores = 0, []
        if quad is not None:
            ar = poly_area(quad) / (w * h)
            sup = self.meas_sup if detected else self.meas_sup * 0.8
            roi = warp_roi(gray, quad)
            sharp_raw = float(cv2.Laplacian(roi, cv2.CV_64F).var())
            glare = float(np.mean(roi >= 250))
            margin = float(min(quad[:, 0].min(), quad[:, 1].min(),
                               w - quad[:, 0].max(), h - quad[:, 1].max())) / w

            stab = float(np.clip(1.0 - self.vel / 1.15, 0, 1))
            imu = float(np.clip(1.0 - omega / 2.6, 0, 1))
            sharp = float(np.clip((sharp_raw - 45.0) / 210.0, 0, 1))
            glare_pen = float(np.clip((glare - 0.004) / 0.05, 0, 1))

            if mode == "page":
                if self.frame_idx - self._ev_idx >= 3:
                    evidence, side_scores = self._evidence(gray, quad)
                    self._ev_idx = self.frame_idx
                    self._ev_val = (evidence, side_scores)
                else:
                    evidence, side_scores = self._ev_val
                area_s = np.clip((ar - 0.18) / 0.22, 0, 1) * np.clip((0.92 - ar) / 0.10, 0, 1)
                area_s = float(min(1.0, area_s * 1.6))
                geom = self._geom(quad) * float(np.clip(margin / 0.02, 0, 1))
                comps = dict(support=sup, area=area_s, stability=stab, imu=imu,
                             sharp=sharp, geom=geom, glare=glare_pen, glare_raw=glare,
                             margin=margin, sharp_raw=sharp_raw, sides=side_scores)
                raw_score = (0.26 * sup + 0.18 * area_s + 0.22 * stab + 0.10 * imu
                             + 0.16 * sharp + 0.08 * geom)
                raw_score -= 0.35 * glare_pen
                if not detected:
                    raw_score *= 0.55              # coasting on prediction
                elif coasted:
                    raw_score *= 0.92              # reused text-block result
                if ar < 0.18 or sup < 0.42:
                    raw_score *= 0.35
            else:
                # content mode: the block is a rectangle by construction, so drop
                # area/geometry gates and lean on sharpness + steadiness
                comps = dict(support=0.0, area=1.0, stability=stab, imu=imu,
                             sharp=sharp, geom=1.0, glare=glare_pen, glare_raw=glare,
                             margin=margin, sharp_raw=sharp_raw, sides=[])
                # the block ROI is text-sized, not page-sized: its Laplacian
                # variance lives on a different scale than a whole-page ROI
                sharp_c = float(np.clip((sharp_raw - 30.0) / 150.0, 0, 1))
                raw_score = (0.32 * stab + 0.16 * imu + 0.34 * sharp_c
                             + 0.18 * (1.0 - glare_pen))
                if not detected:
                    raw_score *= 0.6
                elif coasted:
                    raw_score *= 0.92
                if ar < 0.015:
                    raw_score *= 0.5
            raw_score = float(np.clip(raw_score, 0, 1))

        self.score = 0.45 * self.score + 0.55 * raw_score
        if mode == "page":
            conf = "high" if evidence >= 3 else ("medium" if evidence == 2 else "low")
        else:
            conf = "content"

        # ---------------- state machine
        if self.cooldown > 0:
            self.cooldown -= 1
        gate = (quad is not None and detected and self.score >= cfg.score_on
                and omega <= cfg.imu_gate and comps["geom"] > 0.15
                and conf != "low" and self.cooldown == 0)
        if gate:
            self.stable += 1
        else:
            self.stable = 0 if self.score < cfg.score_on - 0.18 else max(0, self.stable - 1)

        if self.stable >= cfg.stable_frames and quad is not None:
            self.countdown += 1
        else:
            self.countdown = max(0, self.countdown - 3)

        triggered = self.countdown >= cfg.countdown_frames
        if triggered:
            self.state = "CAPTURED"
        elif self.countdown > 0:
            self.state = "COUNTDOWN"
        elif self.stable > 0:
            self.state = "LOCKED"
        elif quad is not None:
            self.state = "TRACKING"
        else:
            self.state = "SEARCH"

        if triggered:                     # re-arm after a shot
            self.countdown, self.stable = 0, 0
            self.cooldown = cfg.cooldown_frames

        if self.cooldown > cfg.cooldown_frames - 10:
            self.state = "CAPTURED"

        return dict(quad=quad, score=self.score, raw=raw_score, comps=comps,
                    state=self.state, stable=self.stable,
                    countdown=self.countdown / max(cfg.countdown_frames, 1),
                    triggered=triggered, vel=self.vel, omega=omega,
                    detected=detected, idx=self.frame_idx,
                    tier=tier, mode=mode, evidence=evidence, conf=conf)

    @staticmethod
    def _geom(q: np.ndarray) -> float:
        e = np.array([q[(i + 1) % 4] - q[i] for i in range(4)], np.float32)
        ln = np.linalg.norm(e, axis=1) + 1e-6
        cos = np.array([abs(np.dot(e[i], e[(i + 1) % 4]) / (ln[i] * ln[(i + 1) % 4]))
                        for i in range(4)])
        dev = np.abs(90.0 - np.degrees(np.arccos(np.clip(cos, 0, 1)))).max()
        ratio = min(ln[0], ln[2]) / max(ln[0], ln[2]) * min(ln[1], ln[3]) / max(ln[1], ln[3])
        return float(np.clip(1 - dev / 26.0, 0, 1) * np.clip(ratio / 0.55, 0, 1))
