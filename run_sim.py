"""Run the full pipeline over a synthetic handheld capture session.

    python run_sim.py            # renders demo.mp4 + captured scan + report
    python run_sim.py --show     # additionally open an interactive window
"""
from __future__ import annotations

import argparse
import json
import time
from collections import deque
from pathlib import Path

import cv2
import numpy as np

from scanner import scene as scene_mod
from scanner import viz
from scanner.pipeline import Tracker, Config, order_quad
from scanner.capture import capture_scan

OUT = Path(__file__).parent / "out"


def corner_error(est: np.ndarray, gt: np.ndarray) -> float:
    """Mean L2 distance between corresponding corners (order-insensitive)."""
    e = order_quad(est)
    best = float("inf")
    for k in range(4):
        g = np.roll(order_quad(gt), k, axis=0)
        best = min(best, float(np.linalg.norm(e - g, axis=1).mean()))
    return best


def draw_timeline(rows, trigger_t, path: Path):
    from PIL import Image, ImageDraw
    from scanner import page

    W, H, pad = 1000, 330, 56
    img = Image.new("RGB", (W, H), (26, 26, 30))
    d = ImageDraw.Draw(img)
    n = len(rows)

    def X(t):
        return pad + t / max(n - 1, 1) * (W - 2 * pad)

    def Y(s):
        return H - 52 - s * (H - 52 - 34)

    for g in (0.0, 0.25, 0.5, 0.75, 1.0):
        d.line([(pad, Y(g)), (W - pad, Y(g))], fill=(52, 52, 58), width=1)
        d.text((12, Y(g) - 9), f"{g:.2f}", font=page.font("regular", 16), fill=(150, 150, 158))

    # threshold
    d.line([(pad, Y(0.74)), (W - pad, Y(0.74))], fill=(255, 170, 60), width=2)
    d.text((W - pad - 92, Y(0.74) - 22), "阈值 0.74", font=page.font("regular", 17), fill=(255, 170, 60))

    pts = [(X(r["t"]), Y(r["score"])) for r in rows]
    d.line(pts, fill=(90, 230, 130), width=3)

    if trigger_t is not None:
        d.line([(X(trigger_t), 30), (X(trigger_t), H - 52)], fill=(255, 90, 90), width=2)
        d.text((X(trigger_t) + 8, 32), f"触发 t={trigger_t}", font=page.font("regular", 18), fill=(255, 120, 120))

    d.text((pad, 10), "综合稳定度评分（每帧）", font=page.font("bold", 20), fill=(235, 235, 235))
    d.text((pad, H - 30), "左侧：运动中评分低，不触发　　右侧：端稳后过线并持续，倒计时结束自动拍摄",
           font=page.font("regular", 17), fill=(180, 180, 188))
    img.save(path)


def _fit(img, pw, ph):
    ih, iw = img.shape[:2]
    k = min(pw / iw, ph / ih)
    r = cv2.resize(img, (max(1, int(iw * k)), max(1, int(ih * k))), interpolation=cv2.INTER_AREA)
    tile = np.full((ph, pw, 3), 24, np.uint8)
    y0, x0 = (ph - r.shape[0]) // 2, (pw - r.shape[1]) // 2
    tile[y0:y0 + r.shape[0], x0:x0 + r.shape[1]] = r
    return tile


