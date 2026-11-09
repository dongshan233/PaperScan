"""Synthetic camera feed: a worksheet on a wooden desk, handheld motion model.

The scene is rendered at 1280x960 ("full resolution"); the pipeline downscales
to 480x360 for analysis, exactly like the two-stream design on a phone.
Ground-truth corners + a pseudo-gyro signal are exported for validation.
"""
from __future__ import annotations

import numpy as np
import cv2

from . import page

FW, FH = 1280, 960          # full-res frame
FPS = 30


def _smoothstep(x: float) -> float:
    x = min(max(x, 0.0), 1.0)
    return x * x * (3 - 2 * x)


DESKS = {
    "wood":     (118, 142, 166),      # BGR
    "beige":    (196, 202, 206),
    "gray":     (208, 210, 209),
    "white":    (230, 232, 231),
    "purewhite": (236, 237, 236),
}


def _desk(w: int, h: int, seed: int = 7, kind: str = "wood") -> np.ndarray:
    rng = np.random.default_rng(seed)
    base = np.empty((h, w, 3), np.float32)
    base[:] = DESKS.get(kind, DESKS["wood"])
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)

    if kind == "wood":
        n = rng.normal(0, 1, (h // 16, w // 16)).astype(np.float32)
        n = cv2.resize(cv2.GaussianBlur(n, (0, 0), 1.4), (w, h))
        base += n[..., None] * 22

        grain = np.sin(yy * 0.09 + 3.0 * np.sin(xx * 0.006) + 0.7) * 0.5 + 0.5
        grain = 0.55 * grain + 0.45 * (np.sin(yy * 0.031 + 1.7) * 0.5 + 0.5)
        base *= (0.84 + 0.28 * grain)[..., None]

        for py in range(0, h, 235):
            seam = np.exp(-((yy - py) ** 2) / (2 * 1.6 ** 2)).astype(np.float32)
            base *= (1.0 - 0.22 * seam)[..., None]

        for kx, ky, r in [(w * 0.22, h * 0.71, 90), (w * 0.81, h * 0.24, 120)]:
            d2 = ((xx - kx) ** 2 + (yy - ky) ** 2) / (r * r)
            base *= (0.88 + 0.12 * np.clip(d2, 0, 1))[..., None]
    else:
        # smooth laminate / painted desk: faint low-frequency texture + fine grain
        n = rng.normal(0, 1, (h // 24, w // 24)).astype(np.float32)
        n = cv2.resize(cv2.GaussianBlur(n, (0, 0), 2.0), (w, h))
        base += n[..., None] * 4.0
        tex = np.sin(yy * 0.012 + 1.3 * np.sin(xx * 0.0045)) * 0.5 + 0.5
        base *= (0.985 + 0.03 * tex)[..., None]

    base += rng.normal(0, 2.5, base.shape).astype(np.float32)
    return np.clip(base, 0, 255).astype(np.uint8)


class Scene:
    def __init__(self, total_frames: int = 110, desk: str = "wood", shadow: float = 0.38):
        self.page = page.render_page()
        self.bg = _desk(FW, FH, kind=desk)
        self.desk = desk
        self.shadow = shadow
        self.total = total_frames
        # page-plane alpha mask (255 = paper)
        self.alpha_src = np.full((page.PAGE_H, page.PAGE_W), 255, np.uint8)

    # ---------------------------------------------------------------- motion
    def params(self, t: int):
        """(cx, cy, scale, rot_deg) for frame t -- approach, settle, hold."""
        p = _smoothstep(t / 52.0)                       # approach progress
        scale = 0.52 + 0.40 * p                         # 0.52 -> 0.92 of frame height
        cx = FW * (0.46 + 0.06 * p)
        cy = FH * (0.44 + 0.08 * p)
        rot = -7.5 * (1.0 - p) + 0.6 * np.sin(t * 0.05)

        # decaying handheld tremor
        env = (1.0 - p) ** 1.6
        jx = env * (7.0 * np.sin(t * 0.21 + 1.1) + 3.0 * np.sin(t * 0.53 + 0.4))
        jy = env * (6.0 * np.sin(t * 0.17 + 2.3) + 2.6 * np.sin(t * 0.61 + 1.9))
        cx += jx
        cy += jy
        return cx, cy, scale, rot

    def quad(self, t: int) -> np.ndarray:
        cx, cy, scale, rot = self.params(t)
        k = scale * FH / page.PAGE_H
        pts = np.array([[0, 0], [page.PAGE_W, 0], [page.PAGE_W, page.PAGE_H], [0, page.PAGE_H]],
                       np.float32) * k
        M = cv2.getRotationMatrix2D((page.PAGE_W * k / 2, page.PAGE_H * k / 2), rot, 1.0)
        pts = pts @ M[:, :2].T + M[:, 2]
        pts += np.array([cx - pts[:, 0].mean(), cy - pts[:, 1].mean()], np.float32)
        return pts.astype(np.float32)

    def omega(self, t: int) -> float:
        """Pseudo gyro magnitude in deg/s, from frame-to-frame camera motion."""
        if t == 0:
            return 0.0
        q0, q1 = self.quad(t - 1), self.quad(t)
        dc = np.linalg.norm(q1.mean(0) - q0.mean(0))
        dtheta = 0.0
        a0 = np.degrees(np.arctan2(*(q0[1] - q0[0])[::-1]))
        a1 = np.degrees(np.arctan2(*(q1[1] - q1[0])[::-1]))
        dtheta = abs(a1 - a0)
        # 1 px lateral at this distance ~ 0.06 deg of pan
        return float((dtheta + dc * 0.06) * FPS)

    # ---------------------------------------------------------------- render
    def render(self, t: int) -> np.ndarray:
        quad = self.quad(t)
        H = cv2.getPerspectiveTransform(
            np.array([[0, 0], [page.PAGE_W, 0], [page.PAGE_W, page.PAGE_H], [0, page.PAGE_H]], np.float32),
            quad,
        )

        bgra = cv2.cvtColor(self.page, cv2.COLOR_BGR2BGRA)
        warped = cv2.warpPerspective(bgra, H, (FW, FH), flags=cv2.INTER_LINEAR,
                                     borderValue=(0, 0, 0, 0))
        alpha = cv2.warpPerspective(self.alpha_src, H, (FW, FH), flags=cv2.INTER_LINEAR,
                                    borderValue=0).astype(np.float32) / 255.0

        frame = self.bg.astype(np.float32)

        # soft drop shadow cast by the sheet onto the desk (desk only)
        sh = cv2.GaussianBlur(alpha, (0, 0), 26)
        sh = np.roll(np.roll(sh, 16, axis=0), 12, axis=1) * self.shadow
        frame *= (1.0 - sh)[..., None]

        a = alpha[..., None]
        frame = frame * (1 - a) + warped[..., :3].astype(np.float32) * a

        # ambient lighting + lens vignetting: applied to the whole scene, paper
        # included, otherwise white-on-white would be artificially high contrast
        yy, xx = np.mgrid[0:FH, 0:FW].astype(np.float32)
        d2 = ((xx - FW * 0.22) ** 2 + (yy - FH * 0.12) ** 2) / (FW * FH)
        light = 0.74 + 0.24 * np.exp(-d2 * 2.2)
        vig = 1.0 - 0.30 * (((xx - FW / 2) / (FW / 2)) ** 2 + ((yy - FH / 2) / (FH / 2)) ** 2)
        frame *= (light * np.clip(vig, 0.6, 1.0))[..., None]

        # specular glare drifting across the sheet (frames 10-22), then gone
        if 8 <= t <= 24:
            gx = FW * (0.62 - 0.02 * (t - 8))
            gy = FH * 0.30
            blob = np.exp(-(((xx - gx) ** 2 + (yy - gy) ** 2) / (2 * (150 ** 2)))).astype(np.float32)
            blob *= a[..., 0]
            frame += blob[..., None] * 90.0 * np.array([0.95, 0.98, 1.0], np.float32)

        # motion blur while the hand is moving fast
        om = self.omega(t)
        if om > 2.0:
            length = int(np.clip(om * 0.9, 1, 13))
            if length >= 3:
                ker = np.zeros((length, length), np.float32)
                ker[length // 2, :] = 1.0
                ker = cv2.warpAffine(ker, cv2.getRotationMatrix2D((length / 2 - .5, length / 2 - .5), 14, 1.0),
                                     (length, length))
                ker /= max(ker.sum(), 1e-6)
                frame = cv2.filter2D(frame, -1, ker)

        # sensor noise + quantisation
        rng = np.random.default_rng(1000 + t)
        frame += rng.normal(0, 2.2, frame.shape).astype(np.float32)
        return np.clip(frame, 0, 255).astype(np.uint8)
