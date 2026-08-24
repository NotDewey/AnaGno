from __future__ import annotations

import argparse
from pathlib import Path

from ultralytics import YOLO


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", default="bubble_types.yaml")
    parser.add_argument("--base", default="yolov8n-seg.pt")
    parser.add_argument("--epochs", type=int, default=140)
    parser.add_argument("--batch", type=int, default=8)
    parser.add_argument("--device", default=None)
    args = parser.parse_args()

    data = Path(args.data).resolve()
    if not data.is_file():
        raise SystemExit(f"Dataset configuration not found: {data}")

    model = YOLO(args.base)
    model.train(
        data=str(data),
        imgsz=1024,
        epochs=args.epochs,
        batch=args.batch,
        device=args.device,
        project="runs",
        name="bubble_zoom",
        patience=30,
        close_mosaic=15,
        degrees=2.0,
        translate=0.06,
        scale=0.25,
        fliplr=0.5,
        flipud=0.0,
        cache=False,
    )


if __name__ == "__main__":
    main()
