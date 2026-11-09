"""Static int8 (QDQ) quantization with a calibration set of document frames."""
from __future__ import annotations

import sys
import time
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort
from onnxruntime.quantization import (
    CalibrationDataReader,
    QuantFormat,
    QuantType,
    quantize_static,
)
from onnxruntime.quantization.shape_inference import quant_pre_process

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "models" / "docaligner_fastvit_t8.onnx"
PRE = ROOT / "models" / "_fastvit_pre.onnx"
DST = ROOT / "models" / "docaligner_fastvit_t8_int8s.onnx"
OUT = ROOT / "out" / "quant"


def prep(bgr):
    x = cv2.resize(bgr, (256, 256)).astype(np.float32)
    return np.ascontiguousarray(np.transpose(x, (2, 0, 1))[None] / 255.0)


class Reader(CalibrationDataReader):
    def __init__(self, samples):
        self.samples = samples
        self.i = 0

    def get_next(self):
        if self.i >= len(self.samples):
            return None
        s = self.samples[self.i]
        self.i += 1
        return {"img": s}

    def rewind(self):
        self.i = 0


def build_samples():
    sys.path.insert(0, str(ROOT))
    from scanner import scene as S

    OUT.mkdir(parents=True, exist_ok=True)
    samples = []
    for kind, shadow in (("wood", 0.38), ("white", 0.30), ("white", 0.0), ("beige", 0.25)):
        sc = S.Scene(desk=kind, shadow=shadow)
        for t in (40, 55, 68):
            samples.append(prep(sc.render(t)))
    for name in ("real_white_desk.jpg", "real_exam.jpg"):
        p = ROOT / "data" / name
        if p.exists():
            img = cv2.imread(str(p))
            samples.append(prep(img))
            samples.append(prep(cv2.flip(img, 1)))
            samples.append(prep(cv2.convertScaleAbs(img, alpha=0.8, beta=-10)))
    return samples


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
    samples = build_samples()
    print(f"calibration samples: {len(samples)}")

    print("pre-processing (shape inference) ...")
    quant_pre_process(str(SRC), str(PRE), skip_symbolic_shape=True)

    print("static QDQ int8 ...")
    t0 = time.perf_counter()
    quantize_static(
        model_input=str(PRE),
        model_output=str(DST),
        calibration_data_reader=Reader(samples),
        quant_format=QuantFormat.QDQ,
        per_channel=True,
        activation_type=QuantType.QUInt8,
        weight_type=QuantType.QInt8,
    )
    print(f"  done in {time.perf_counter() - t0:.1f}s")

    src_mb = SRC.stat().st_size / 1e6
    dst_mb = DST.stat().st_size / 1e6
    print(f"  size {src_mb:.1f} MB -> {dst_mb:.1f} MB  ({dst_mb / src_mb:.0%})")

    fp32 = ort.InferenceSession(str(SRC), providers=["CPUExecutionProvider"])
    int8 = ort.InferenceSession(str(DST), providers=["CPUExecutionProvider"])

    cases = [("white_desk", ROOT / "data" / "real_white_desk.jpg"),
             ("exam", ROOT / "data" / "real_exam.jpg")]
    for p in sorted(OUT.glob("_synth*.jpg")):
        cases.append((p.stem.lstrip("_"), p))

    f = lambda a: "[" + ",".join(f"{v:.2f}" for v in a) + "]"
    print(f"\n{'case':<12}{'fp32':<32}{'int8':<32}{'max|d|'}")
    for tag, path in cases:
        bgr = cv2.imread(str(path))
        if bgr is None:
            continue
        x = prep(bgr)
        p32, p8 = peaks_of(fp32, x), peaks_of(int8, x)
        print(f"{tag:<12}{f(p32):<32}{f(p8):<32}{float(np.abs(p32 - p8).max()):.3f}")

    x = prep(cv2.imread(str(ROOT / "data" / "real_white_desk.jpg")))
    t32, t8 = bench(fp32, x), bench(int8, x)
    print(f"\ninference: fp32 {t32:.1f} ms  int8 {t8:.1f} ms  speedup {t32 / t8:.1f}x")


if __name__ == "__main__":
    main()
