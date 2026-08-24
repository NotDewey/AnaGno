# Bubble Zoom model workspace

This folder trains the model that replaces Kotlin-only contour guesses. It is
developer tooling and does not add manual correction to the reader.

## Dataset contract

Use YOLO segmentation labels with these classes, in this exact order:

1. `speech`
2. `thought`
3. `shout`
4. `whisper`
5. `electronic`
6. `caption`

Each mask must include the complete visible surface: body, outline, tail, and
thought dots when present. Neighboring balloons are separate instances. Record
their shared reading unit in `groups.jsonl`; this lets the benchmark distinguish
"two connected balloons" from "two balloons incorrectly merged."

```json
{"image":"page_017.png","instances":[{"label":0,"group":"g1","hasTail":true},{"label":0,"group":"g1","hasTail":true},{"label":5,"group":"g2","hasTail":false}]}
```

Recommended split: keep complete comics together. Never place pages from the
same comic in both training and test sets, otherwise the reported score will
overestimate cross-comic generalization.

## Layout

```text
dataset/
  images/{train,val,test}/
  labels/{train,val,test}/
  groups.jsonl
```

Update the dataset path in `bubble_types.yaml`, then run:

```bash
python -m pip install -r requirements.txt
python train.py --data bubble_types.yaml
python export_onnx.py --weights runs/bubble_zoom/weights/best.pt
python benchmark.py --weights runs/bubble_zoom/weights/best.pt --data bubble_types.yaml
```

`export_onnx.py` verifies the fixed 1024×1024 input and the six-class output
contract consumed by `BubbleDetector.kt`. Do not replace the Android asset until
the frozen test pages improve in segmentation mAP, boundary quality, and visual
inspection without reducing dialogue recall.