def storyboard(frames, captions, path: Path):
    from PIL import Image, ImageDraw
    from scanner import page

    pw, ph, gap, cap = 620, 465, 16, 56
    W = len(frames) * pw + (len(frames) - 1) * gap
    H = ph + cap
    canvas = Image.new("RGB", (W, H), (24, 24, 28))
    d = ImageDraw.Draw(canvas)
    for i, (f, c) in enumerate(zip(frames, captions)):
        img = Image.fromarray(cv2.cvtColor(_fit(f, pw, ph), cv2.COLOR_BGR2RGB))
        x = i * (pw + gap)
        canvas.paste(img, (x, 0))
        d.text((x + 14, ph + 12), c, font=page.font("regular", 24), fill=(235, 235, 235))
    canvas.save(path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--frames", type=int, default=110)
    ap.add_argument("--show", action="store_true")
    ap.add_argument("--trace", type=int, default=5)
    args = ap.parse_args()

    OUT.mkdir(exist_ok=True)
    (OUT / "frames").mkdir(exist_ok=True)

    cfg = Config()
    sc = scene_mod.Scene(args.frames)
    tr = Tracker(cfg)
    ring: deque = deque(maxlen=18)

    vw = cv2.VideoWriter(str(OUT / "demo.mp4"), cv2.VideoWriter_fourcc(*"mp4v"),
                         30, (scene_mod.FW, scene_mod.FH))
    rows = []
    captured = None
    events = []
    step_ms = 0.0
    t0 = time.perf_counter()

    for t in range(args.frames):
        frame = sc.render(t)
        gt = sc.quad(t)
        t_step = time.perf_counter()
        st = tr.step(frame, sc.omega(t))
        step_ms += (time.perf_counter() - t_step) * 1000.0
        st["gt_err_analysis"] = (corner_error(st["quad"], gt * cfg.aw / scene_mod.FW)
                                 if st["quad"] is not None else float("nan"))

        if st["quad"] is not None:
            ring.append((t, frame, st["quad"].copy(), st["score"]))
        rows.append(dict(t=t, state=st["state"], score=round(st["score"], 3),
                         sup=round(st["comps"]["support"], 3),
                         vel=round(st["vel"], 2), omega=round(st["omega"], 2),
                         sharp=round(st["comps"]["sharp_raw"], 1),
                         err=None if st["quad"] is None else round(st["gt_err_analysis"], 2),
                         countdown=round(st["countdown"], 2)))

        if st["triggered"] and captured is None:
            # 1) prefer the best frame in the ring buffer, not "now"
            best_t, best_f, best_q, best_s = max(
                ring, key=lambda r: r[3] - 0.004 * (t - r[0]))
            coarse = best_q * (scene_mod.FW / cfg.aw)
            t_ref = time.perf_counter()
            ref_q, support = tr.refine_full(best_f, best_q)
            ref_ms = (time.perf_counter() - t_ref) * 1000.0
            use_q = ref_q if ref_q is not None else coarse
            scan, warped, met = capture_scan(best_f, use_q, snap=False)
            cv2.imwrite(str(OUT / "captured_raw.png"), warped)
            cv2.imwrite(str(OUT / "captured_scan.png"), scan)
            gt_full = sc.quad(best_t)
            captured = dict(trigger_frame=t, capture_frame=best_t,
                            ring_score=round(best_s, 3),
                            corner_err_full=round(corner_error(met["corners"], gt_full), 2),
                            corner_err_coarse=round(corner_error(coarse, gt_full), 2),
                            refine_ms=round(ref_ms, 1),
                            refine_support=[round(x, 2) for x in support],
                            corner_err_analysis=round(st["gt_err_analysis"], 2),
                            clarity_before=round(met["clarity_before"], 1),
                            clarity_after=round(met["clarity_after"], 1),
                            white_ratio=round(met["white_ratio"], 3))
            events.append((t, best_t))
            print(f"\n[CAPTURE] trigger@t={t}  use frame t={best_t}  score={best_s:.2f}  "
                  f"corner_err {captured['corner_err_coarse']}px -> {captured['corner_err_full']}px "
                  f"({ref_ms:.0f}ms refine)  "
                  f"clarity {met['clarity_before']:.0f} -> {met['clarity_after']:.0f}\n")

        vis = viz.draw_hud(frame, st, scale=scene_mod.FW / cfg.aw, note=f"t={t:03d}")
        vw.write(vis)
        if t % 2 == 0:
            cv2.imwrite(str(OUT / "frames" / f"f_{t:03d}.png"), vis)
        if args.trace and t % args.trace == 0:
            c = st["comps"]
            print(f"t={t:3d} {st['state']:<9} score={st['score']:.2f} sup={c['support']:.2f} "
                  f"area={c['area']:.2f} stab={c['stability']:.2f} sharp={c['sharp_raw']:6.1f} "
                  f"vel={st['vel']:.2f} om={st['omega']:4.1f} err={st['gt_err_analysis']:.2f}")

    vw.release()
    dt = time.perf_counter() - t0

    false_alarms = [r for r in rows if r["state"] == "CAPTURED" and r["t"] < 45]
    report = dict(config=cfg.__dict__, frames=args.frames,
                  seconds=round(dt, 2), fps=round(args.frames / dt, 1),
                  pipeline_ms_per_frame=round(step_ms / args.frames, 2),
                  captured=captured, false_alarms=len(false_alarms),
                  trigger_t=events[0][0] if events else None,
                  trace=rows)
    (OUT / "report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=1, default=float), "utf-8")

    # storyboard: search -> locked -> result
    f_search = cv2.imread(str(OUT / "frames" / "f_014.png"))
    f_lock = cv2.imread(str(OUT / "frames" / f"f_{(events[0][0] - 6) // 2 * 2:03d}.png")) if events else None
    scan = cv2.imread(str(OUT / "captured_scan.png"))
    if f_search is not None and f_lock is not None and scan is not None:
        storyboard([f_search, f_lock, scan],
                   ["① 运动中：粗定位 + 抖动大，不触发",
                    "② 稳定：评分过线，倒计时中",
                    "③ 全分辨率重定位 + 透视矫正输出"],
                   OUT / "storyboard.png")

    draw_timeline(rows, events[0][0] if events else None, OUT / "score_timeline.png")

    print("\n--- summary ---")
    print(f"frames {args.frames}  wall {dt:.2f}s  -> {args.frames / dt:.0f} fps (offline, full-res render)")
    print(json.dumps(captured, ensure_ascii=False, indent=1))
    print(f"false triggers during motion: {len(false_alarms)}")
    print(f"outputs in {OUT}")
    if args.show:
        cap = cv2.VideoCapture(str(OUT / "demo.mp4"))
        while True:
            ok, fr = cap.read()
            if not ok:
                break
            cv2.imshow("auto-capture", fr)
            if cv2.waitKey(33) == 27:
                break
        cap.release()
        cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
