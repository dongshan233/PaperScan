"""Build the low-contrast explainer figure: frame / strict edges / permissive edges."""
from __future__ import annotations

import sys
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scanner import scene as S
from scanner import page

OUT = Path(__file__).resolve().parents[1] / "out" / "contrast"


def panel(img, w, h):
    ih, iw = img.shape[:2]
    k = min(w / iw, h / ih)
    r = cv2.resize(img, (int(iw * k), int(ih * k)), interpolation=cv2.INTER_AREA)
    tile = np.full((h, w, 3), 22, np.uint8)
    y0, x0 = (h - r.shape[0]) // 2, (w - r.shape[1]) // 2
    tile[y0:y0 + r.shape[0], x0:x0 + r.shape[1]] = r
    return tile


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    sc = S.Scene(desk="white", shadow=0.30)
    f = cv2.resize(sc.render(68), (480, 360), interpolation=cv2.INTER_AREA)
    g = cv2.cvtColor(f, cv2.COLOR_BGR2GRAY)
    gb = cv2.GaussianBlur(g, (5, 5), 0)
    norm = cv2.divide(gb, cv2.GaussianBlur(gb, (0, 0), 25), scale=128)
    e35 = cv2.Canny(norm, 35, 105)
    e8 = cv2.Canny(norm, 8, 24)

    tiles = [f, cv2.cvtColor(e35, cv2.COLOR_GRAY2BGR), cv2.cvtColor(e8, cv2.COLOR_GRAY2BGR)]
    caps = ["白桌 + 接触阴影（边框灰阶差 ≈21）",
            "Canny(35,105)：纸边 0 像素",
            "Canny(8,24)：能看见，但边缘断裂"]
    pw, ph, cap = 600, 450, 46
    canvas = Image.new("RGB", (pw * 3 + 32, ph + cap), (22, 22, 26))
    d = ImageDraw.Draw(canvas)
    for i, (t, c) in enumerate(zip(tiles, caps)):
        x = i * (pw + 16)
        canvas.paste(Image.fromarray(cv2.cvtColor(panel(t, pw, ph), cv2.COLOR_BGR2RGB)), (x, 0))
        d.text((x + 10, ph + 12), c, font=page.font("regular", 22), fill=(235, 235, 235))
    canvas.save(OUT / "explain.png")
    print("wrote", OUT / "explain.png")


if __name__ == "__main__":
    main()
