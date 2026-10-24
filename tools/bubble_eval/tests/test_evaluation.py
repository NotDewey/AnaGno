"""Known answers authored independently of detector/private Android code."""

from fractions import Fraction
import hashlib
import itertools
import json
from pathlib import Path
import random
import struct
import tempfile
import unittest
import zlib

from tools.bubble_eval.generate_fixtures import generate
from tools.bubble_eval.matching import maximum_matching
from tools.bubble_eval.metrics import evaluate_page, mask_iou
from tools.bubble_eval.png import read_alpha, write_gray, write_rgba
from tools.bubble_eval.projection import BinaryMask, box_mask, project_alpha

FIXTURES = Path(__file__).resolve().parents[3]/'qa/bubble/fixtures'


def item(identity, box, width=12, height=6):
    return {'id': identity, 'box': box, 'mask': box_mask(box, width, height)}


class EvaluationTests(unittest.TestCase):
    def setUp(self):
        self.first = item('a', [0, 0, 4, 4])

    def score(self, gt, pred, **kwargs):
        return evaluate_page(gt, pred, 12, 6, **kwargs)

    def counts(self, result, tp, fp, fn):
        self.assertEqual((result['tp'], result['fp'], result['fn']), (tp, fp, fn))

    def test_perfect_independent_mask_and_box(self):
        for mode in ('mask', 'box'):
            result = self.score([self.first], [item('p', [0, 0, 4, 4])], mode=mode)
            self.counts(result, 1, 0, 0)
            self.assertEqual(result['recall'], 1)
            self.assertEqual(result['iou_sum'], 1)

    def test_half_width_shift_exact_one_third(self):
        prediction = item('p', [2, 0, 6, 4])
        self.assertEqual(mask_iou(self.first['mask'], prediction['mask']), Fraction(1, 3))
        for mode in ('mask', 'box'):
            self.counts(self.score([self.first], [prediction], mode=mode), 0, 1, 1)
            result = self.score([self.first], [prediction], mode=mode, threshold=Fraction(1, 3))
            self.counts(result, 1, 0, 0)
            self.assertEqual(result['iou_sum'], 1/3)

    def test_duplicates_are_false_positives_with_stable_ids(self):
        prediction = [item('z', [0, 0, 4, 4]), item('b', [0, 0, 4, 4])]
        result = self.score([self.first], prediction)
        self.counts(result, 1, 1, 0)
        self.assertEqual(result['matches'][0]['prediction_id'], 'b')
        self.assertEqual(result, self.score([self.first], prediction[::-1]))

    def test_merged_prediction_counts_missed_instance(self):
        gt = [self.first, item('b', [4, 0, 8, 4])]
        result = self.score(gt, [item('merged', [0, 0, 8, 4])])
        self.counts(result, 1, 0, 1)
        self.assertEqual(result['mean_iou'], .25)

    def test_split_prediction_counts_extra_prediction(self):
        result = self.score([self.first], [item('left', [0, 0, 2, 4]), item('right', [2, 0, 4, 4])])
        self.counts(result, 1, 1, 0)
        self.assertEqual(result['iou_sum'], .5)

    def test_threshold_boundaries_half_and_three_quarters(self):
        for box, cutoff in (([0, 0, 2, 4], .5), ([0, 0, 3, 4], .75)):
            pred = [item('p', box)]
            self.counts(self.score([self.first], pred, threshold=cutoff), 1, 0, 0)
            self.counts(self.score([self.first], pred, threshold=cutoff+1e-9), 0, 1, 1)

    def test_negative_empty_and_nonempty(self):
        empty = self.score([], [])
        self.counts(empty, 0, 0, 0)
        self.assertIsNone(empty['recall'])
        self.assertIsNone(empty['mean_iou'])
        self.counts(self.score([], [self.first]), 0, 1, 0)
        missed = self.score([self.first], [])
        self.counts(missed, 0, 0, 1)
        self.assertEqual(missed['mean_iou'], 0)

    def test_group_union_is_separate_from_instance(self):
        gt = [self.first, item('b', [4, 0, 8, 4])]
        pred = [item('p', [0, 0, 8, 4])]
        for mode in ('mask', 'box'):
            group = self.score(gt, pred, mode=mode, unit='group', groups=[{'id':'g', 'members':['a','b']}])
            self.counts(group, 1, 0, 0)
            self.assertEqual(group['iou_sum'], 1)
            self.counts(self.score(gt, pred, mode=mode), 1, 0, 1)

    def test_group_overlapping_union_not_area_sum(self):
        gt = [self.first, item('b', [2, 0, 6, 4])]
        for mode in ('mask', 'box'):
            result = self.score(gt, [item('p', [0, 0, 6, 4])], mode=mode, unit='group',
                                groups=[{'id':'g', 'members':['a','b']}])
            self.assertEqual(result['iou_sum'], 1)

    def test_ungrouped_remains_singleton(self):
        gt = [self.first, item('b', [8, 0, 10, 4])]
        result = self.score(gt, [self.first], unit='group', groups=[{'id':'g', 'members':['a']}])
        self.counts(result, 1, 0, 1)

    def test_ignore_half_foreground_boundary_and_positive_first(self):
        ignore = box_mask([2, 0, 4, 4], 12, 6)
        for mode in ('mask', 'box'):
            result = self.score([], [self.first], mode=mode, ignore_regions=[ignore])
            self.counts(result, 0, 0, 0)
            self.assertEqual(result['ignored_prediction_ids'], ['a'])

            gt = [item('gt', [0, 0, 1, 1])]
            result = self.score(gt, [self.first], mode=mode, ignore_regions=[self.first['mask']])
            self.counts(result, 0, 1, 1)
            self.assertEqual(result['ignored_prediction_ids'], [])

    def test_ignore_union_does_not_double_count(self):
        ignore = box_mask([0, 0, 1, 4], 12, 6)
        self.counts(self.score([], [self.first], ignore_regions=[ignore, ignore]), 0, 1, 0)

    def test_fractional_box_mode_continuous_not_mask_rounding(self):
        gt = [{'id':'g', 'box':[0, 0, 1.5, 1]}]
        pred = [{'id':'p', 'box':[0, 0, 1, 1]}]
        result = self.score(gt, pred, mode='box')
        self.assertEqual(result['iou_sum'], 2/3)

    def test_invalid_canonical_inputs(self):
        for bad in ({'id':'a', 'box':[0, 0, 0, 1]},
                    {'id':'a', 'box':[0, 0, float('nan'), 1]},
                    {'id':'a', 'box':[0, 0, 13, 1]},
                    {'id':'a', 'box':[0, 0, 1, 1], 'mask':BinaryMask(1, 1, b'\1')}):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                self.score([bad], [])
        with self.assertRaises(ValueError):
            self.score([self.first, self.first], [])
        with self.assertRaises(ValueError):
            self.score([], [], threshold=1.01)
        with self.assertRaises(ValueError):
            self.score([self.first], [], unit='group', groups=[{'id':'g', 'members':['missing']}])


