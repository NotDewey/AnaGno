from __future__ import annotations

import argparse
from pathlib import Path

import onnxruntime as ort
from ultralytics import YOLO


EXPECTED_INPUT = [1, 3, 1024, 1024]
EXPECTED_CLASSES = 6
MASK_CHANNELS = 32


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--weights", required=True)
    parser.add_argument("--output", default="bubble_segment_v34.onnx")
    args = parser.parse_args()

    exported = Path(
        YOLO(args.weights).export(
            format="onnx",
            imgsz=1024,
            opset=17,
            simplify=True,
            dynamic=False,
        )
    )
    destination = Path(args.output).resolve()
    destination.write_bytes(exported.read_bytes())

    session = ort.InferenceSession(str(destination), providers=["CPUExecutionProvider"])
    input_shape = session.get_inputs()[0].shape
    if input_shape != EXPECTED_INPUT:
        raise SystemExit(f"Unexpected input shape: {input_shape}; expected {EXPECTED_INPUT}")

    outputs = session.get_outputs()
    if len(outputs) < 2:
        raise SystemExit("Segmentation model must expose prediction and prototype outputs")
    prediction_shape = outputs[0].shape
    expected_channels = 4 + EXPECTED_CLASSES + MASK_CHANNELS
    if len(prediction_shape) != 3 or prediction_shape[1] != expected_channels:
        raise SystemExit(
            f"Unexpected prediction shape: {prediction_shape}; "
            f"channel 1 must be {expected_channels}"
        )
    print(destination)


if __name__ == "__main__":
    main()
