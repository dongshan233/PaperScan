"""End-to-end: does the auto-capture still work on a white desk?

Three configs on the same handheld sequence:
  baseline   -- D1 only (edge + contour), no escalation
  low_contrast -- D2 only (permissive edges + strict geometry)
  tiered     -- D1 -> D2 -> D3 (text block) with evidence-based mode selection

Run:  python experiments/low_contrast_run.py
"""
from __future__ import annotations

import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scanner import scene as S
from scanner.pipeline import Tracker, Config, order_quad

AW, AH = 480, 360
N = 95
SCENARIOS = [("wood", 0.38), ("white", 0.30), ("white", 0.08), ("white", 0.0)]
NO_ESC = dict(escalate_after=10 ** 6, escalate_text_after=10 ** 6)


def corner_err(est, gt):
    e, g = order_quad(est), order_quad(gt)
    return float(min(np.linalg.norm(e - np.roll(g, k, axis=0), axis=1).mean() for k in range(4)))


def render_sequence(kind, shadow):
    sc = S.Scene(desk=kind, shadow=shadow)
    frames, gts, oms = [], [], []
    for t in range(N):
        f = sc.render(t)
        frames.append(cv2.resize(f, (AW, AH), interpolation=cv2.INTER_AREA))
        gts.append(sc.quad(t) * (AW / S.FW))
        oms.append(sc.omega(t))
    return frames, gts, oms


def run(frames, gts, oms, cfg):
    tr = Tracker(cfg)
    trig, errs, dets = None, [], []
    mode = "page"
    for t in range(N):
        st = tr.step(frames[t], oms[t])
        if st["triggered"] and trig is None:
            trig = t
        if t >= 55:
            dets.append(st["quad"] is not None)
            if st["quad"] is not None:
                mode = st["mode"]
                if st["mode"] == "page":          # content quads aren't page quads
                    errs.append(corner_err(st["quad"], gts[t]))
    return dict(trigger=trig, det=float(np.mean(dets)),
                err=float(np.median(errs)) if errs else float("nan"),
                false=trig is not None and trig < 48, mode=mode)


def main():
    print(f"{'desk':<7}{'shadow':>7} {'config':<13}{'trigger':>8}{'detect%':>9}{'err(px)':>9}{'mode':>9}{'false':>7}")
    configs = [("baseline", Config(**NO_ESC)),
               ("low_contrast", Config(low_contrast=True, **NO_ESC)),
               ("tiered", Config())]
    for kind, sh in SCENARIOS:
        frames, gts, oms = render_sequence(kind, sh)
        for label, cfg in configs:
            r = run(frames, gts, oms, cfg)
            trig = "  never" if r["trigger"] is None else f"t={r['trigger']}"
            err = "    n/a" if np.isnan(r["err"]) else f"{r['err']:7.2f}"
            print(f"{kind:<7}{sh:>7.2f} {label:<13}{trig:>8}{r['det']:>8.0%}"
                  f"{err:>9}{r['mode']:>9}{'  YES' if r['false'] else '   no':>7}")
        print()


if __name__ == "__main__":
    main()
