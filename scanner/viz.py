"""Overlay rendering (shapes with OpenCV, CJK text with PIL)."""
from __future__ import annotations

import numpy as np
import cv2
from PIL import Image, ImageDraw, ImageFont

from . import page

STATE_COLOR = {
    "SEARCH": (120, 200, 255),
    "TRACKING": (60, 210, 255),
    "LOCKED": (80, 240, 120),
    "COUNTDOWN": (80, 255, 90),
    "CAPTURED": (255, 255, 255),
}


def _text(bgr, items):
    """items: [(x, y, text, size, color_bgr, bold)]"""
    img = Image.fromarray(cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB))
    d = ImageDraw.Draw(img)
    for x, y, s, size, color, bold in items:
        rgb = (int(color[2]), int(color[1]), int(color[0]))
        d.text((x, y), s, font=page.font("bold" if bold else "regular", size), fill=rgb)
    return cv2.cvtColor(np.asarray(img), cv2.COLOR_RGB2BGR)


def _panel(img, p1, p2, alpha=0.55, color=(18, 18, 18)):
    ov = img.copy()
    cv2.rectangle(ov, p1, p2, color, -1)
    cv2.addWeighted(ov, alpha, img, 1 - alpha, 0, img)


def draw_hud(frame: np.ndarray, st: dict, scale=1.0, note: str = ""):
    out = frame.copy()
    h, w = out.shape[:2]
    sx, sy = (scale, scale) if np.isscalar(scale) else scale
    quad = st.get("quad")
    color = STATE_COLOR.get(st["state"], (200, 200, 200))

    if quad is not None:
        q = np.column_stack([quad[:, 0] * sx, quad[:, 1] * sy]).astype(np.int32)
        cv2.polylines(out, [q], True, (0, 0, 0), 7, cv2.LINE_AA)
        cv2.polylines(out, [q], True, color, 3, cv2.LINE_AA)
        for p in q:
            cv2.circle(out, tuple(p), 9, (0, 0, 0), -1, cv2.LINE_AA)
            cv2.circle(out, tuple(p), 6, color, -1, cv2.LINE_AA)
        # corner brackets
        L = 54
        for i, p in enumerate(q):
            nxt = q[(i + 1) % 4]
            prv = q[(i - 1) % 4]
            for ref in (nxt, prv):
                d = ref - p
                d = d / (np.linalg.norm(d) + 1e-6) * L
                cv2.line(out, tuple(p), tuple((p + d).astype(int)), color, 4, cv2.LINE_AA)

    # ---------------- left panel
    px, py = int(26 * 1), int(24 * 1)
    pw, ph = 330, 226
    _panel(out, (px, py), (px + pw, py + ph))

    comps = st.get("comps", {})
    sc = st.get("score", 0.0)
    items = [
        (px + 18, py + 12, f"状态 {st['state']}", 26, color, True),
        (px + 18, py + 54, f"综合分 {sc:.2f}", 22, (255, 255, 255), False),
        (px + 18, py + 88, f"边缘支撑 {comps.get('support', 0):.2f}", 19, (215, 215, 215), False),
        (px + 18, py + 114, f"角点抖动 {st.get('vel', 0):.2f}px", 19, (215, 215, 215), False),
        (px + 18, py + 140, f"陀螺 {st.get('omega', 0):.1f}°/s", 19, (215, 215, 215), False),
        (px + 18, py + 166, f"清晰度 {comps.get('sharp_raw', 0):.0f}", 19, (215, 215, 215), False),
        (px + 18, py + 192, f"反光占比 {comps.get('glare_raw', 0):.3f}", 19, (215, 215, 215), False),
    ]

    # score bar
    bx, by, bw2 = px + 150, py + 52, 160
    cv2.rectangle(out, (bx, by + 6), (bx + bw2, by + 20), (70, 70, 70), -1)
    cv2.rectangle(out, (bx, by + 6), (bx + int(bw2 * sc), by + 20), color, -1)
    cv2.line(out, (bx + int(bw2 * 0.74), by), (bx + int(bw2 * 0.74), by + 26), (255, 255, 255), 1)

    # countdown ring
    if st["state"] in ("COUNTDOWN", "LOCKED"):
        cx, cy, r = w - 104, 104, 44
        cv2.circle(out, (cx, cy), r, (60, 60, 60), 6, cv2.LINE_AA)
        frac = st.get("countdown", 0)
        if frac > 0:
            cv2.ellipse(out, (cx, cy), (r, r), 0, -90, -90 + int(360 * frac), color, 6, cv2.LINE_AA)
        items.append((cx - 24, cy - 16, "拍", 30, color, True))

    # bottom hint
    hint = {"SEARCH": "把试卷完整放入取景框",
            "TRACKING": "识别中，请放稳",
            "LOCKED": "已锁定，保持不动",
            "COUNTDOWN": "保持不动，正在拍摄…",
            "CAPTURED": "拍摄完成"}.get(st["state"], "")
    tw = len(hint) * 22
    _panel(out, (w // 2 - tw // 2 - 24, h - 86), (w // 2 + tw // 2 + 24, h - 34), alpha=0.5)
    items.append((w // 2 - tw // 2, h - 80, hint, 26, (255, 255, 255), True))

    if note:
        items.append((26, h - 120, note, 22, (255, 255, 255), False))

    out = _text(out, items)

    if st["state"] == "CAPTURED":
        flash = np.full_like(out, 255)
        out = cv2.addWeighted(flash, 0.35, out, 0.65, 0)
    return out