class MatchingTests(unittest.TestCase):
    def test_cardinality_assignment_trap(self):
        scores = {('a','x'):Fraction(9,10), ('a','y'):Fraction(6,10), ('b','x'):Fraction(6,10)}
        self.assertEqual([(first,prediction) for first,prediction,_ in maximum_matching(scores)], [('a','y'),('b','x')])

    def test_total_iou_before_lexicographic(self):
        scores = {('a','x'):Fraction(6,10), ('a','y'):Fraction(9,10),
                  ('b','x'):Fraction(9,10), ('b','y'):Fraction(6,10)}
        self.assertEqual([(first,prediction) for first,prediction,_ in maximum_matching(scores)], [('a','y'),('b','x')])

    def test_lex_ties_independent_of_insertion_order(self):
        scores = {edge:Fraction(1) for edge in itertools.product(('a','b'), ('x','y'))}
        expected = [('a','x',Fraction(1)), ('b','y',Fraction(1))]
        self.assertEqual(maximum_matching(scores), expected)
        self.assertEqual(maximum_matching(dict(reversed(list(scores.items())))), expected)

    def test_exhaustive_independent_oracle_small_random_graphs(self):
        rng = random.Random(20261024)
        all_edges = list(itertools.product(('a','b','c'), ('x','y','z')))
        for _ in range(40):
            scores = {edge:Fraction(rng.randint(0,4),4) for edge in all_edges}
            eligible = [edge for edge in all_edges if scores[edge] >= Fraction(1,2)]
            candidates = []

            for size in range(4):
                for chosen in itertools.combinations(eligible, size):
                    if len({first for first,prediction in chosen}) == size and len({prediction for first,prediction in chosen}) == size:
                        candidates.append(chosen)
            expected = min(candidates, key=lambda chosen_edges: (-len(chosen_edges), -sum(scores[edge] for edge in chosen_edges), chosen_edges))
            self.assertEqual([(first,prediction) for first,prediction,_ in maximum_matching(scores)], list(expected))


