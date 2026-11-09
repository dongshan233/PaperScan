"""How does text density on the sheet affect detection?

Runs both stages on the same synthetic capture at several text densities:
  - classical edge detector (DocDetector/python pipeline)
  - DocAligner fastvit_t8 corner model
"""
from __future__ import annotations

import sys
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from scanner import scene as S                      # noqa: E402
from scanner.pipeline import Tracker, Config        # noqa: E402

MODEL = ROOT / "models" / "docaligner_fastvit_t8.onnx"
OUT = ROOT / "out" / "density"
DENSITIES = (1.0, 0.6, 0.35, 0.15, 0.0)
DESKS = (("wood", 0.38), ("white", 0.30))


def model_peaks(sess, bgr):
    x = cv2.resize(bgr, (256, 256)).astype(np.float32)
    x = np.ascontiguousarray(np.transpose(x, (2, 0, 1))[None] / 255.0)
    hm = sess.run(None, {"img": x})[0][0]
    return hm.max(axis=(1, 2))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    sess = ort.InferenceSession(str(MODEL), providers=["CPUExecutionProvider"])
    tr = Tracker(Config())

    print(f"{'desk':<6}{'text%':>7}{'ink':>8}{'classical':>12}{'support':>9}"
          f"{'model peaks':>26}{'model ok':>9}")
    for desk, shadow in DESKS:
        for d in DENSITIES:
            sc = S.Scene(desk=desk, shadow=shadow, text_density=d)
            frame = sc.render(68)
            gt = sc.quad(68)

            small = cv2.resize(frame, (480, 360), interpolation=cv2.INTER_AREA)
            gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
            st = tr.step(frame, 0.0)          # full pipeline incl. escalation
            det = tr.detect(gray, low=False)

            peaks = model_peaks(sess, frame)
            ok = int((peaks > 0.30).sum())
            ink = int((gray < 120).sum())

            name = f"{desk}"
            cls = "hit" if det[0] is not None else "-"
            sup = f"{det[1][0]:.2f}" if det[1] else "-"
            pk = "[" + ",".join(f"{v:.2f}" for v in peaks) + "]"
            print(f"{name:<6}{d * 100:>6.0f}%{ink:>8}{cls:>12}{sup:>9}{pk:>26}{ok:>7}/4")

            cv2.imwrite(str(OUT / f"{desk}_{int(d * 100):03d}.jpg"), frame)


if __name__ == "__main__":
    main()
