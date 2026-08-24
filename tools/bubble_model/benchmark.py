from __future__ import annotations

import argparse
import json
from pathlib import Path

from ultralytics import YOLO


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--weights", required=True)
    parser.add_argument("--data", default="bubble_types.yaml")
    parser.add_argument("--output", default="benchmark.json")
    args = parser.parse_args()

    metrics = YOLO(args.weights).val(
        data=args.data,
        split="test",
        imgsz=1024,
        plots=True,
    )
    result = {
        "box_map50": float(metrics.box.map50),
        "box_map50_95": float(metrics.box.map),
        "mask_map50": float(metrics.seg.map50),
        "mask_map50_95": float(metrics.seg.map),
        "fitness": float(metrics.fitness),
        "required_manual_checks": [
            "dialogue recall",
            "connected-group accuracy",
            "tail recall",
            "background leakage",
            "text backing",
        ],
    }
    output = Path(args.output).resolve()
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()