class ProjectionTests(unittest.TestCase):
    def test_alpha_threshold_edges_serialized_answers(self):
        answer = json.loads((FIXTURES/'known-answers.json').read_text())['alpha_edge']
        for cutoff, key in ((128, 'threshold_128'), (9, 'threshold_9')):
            result = project_alpha(bytes(answer['coverage']), 6, 1, [0,0,1,1], 6, 1, cutoff)
            self.assertEqual(list(result.data), answer[key])

    def test_non_square_fractional_crop_pixel_centers(self):



        result = project_alpha(bytes([255,0,0,255]), 2, 2,
                               [Fraction(3,28), Fraction(1,10), Fraction(3,4), Fraction(9,10)], 7, 3)
        self.assertEqual(result.data, bytes([0,1,1,0,0,0,0,
                                          0,0,0,1,1,0,0,
                                          0,0,0,1,1,0,0]))

    def test_half_open_boundary_center_exactly_on_edge(self):
        result = project_alpha(b'\xff', 1, 1, [.125,0,.625,1], 4, 1)
        self.assertEqual(result.data, b'\1\1\0\0')

    def test_transition_dimensions_preserve_last_pixels(self):
        for width in (3199,3200,3201,6401,6402):
            result = project_alpha(b'\xff', 1, 1, [Fraction(width-1,width),0,1,1], width, 1)
            self.assertEqual(sum(result.data), 1)
            self.assertEqual(result.data[-1], 1)

    def test_invalid_masks_bounds_dimensions(self):
        for width, height, data in ((0,1,b''), (1,1,b'\2'), (2,1,b'\1'), (True,1,b'\1')):
            with self.assertRaises(ValueError):
                BinaryMask(width, height, data)
        for bounds in ([-.01,0,1,1], [0,0,1.01,1], [0,0,0,1], [0,0,float('inf'),1]):
            with self.assertRaises(ValueError):
                project_alpha(b'\xff', 1, 1, bounds, 2, 2)
        with self.assertRaises(ValueError):
            project_alpha(b'', 1, 1, [0,0,1,1], 2, 2)
        with self.assertRaises(ValueError):
            project_alpha(b'\xff', 1, 1, [0,0,1,1], 2, 2, 0)


def chunk(kind, data):

    return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)


def png_bytes(width, height, color, scanlines, depth=8, interlace=0):
    return (b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR', struct.pack('>IIBBBBB',width,height,depth,color,0,0,interlace))+
            chunk(b'IDAT', zlib.compress(scanlines))+chunk(b'IEND', b''))


class PngTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name)/'test.png'

    def test_gray_values_are_coverage(self):
        self.path.write_bytes(png_bytes(3,1,0,b'\0\0\x7f\xff'))
        self.assertEqual(read_alpha(self.path), (3,1,b'\0\x7f\xff'))

    def test_rgba_uses_actual_alpha_not_rgb(self):
        self.path.write_bytes(png_bytes(2,1,6,b'\0\xff\xff\xff\0\0\0\0\xff'))
        self.assertEqual(read_alpha(self.path), (2,1,b'\0\xff'))

    def test_writer_roundtrip_gray_and_rgba(self):
        write_gray(self.path, 2, 2, bytes([0,8,128,255]))
        self.assertEqual(read_alpha(self.path), (2,2,bytes([0,8,128,255])))
        write_rgba(self.path, 2, 1, bytes([4,5,6,8,7,8,9,128]))
        self.assertEqual(read_alpha(self.path), (2,1,bytes([8,128])))

    def test_all_scanline_filters_gray_independent_known_bytes(self):

        encoded = {0:bytes([15,25,35]), 1:bytes([15,10,10]),
                   2:bytes([5,5,5]), 3:bytes([10,8,8]), 4:bytes([5,5,5])}
        for method, row in encoded.items():
            self.path.write_bytes(png_bytes(3,2,0,b'\0\x0a\x14\x1e'+bytes([method])+row))
            self.assertEqual(read_alpha(self.path)[2], bytes([10,20,30,15,25,35]))

    def test_rgba_sub_filter_uses_four_byte_stride(self):

        self.path.write_bytes(png_bytes(2,1,6,bytes([1,1,2,3,128,4,4,4,127])))
        self.assertEqual(read_alpha(self.path)[2], bytes([128,255]))

    def test_bad_crc_truncation_format_filter_length_and_extra_stream(self):
        valid = png_bytes(1,1,0,b'\0\xff')
        invalid = [b'not-png', valid[:-1], valid+b'x', valid[:20]+b'x'+valid[21:],
                   png_bytes(1,1,2,b'\0\xff\xff\xff'), png_bytes(1,1,0,b'\0\xff',depth=16),
                   png_bytes(1,1,0,b'\0\xff',interlace=1), png_bytes(1,1,0,b'\5\xff'),
                   png_bytes(1,1,0,b'\0'), png_bytes(1,1,0,b'\0\xff\xff')]
        for data in invalid:
            with self.subTest(data=data[:25]), self.assertRaises(ValueError):
                self.path.write_bytes(data)
                read_alpha(self.path)


class FixtureTests(unittest.TestCase):
    def test_generator_passes_shared_validator_but_blocks_real_baseline(self):
        from tools.bubble_eval.validate import validate_manifest

        with tempfile.TemporaryDirectory() as directory:
            generate(directory)
            manifest = validate_manifest(Path(directory)/'manifest.json')
            self.assertEqual(manifest['_validation']['inventory_status'], 'PASS')
            self.assertEqual(manifest['_validation']['baseline_status'], 'BLOCKED')
            for sample in manifest['samples']:
                annotation = json.loads((Path(directory)/sample['annotation']['path']).read_text())
                members = [member for group in annotation['groups'] for member in group['members']]
                self.assertEqual(sorted(members), sorted(instance['id'] for instance in annotation['instances']))
            with self.assertRaises(ValueError):
                validate_manifest(Path(directory)/'manifest.json', require_core=True)

    def test_generated_manifest_masks_landmarks_and_determinism(self):
        answers = json.loads((FIXTURES/'known-answers.json').read_text())
        self.assertTrue(answers['real_accuracy_baseline'].startswith('BLOCKED'))
        with tempfile.TemporaryDirectory() as first_root, tempfile.TemporaryDirectory() as second_root:
            one, two = generate(first_root), generate(second_root)
            self.assertEqual(one, two)
            self.assertEqual(one['kind'], 'synthetic')
            paths_one = sorted(Path(first_root).iterdir())
            paths_two = sorted(Path(second_root).iterdir())
            self.assertEqual([prediction.name for prediction in paths_one], [prediction.name for prediction in paths_two])
            for first,second in zip(paths_one, paths_two):
                self.assertEqual(first.read_bytes(), second.read_bytes())
            for sample in one['samples']:
                annotation = json.loads((Path(first_root)/sample['annotation']['path']).read_text())
                total = 0
                for instance in annotation['instances']:
                    path = Path(first_root)/instance['mask_path']
                    self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), instance['mask_sha256'])
                    width,height,alpha = read_alpha(path)
                    left,top,right,bottom = instance['box']

                    expected = bytes(255 if left <= column_index < right and top <= row_index < bottom else 0
                                     for row_index in range(height) for column_index in range(width))
                    self.assertEqual(alpha, expected)
                    total += sum(value == 255 for value in alpha)
                key = sample['sample_id']
                expected_total = answers[key]['foreground_pixels'] if key in answers else answers['transition_foreground_pixels']
                self.assertEqual(total, expected_total)
            with self.assertRaises(ValueError):
                generate(first_root)


if __name__ == '__main__':
    unittest.main()
