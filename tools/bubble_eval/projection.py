"""Canonical masks and public-cutout projection.

Masks are full-page row-major bytes: exactly width*height values of 0 or 1.
Boxes are half-open original pixel coordinates. Projection samples page pixel
centers, maps into normalized cutout bounds, then uses nearest-neighbor cells.
128 means alpha >=128; use threshold=9 for the production visibility rule >8.
No clipping of invalid bounds or implicit resize is performed.
"""

from dataclasses import dataclass
from fractions import Fraction
import math

MAX_PIXELS = 64_000_000


def dimensions(width, height):
    if (type(width) is not int or type(height) is not int or
            width <= 0 or height <= 0 or width * height > MAX_PIXELS):
        raise ValueError("invalid or excessive image dimensions")


def validate_box(box, width, height, normalized=False):
    dimensions(width, height)
    if not isinstance(box, (list, tuple)) or len(box) != 4:
        raise ValueError("box must contain four coordinates")
    if any(isinstance(coordinate, bool) or not isinstance(coordinate, (int, float, Fraction))
           or not math.isfinite(coordinate) for coordinate in box):
        raise ValueError("box coordinates must be finite numbers")
    left, top, right, bottom = (Fraction(str(coordinate)) for coordinate in box)
    max_x, max_y = (1, 1) if normalized else (width, height)
    if not (0 <= left < right <= max_x and 0 <= top < bottom <= max_y):
        raise ValueError("box must be nonempty and within page")
    return left, top, right, bottom


@dataclass(frozen=True)
class BinaryMask:
    width: int
    height: int
    data: bytes

    def __post_init__(self):
        dimensions(self.width, self.height)
        if (not isinstance(self.data, bytes) or
                len(self.data) != self.width * self.height or
                any(value > 1 for value in self.data)):
            raise ValueError("mask must be width*height binary bytes (0/1)")


def box_mask(box, width, height):
    left, top, right, bottom = validate_box(box, width, height)
    column_coverage = bytes(int(left <= Fraction(2*column_index+1, 2) < right) for column_index in range(width))
    empty = bytes(width)
    return BinaryMask(width, height, b"".join(
        column_coverage if top <= Fraction(2*row_index+1, 2) < bottom else empty for row_index in range(height)))


def project_alpha(alpha, mask_width, mask_height, bounds, width, height,
                  threshold=128):
    dimensions(mask_width, mask_height)
    dimensions(width, height)
    if not isinstance(alpha, bytes) or len(alpha) != mask_width * mask_height:
        raise ValueError("alpha must be exactly mask_width*mask_height bytes")
    if type(threshold) is not int or not 1 <= threshold <= 255:
        raise ValueError("threshold must be an integer in 1..255")
    left, top, right, bottom = validate_box(bounds, width, height, normalized=True)
    left, right, top, bottom = left*width, right*width, top*height, bottom*height
    x_map = [(int((Fraction(2*column_index+1, 2)-left)*mask_width/(right-left))
              if left <= Fraction(2*column_index+1, 2) < right else None) for column_index in range(width)]
    output = bytearray(width * height)
    for row_index in range(height):
        center_y = Fraction(2*row_index+1, 2)
        if not top <= center_y < bottom:
            continue
        source_y = int((center_y-top)*mask_height/(bottom-top))
        row = source_y * mask_width
        output_offset = row_index * width
        for column_index, source_x in enumerate(x_map):
            if source_x is not None and alpha[row+source_x] >= threshold:
                output[output_offset+column_index] = 1
    return BinaryMask(width, height, bytes(output))


def union_masks(masks, width, height):
    output = bytearray(width * height)
    for mask in masks:
        if not isinstance(mask, BinaryMask) or (mask.width, mask.height) != (width, height):
            raise ValueError("mask dimensions differ from page")
        for pixel_index, value in enumerate(mask.data):
            output[pixel_index] |= value
    return BinaryMask(width, height, bytes(output))
