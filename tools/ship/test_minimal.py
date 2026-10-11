"""Offline layout invariants; these do not replace flight acceptance."""
import collections
import unittest
from pathlib import Path

from minimal import blocks, build


class MinimalHullTest(unittest.TestCase):
    def test_parts_and_mass(self):
        count = collections.Counter(s.split('[')[0] for s in blocks().values())
        self.assertEqual(sum(count.values()), 185)
        self.assertEqual(count['minecraft:cobblestone'], 142)
        self.assertEqual(count['minecraft:oak_planks'], 19)
        self.assertEqual(count['starfold:thruster'], 12)
        self.assertEqual(count['minecraft:oak_door'], 4)
        mass = {'minecraft:cobblestone': 2, 'minecraft:oak_planks': .5,
                'minecraft:oak_door': .25, 'minecraft:lever': 0}
        self.assertEqual(sum(mass.get(k, 1) * v for k, v in count.items()), 312.5)
        moment = sum(y * mass.get(s.split('[')[0], 1)
                     for (x, y, z), s in blocks().items())
        self.assertAlmostEqual(moment / 312.5, -3.2624)

    def test_direction_pairs(self):
        for direction in ('north', 'south', 'west', 'east', 'up', 'down'):
            positions = [p for p, s in blocks().items()
                         if s == f'starfold:thruster[facing={direction}]']
            self.assertEqual(len(positions), 2)
            if direction in ('north', 'south', 'up', 'down'):
                self.assertEqual(sum(p[0] for p in positions), 0)
            if direction in ('west', 'east', 'up', 'down'):
                self.assertEqual(sum(p[2] for p in positions), 0)
            if direction not in ('up', 'down'):
                self.assertTrue(all(p[1] == -3 for p in positions))

    def test_clear_door_approaches(self):
        hull = blocks()
        for z in (-2, 2):
            self.assertIn('airstair', hull[0, 0, z])
            self.assertIn('oak_door', hull[0, 1, z])
            self.assertIn('oak_door', hull[0, 2, z])
            for y in range(-6, 3):
                self.assertNotIn((0, y, z + (-1 if z < 0 else 1)), hull)
        self.assertEqual(sum('gyro' in s for s in hull.values()), 2)
        self.assertEqual(sum('lever' in s and 'powered=true' in s for s in hull.values()), 2)

    def test_versioned_fixture_matches_generator(self):
        fixture = Path(__file__).resolve().parents[1] / 'probe/minimal-build.txt'
        actual = [line.removeprefix('cmd ') for line in fixture.read_text().splitlines()
                  if line.startswith('cmd setblock ')]
        self.assertEqual(actual, build(5292, 147, 862))

    def test_invalid_inputs(self):
        for candidate, layers in [('unknown', 6), ('B', -1), ('B', 2.5)]:
            with self.assertRaises(ValueError):
                blocks(candidate, layers)


if __name__ == '__main__':
    unittest.main()
