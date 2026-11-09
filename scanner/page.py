"""Synthetic 语文 reading-comprehension worksheet renderer.

Renders an A4-ish page at 1240x1754 (150dpi) with dense Chinese body text,
a table and answer lines -- i.e. the exact hard case for edge detection:
hundreds of strong internal edges competing with the paper boundary.
"""
from __future__ import annotations

import numpy as np
import cv2
from PIL import Image, ImageDraw, ImageFont

PAGE_W, PAGE_H = 1240, 1754
MARGIN = 92

_TTF = {
    "regular": [r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\simsun.ttc"],
    "bold": [
        r"C:\Windows\Fonts\msyhbd.ttc",
        r"C:\Windows\Fonts\msyh.ttc",
        r"C:\Windows\Fonts\simhei.ttf",
    ],
}
_cache: dict = {}


def font(kind: str, size: int):
    key = (kind, size)
    if key in _cache:
        return _cache[key]
    for path in _TTF[kind]:
        try:
            f = ImageFont.truetype(path, size)
            _cache[key] = f
            return f
        except Exception:
            continue
    f = ImageFont.load_default()
    _cache[key] = f
    return f


PASSAGE = [
    "秋天的午后，我在小区的银杏树下站了很久。风一吹，金黄的叶子就一片一片地",
    "落下来，像一只只小蝴蝶，慢慢地、轻轻地，落在草地上，落在长椅上，也落在",
    "我的肩膀上。我伸出手，接住了一片。它的形状像一把小扇子，边缘有一圈细细",
    "的波浪，摸上去有一点凉，还有一点软。",
    "奶奶说，银杏树是很老的树，有的已经活了一千多年。她小时候，家门口就有",
    "一棵。每年秋天，她都会把落叶扫成一小堆，装进布袋里，做成枕头。她说，枕着",
    "银杏叶睡觉，梦里都是金黄色的。",
    "我把这片叶子夹进了书里。我想，等冬天来了，等雪落下来，我再把它拿出来看",
    "一看。那时候，它一定还是这样黄，这样轻，这样安静。",
]

QUESTIONS = [
    ("1.", "短文主要写了秋天的什么景物？作者是在哪里看到它的？"),
    ("2.", "画线句子“像一只只小蝴蝶”把什么比作了什么？这样写有什么好处？"),
    ("3.", "奶奶为什么要把银杏叶装进布袋做成枕头？"),
    ("4.", "如果你也捡到一片银杏叶，你会怎样保存它？请写一写。"),
]


def _wrap(draw, text: str, fnt, max_w: float) -> list[str]:
    lines, cur = [], ""
    for ch in text:
        if draw.textlength(cur + ch, font=fnt) <= max_w:
            cur += ch
        else:
            lines.append(cur)
            cur = ch
    if cur:
        lines.append(cur)
    return lines


def render_page() -> np.ndarray:
    """Return the worksheet as BGR uint8."""
    img = Image.new("RGB", (PAGE_W, PAGE_H), (236, 234, 229))
    d = ImageDraw.Draw(img)
    W = PAGE_W - 2 * MARGIN

    # header
    d.text((MARGIN, 70), "阅读理解（二）", font=font("bold", 46), fill=(20, 20, 20))
    d.text((PAGE_W - MARGIN - 250, 84), "三年级 · 语文", font=font("regular", 26), fill=(60, 60, 60))
    d.line([(MARGIN, 132), (PAGE_W - MARGIN, 132)], fill=(40, 40, 40), width=3)

    d.text((MARGIN, 152), "班级：____________   姓名：____________   得分：__________",
           font=font("regular", 26), fill=(40, 40, 40))

    y = 210
    d.text((MARGIN, y), "一、阅读短文，完成练习。", font=font("bold", 30), fill=(20, 20, 20))
    y += 56

    body = font("regular", 27)
    for para in PASSAGE:
        for line in _wrap(d, para, body, W):
            d.text((MARGIN, y), line, font=body, fill=(30, 30, 30))
            y += 46
        y += 8

    # table (答题区) -- straight lines that compete with the page border
    y += 10
    cols = [150, 700, 140]
    rows = [56, 74, 74, 74]
    x0, y0 = MARGIN, y
    total_w = sum(cols)
    d.rectangle([x0, y0, x0 + total_w, y0 + sum(rows)], outline=(30, 30, 30), width=2)
    cy = y0
    for r, rh in enumerate(rows):
        cx = x0
        for c, cw in enumerate(cols):
            if r > 0 or c > 0:
                d.line([(cx, cy), (cx, cy + rh)], fill=(30, 30, 30), width=2)
            cx += cw
        cy += rh
        if r < len(rows) - 1:
            d.line([(x0, cy), (x0 + total_w, cy)], fill=(30, 30, 30), width=2)
    head = ["题号", "答案要点", "得分"]
    cx = x0
    for c, cw in enumerate(cols):
        d.text((cx + 18, y0 + 12), head[c], font=font("bold", 24), fill=(30, 30, 30))
        cx += cw

    y = y0 + sum(rows) + 40
    d.text((MARGIN, y), "二、回答问题。", font=font("bold", 30), fill=(20, 20, 20))
    y += 56
    qf = font("regular", 26)
    for num, q in QUESTIONS:
        lines = _wrap(d, q, qf, W - 60)
        for i, line in enumerate(lines):
            d.text((MARGIN + (0 if i == 0 else 46), y), (num + " " if i == 0 else "") + line,
                   font=qf, fill=(30, 30, 30))
            y += 42
        y += 30
        d.line([(MARGIN + 46, y + 12), (PAGE_W - MARGIN - 30, y + 12)], fill=(120, 120, 120), width=2)
        y += 60

    d.text((PAGE_W // 2 - 40, PAGE_H - 60), "— 1 —", font=font("regular", 22), fill=(90, 90, 90))

    arr = np.asarray(img)
    return cv2.cvtColor(arr, cv2.COLOR_RGB2BGR)


if __name__ == "__main__":
    p = render_page()
    cv2.imwrite("page_preview.png", p)
    print("wrote page_preview.png", p.shape)
