"""Quantize DocAligner fastvit_t8 to int8 and verify accuracy/speed."""
from __future__ import annotations

import sys
import time
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort
from onnxruntime.quantization import QuantType, quantize_dynamic

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "models" / "docaligner_fastvit_t8.onnx"
DST = ROOT / "models" / "docaligner_fastvit_t8_int8.onnx"
OUT = ROOT / "out" / "quant"
THR = 0.15


def prep(bgr):
    x = cv2.resize(bgr, (256, 256)).astype(np.float32)
    return np.ascontiguousarray(np.transpose(x, (2, 0, 1))[None] / 255.0)


def peaks_of(sess, x):
    return sess.run(None, {"img": x})[0][0].max(axis=(1, 2))


def bench(sess, x, n=10):
    for _ in range(3):
        sess.run(None, {"img": x})
    t0 = time.perf_counter()
    for _ in range(n):
        sess.run(None, {"img": x})
    return (time.perf_counter() - t0) / n * 1000


def main():
    print("quantizing (dynamic int8) ...")
    t0 = time.perf_counter()
    quantize_dynamic(
        model_input=str(SRC),
        model_output=str(DST),
        per_channel=True,
        reduce_range=False,
        weight_type=QuantType.QInt8,
    )
    print(f"  done in {time.perf_counter() - t0:.1f}s")

    src_mb = SRC.stat().st_size / 1e6
    dst_mb = DST.stat().st_size / 1e6
    print(f"  size {src_mb:.1f} MB -> {dst_mb:.1f} MB  ({dst_mb / src_mb:.0%})")

    fp32 = ort.InferenceSession(str(SRC), providers=["CPUExecutionProvider"])
    fp32.io_binding()
    int8 = ort.InferenceSession(str(DST), providers=["CPUExecutionProvider"])

    cases = [
        ("white_desk", ROOT / "data" / "real_white_desk.jpg"),
        ("exam", ROOT / "data" / "real_exam.jpg"),
    ]
    sys.path.insert(0, str(ROOT))
    from scanner import scene as S
    OUT.mkdir(parents=True, exist_ok=True)
    for kind, shadow, name in (("wood", 0.38, "synth_wood"), ("white", 0.30, "synth_white")):
        p = OUT / f"_{name}.jpg"
        cv2.imwrite(str(p), S.Scene(desk=kind, shadow=shadow).render(68))
        cases.append((name, p))

    print(f"\n{'case':<12}{'fp32 peaks':<34}{'int8 peaks':<34}{'max|d|'}")
    for tag, path in cases:
        bgr = cv2.imread(str(path))
        if bgr is None:
            continue
        x = prep(bgr)
        p32 = peaks_of(fp32, x)
        p8 = peaks_of(int8, x)
        diff = float(np.abs(p32 - p8).max())
        f = lambda a: "[" + ",".join(f"{v:.2f}" for v in a) + "]"
        print(f"{tag:<12}{f(p32):<34}{f(p8):<34}{diff:.3f}")

    x = prep(cv2.imread(str(ROOT / "data" / "real_white_desk.jpg")))
    t32 = bench(fp32, x)
    t8 = bench(int8, x)
    print(f"\ninference: fp32 {t32:.1f} ms  int8 {t8:.1f} ms  speedup {t32 / t8:.1f}x")


if __name__ == "__main__":
    main()
