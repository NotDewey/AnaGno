"""Page scoring only; the reporter owns schedules, failures and corpus rates.

Mask IoUs use exact integer foreground counts. Box IoUs use exact rational
continuous areas, including unions for group scoring. mean_iou = iou_sum/GT
count, so missed instances contribute zero. Empty GT recall/mean_iou are None.
Group mode collapses specified GT members; ungrouped GT remain singleton units.
Predictions are never merged opportunistically. Groups are a separate metric.
An unmatched prediction touching any positive GT is always FP before ignores.
Otherwise >=50% of its foreground in the ignore union suppresses it.
"""

from fractions import Fraction

from .matching import maximum_matching
from .projection import BinaryMask, box_mask, dimensions, union_masks, validate_box


def mask_iou(first_region, second_region):
    if (first_region.width, first_region.height) != (second_region.width, second_region.height):
        raise ValueError("mask dimensions differ")
    intersection = sum(first_value & second_value for first_value, second_value in zip(first_region.data, second_region.data))
    union = sum(first_value | second_value for first_value, second_value in zip(first_region.data, second_region.data))
    return Fraction(intersection, union) if union else Fraction(0)


def _area(boxes):
    """Sweep x slabs and union y intervals to avoid group overlap double counts."""
    x_coordinates = sorted({first_value for box_left, _, box_right, _ in boxes for first_value in (box_left, box_right)})
    total = Fraction(0)
    for left, right in zip(x_coordinates, x_coordinates[1:]):
        spans = sorted((box_top, box_bottom)
                       for box_left, box_top, box_right, box_bottom in boxes
                       if box_left < right and box_right > left)
        end = None
        length = Fraction(0)
        for top, bottom in spans:
            if end is None or top > end:
                length += bottom-top
            elif bottom > end:
                length += bottom-end
            end = bottom if end is None else max(end, bottom)
        total += (right-left)*length
    return total


def _intersection(first_region, second_region):
    boxes = []
    for box_left, box_top, box_right, box_bottom in first_region:
        for other_left, other_top, other_right, other_bottom in second_region:
            rect = (max(box_left, other_left), max(box_top, other_top), min(box_right, other_right), min(box_bottom, other_bottom))
            if rect[0] < rect[2] and rect[1] < rect[3]:
                boxes.append(rect)
    return _area(boxes)


def evaluate_page(annotations, predictions, width, height, mode='mask',
                  threshold=.5, unit='instance', groups=None, ignore_regions=None):
    dimensions(width, height)
    if mode not in ('mask', 'box') or unit not in ('instance', 'group'):
        raise ValueError("unsupported scoring mode or unit")
    cutoff = Fraction(str(threshold))
    if not 0 <= cutoff <= 1:
        raise ValueError("IoU threshold must be in 0..1")

    def canonical(objects):
        result = {}
        for obj in objects:
            identity = obj.get('id')
            if not isinstance(identity, str) or not identity or identity in result:
                raise ValueError("IDs must be unique nonempty strings")
            box = validate_box(obj.get('box'), width, height)
            if mode == 'mask':
                mask = obj.get('mask')
                if not isinstance(mask, BinaryMask) or (mask.width, mask.height) != (width, height):
                    raise ValueError("mask mode requires full-page BinaryMask")
                result[identity] = mask
            else:
                result[identity] = [box]
        return result

    ground_truth, predicted = canonical(annotations), canonical(predictions)
    original_gt = dict(ground_truth)
    if unit == 'group':
        used, grouped = set(), {}
        for group in groups or []:
            identity, members = group.get('id'), group.get('members')
            if (not isinstance(identity, str) or not identity or identity in grouped or
                    not isinstance(members, list) or not members or
                    any(not isinstance(member_id, str) or member_id not in ground_truth or member_id in used for member_id in members) or
                    len(set(members)) != len(members)):
                raise ValueError("invalid, overlapping or unknown group members")
            used.update(members)
            grouped[identity] = (union_masks([ground_truth[member_id] for member_id in members], width, height)
                                 if mode == 'mask' else [member_box for member_id in members
                                                        for member_box in ground_truth[member_id]])
        for identity in ground_truth.keys()-used:
            if identity in grouped:
                raise ValueError("group ID collides with ungrouped instance")
            grouped[identity] = ground_truth[identity]
        ground_truth = grouped

    def overlap(first_region, second_region):
        return (sum(first_value & second_value for first_value, second_value in zip(first_region.data, second_region.data))
                if mode == 'mask' else _intersection(first_region, second_region))

    def iou(first_region, second_region):
        if mode == 'mask':
            return mask_iou(first_region, second_region)
        intersection = _intersection(first_region, second_region)
        union = _area(first_region)+_area(second_region)-intersection
        return intersection/union if union else Fraction(0)

    scores = {(annotation_id, prediction_id): iou(ground_truth[annotation_id], predicted[prediction_id])
              for annotation_id in sorted(ground_truth) for prediction_id in sorted(predicted)}
    pairs = maximum_matching(scores, cutoff)
    matched = {prediction_id for _, prediction_id, _ in pairs}
    ignore = union_masks(ignore_regions or [], width, height)
    ignored = []
    for prediction_id in sorted(predicted.keys()-matched):
        if any(overlap(positive_region, predicted[prediction_id]) > 0 for positive_region in original_gt.values()):
            continue
        foreground = (predicted[prediction_id] if mode == 'mask' else
                      union_masks([box_mask(predicted_box, width, height)
                                   for predicted_box in predicted[prediction_id]], width, height))
        count = sum(foreground.data)
        inside = sum(first_value & second_value for first_value, second_value in zip(foreground.data, ignore.data))
        if count and inside*2 >= count:
            ignored.append(prediction_id)
    total_iou = sum((iou_value for _, _, iou_value in pairs), Fraction(0))
    return {'tp': len(pairs), 'fp': len(predicted)-len(pairs)-len(ignored),
            'fn': len(ground_truth)-len(pairs), 'gt_count': len(ground_truth),
            'prediction_count': len(predicted), 'iou_sum': float(total_iou),
            'recall': len(pairs)/len(ground_truth) if ground_truth else None,
            'mean_iou': float(total_iou/len(ground_truth)) if ground_truth else None,
            'matches': [{'annotation_id': annotation_id, 'prediction_id': prediction_id, 'iou': float(iou_value)}
                        for annotation_id, prediction_id, iou_value in pairs], 'ignored_prediction_ids': ignored}
