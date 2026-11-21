"""Layout connectivity and clearance checks; these are not flight qualification."""
import collections
import importlib.util
from pathlib import Path
import re
import unittest

from first_ship import layout, report
from minimal import blocks


class FirstShipTest(unittest.TestCase):
    def test_only_six_ballast_cells_change(self):
        original, hull = blocks(), layout()
        changed = {p for p in original if original[p] != hull[p]}
        self.assertEqual(changed, {(x, -3, z) for x in (-2, 2) for z in (-2, 0, 2)})
        self.assertEqual(set(original), set(hull))
        self.assertTrue(all(original[p] == 'minecraft:cobblestone' for p in changed))
        counts = report()['items']
        self.assertEqual(counts['minecraft:cobblestone'], 136)
        self.assertEqual(counts['create:fluid_tank'], 4)
        self.assertEqual(counts['minecraft:barrel'], 2)
        self.assertEqual(counts['minecraft:oak_door'], 2)

    def test_in_game_reference_draws_the_same_hull(self):
        path = Path(__file__).resolve().parents[1] / 'modpack/generate-quests.py'
        spec = importlib.util.spec_from_file_location('ship_quest_reference', path)
        generator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(generator)
        symbols = {
            'minecraft:cobblestone': 'C', 'minecraft:oak_planks': 'P',
            'create:fluid_tank': 'T', 'minecraft:barrel': 'B',
            'starfold:gyro': 'G', 'minecraft:lever': 'l', 'starfold:helm': 'H',
            'starfold:scope_nav': 'N', 'starfold:airstair': 'A', 'minecraft:oak_door': 'd',
        }
        directions = {'north': 'n', 'south': 's', 'west': 'w', 'east': 'e', 'up': '^', 'down': 'v'}
        expected = {}
        for pos, state in layout().items():
            block = state.split('[')[0]
            if block == 'starfold:thruster':
                symbol = directions[re.search(r'facing=(\w+)', state)[1]]
            else:
                symbol = symbols[block]
            expected[pos] = symbol
        for locale, lines in generator.first_ship_reference.items():
            drawn = {}
            for line in lines:
                match = re.fullmatch(r'Y=([\d, -]+)[:：]\s*(.*)', line)
                if not match:
                    continue
                rows = match[2].split(' / ')
                self.assertEqual(len(rows), 5)
                for y in map(int, match[1].split(',')):
                    for z, row in enumerate(rows, -2):
                        self.assertEqual(len(row), 5)
                        for x, symbol in enumerate(row, -2):
                            if symbol != '.':
                                drawn[x, y, z] = symbol
            self.assertEqual(drawn, expected, locale)

    def test_connected_hull(self):
        hull = layout()
        visited = {next(iter(hull))}
        pending = collections.deque(visited)
        while pending:
            x, y, z = pending.popleft()
            for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
                p = x + dx, y + dy, z + dz
                if p in hull and p not in visited:
                    visited.add(p)
                    pending.append(p)
        self.assertEqual(visited, set(hull))

    def test_symmetric_tanks_and_clear_service_faces(self):
        hull = layout()
        tanks = [p for p, state in hull.items() if state == 'create:fluid_tank']
        self.assertEqual(len(tanks), 4)
        self.assertEqual(tuple(map(sum, zip(*tanks))), (0, -12, 0))
        for x in (-2, 2):
            # Exterior service access must stay outside the assembly selection.
            for z in (-2, 0, 2):
                self.assertNotIn((x + (1 if x > 0 else -1), -3, z), hull)
        # Boarding route and helm aisle are inherited without new obstructions.
        for z in (-1, 1):
            self.assertNotIn((0, 1, z), hull)
            self.assertNotIn((0, 2, z), hull)
        for z in (-3, 3):
            self.assertTrue(all((0, y, z) not in hull for y in range(-6, 3)))


if __name__ == '__main__':
    unittest.main()
