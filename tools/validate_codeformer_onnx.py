"""Run a converted CodeFormer ONNX model on an aligned 512x512 face."""

from __future__ import annotations

import argparse
import time
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("model", type=Path)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    image = cv2.imread(str(args.input), cv2.IMREAD_COLOR)
    if image is None:
        raise FileNotFoundError(args.input)
    image = cv2.resize(image, (512, 512), interpolation=cv2.INTER_LANCZOS4)
    rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
    tensor = ((rgb - 0.5) / 0.5).transpose(2, 0, 1)[None]

    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    started = time.perf_counter()
    session = ort.InferenceSession(
        str(args.model), sess_options=options, providers=["CPUExecutionProvider"]
    )
    if session.get_inputs()[0].type == "tensor(float16)":
        tensor = tensor.astype(np.float16)
    restored = session.run(["restored"], {"face": tensor})[0][0]
    elapsed = time.perf_counter() - started

    restored = np.clip((restored.transpose(1, 2, 0) + 1.0) * 127.5, 0, 255)
    restored = cv2.cvtColor(restored.astype(np.uint8), cv2.COLOR_RGB2BGR)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(args.output), restored)
    print(f"Saved {args.output}; load + inference: {elapsed:.2f}s")


if __name__ == "__main__":
    main()
