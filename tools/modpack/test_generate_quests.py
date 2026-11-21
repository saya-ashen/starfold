"""Quest-only regression tests. No game, Gradle, jar execution or full-pack checks."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from quest_contract import EXPEDITION_CHAIN, validate_graph_contract, validate_stable_ids


REPO = Path(__file__).resolve().parents[2]
GENERATOR = REPO / 'tools/modpack/generate-quests.py'
QUEST_ROOT = REPO / 'modpack/config/ftbquests/quests'


def snbt(path):
    return json.loads('\n'.join(line for line in path.read_text().splitlines()
                                if not line.startswith('//')))


class QuestGenerationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        subprocess.run([sys.executable, str(GENERATOR), '--output', str(cls.root)],
                       cwd=REPO, check=True, capture_output=True, text=True)
        cls.chapters = [snbt(path) for path in sorted((cls.root / 'chapters').glob('*.snbt'))]
        cls.quests = {q['id']: q for chapter in cls.chapters for q in chapter['quests']}
        cls.graph = {int(qid, 16): [int(parent, 16) for parent in q.get('dependencies', [])]
                     for qid, q in cls.quests.items()}
        cls.optional = {int(qid, 16) for qid, q in cls.quests.items() if q.get('optional', False)}
        cls.langs = {locale: snbt(cls.root / f'lang/{locale}.snbt') for locale in ('en_us', 'zh_cn')}
        spec = importlib.util.spec_from_file_location('quest_generator', GENERATOR)
        cls.generator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.generator)

    def test_preserves_all_existing_ids_and_ownership(self):
        validate_stable_ids(self.chapters)
        for chapter in self.chapters:
            self.assertTrue((self.root / f'chapters/{chapter["id"]}.snbt').exists())

    def test_graph_resolves_without_display_order_assumptions(self):
        # IDs moved from aviation/off-world chapters are intentionally out of numeric order.
        self.generator.validate_dependencies(self.graph, self.optional)
        validate_graph_contract(self.quests)

    def test_validator_rejects_cycles_and_missing_dependencies(self):
        root = 0x1000000000000100
        with self.assertRaisesRegex(AssertionError, 'cycle'):
            self.generator.validate_dependencies({**self.graph, root: [0x1000000000050100]}, self.optional)
        with self.assertRaisesRegex(AssertionError, 'Missing dependency'):
            self.generator.validate_dependencies({**self.graph, root: [0x7777777777777777]}, self.optional)

    def test_core_completes_without_aviation_or_large_pumpjack(self):
        # Simulate finishing only mandatory quests. No optional quest may block any core progress.
        pending = set(self.graph) - self.optional
        completed = set()
        while pending:
            ready = {qid for qid in pending if set(self.graph[qid]) <= completed}
            self.assertTrue(ready, f'Core is blocked by an optional quest or cycle: {pending}')
            completed.update(ready)
            pending.difference_update(ready)
        scanner = 0x1000000000040100
        with self.assertRaisesRegex(AssertionError, 'optional branch'):
            self.generator.validate_dependencies(
                {**self.graph, scanner: self.graph[scanner] + [0x1000000000030400]}, self.optional)

    def test_stage_five_records_are_manual_and_bilingual(self):
        for qid in ('1000000000050F00', '1000000000060C00', '1000000000060D00'):
            self.assertEqual([t['type'] for t in self.quests[qid]['tasks']], ['checkmark'])
            for locale, word in (('en_us', 'Manual'), ('zh_cn', '手动')):
                self.assertIn(word, str(self.langs[locale][f'quest.{qid}.quest_desc']))
        for locale, forbidden in (('en_us', 'three buckets'), ('zh_cn', '约三桶')):
            self.assertNotIn(forbidden, str(self.langs[locale]))

    def test_expedition_records_explain_manual_proofs(self):
        for qid in ['1000000000080B00', *EXPEDITION_CHAIN[1:]]:
            self.assertEqual([t['type'] for t in self.quests[qid]['tasks']], ['checkmark'])
            for locale, word in (('en_us', 'Manual'), ('zh_cn', '手动')):
                self.assertIn(word, str(self.langs[locale][f'quest.{qid}.quest_desc']))
        for locale, phrase in (('en_us', 'other systems exist'), ('zh_cn', '另有其他星系')):
            self.assertIn(phrase, str(self.langs[locale]['quest.1000000000080800.quest_desc']))
        for locale, phrase in (('en_us', 'not automatic'), ('zh_cn', '非自动')):
            for qid in ('1000000000080C00', '1000000000080E00', '1000000000080F00', '1000000000081000'):
                self.assertIn(phrase, str(self.langs[locale][f'quest.{qid}.quest_desc']))

    def test_heat_bootstrap_does_not_require_create_alternator(self):
        infuser = '1000000000050600'
        for locale, words in (('en_us', ('heat generator', 'four osmium')),
                              ('zh_cn', ('热力发电机', '4锇锭'))):
            text = ' '.join(self.langs[locale][f'quest.{infuser}.quest_desc'])
            for word in words:
                self.assertIn(word, text)

    def test_first_ship_uses_shaped_recipes_without_mandatory_mechanical_grid(self):
        for locale, phrase in (('en_us', 'shaped recipe'), ('zh_cn', '普通有序配方')):
            self.assertIn(phrase, ' '.join(self.langs[locale]['quest.1000000000020E00.quest_desc']))

    def test_navigation_and_ship_construction_precede_first_flight(self):
        nav = '1000000000050800'
        self.assertEqual(self.quests[nav]['tasks'][0]['item']['id'], 'starfold:scope_nav')
        self.assertIn(nav, {q['id'] for q in self.chapters[4]['quests']})
        self.assertEqual(self.quests['1000000000040A00']['tasks'][0]['advancement'], 'starfold:ship/lift_off')

    def test_local_reboarding_trial_gates_space_without_claiming_detection(self):
        local = '1000000000041200'
        self.assertEqual([t['type'] for t in self.quests[local]['tasks']], ['checkmark'])
        for locale, words in {
            'en_us': ('Manual', 'board again', 'repair', 'does not detect'),
            'zh_cn': ('手动', '再次登船', '更换', '不自动检测'),
        }.items():
            text = ' '.join(self.langs[locale][f'quest.{local}.quest_desc'])
            for word in words:
                self.assertIn(word, text)

    def test_round_trip_records_preserve_raw_cargo_until_home_unloading(self):
        departure, loading, delivery = ('1000000000050C00', '1000000000050D00', '1000000000050E00')
        for qid in (departure, loading, delivery):
            self.assertEqual([t['type'] for t in self.quests[qid]['tasks']], ['checkmark'])
            for locale, word in (('en_us', 'Manual'), ('zh_cn', '手动')):
                self.assertIn(word, str(self.langs[locale][f'quest.{qid}.quest_desc']))
        for locale, words in (('en_us', ('UUID', 'does not prove', 'consistency')),
                              ('zh_cn', ('UUID', '不证明', '一致性'))):
            text = ' '.join(self.langs[locale][f'quest.{delivery}.quest_desc'])
            for word in words:
                self.assertIn(word, text)

    def test_precision_mechanism_teaches_five_deploying_rounds(self):
        # The native Create recipe repeats its three deploying steps five times.
        text = ' '.join(self.langs['en_us']['quest.1000000000020C00.quest_desc']).lower()
        self.assertIn('five times', text)
        self.assertIn('five of each', text)

    def test_manual_proofs_and_fuel_choice_are_explicit(self):
        records = ('1000000000020D00', '1000000000020E00', '1000000000021000',
                   '1000000000030400', '1000000000030900', '1000000000040F00',
                   '1000000000040400', '1000000000041000', '1000000000041100', '1000000000041200')
        for qid in records:
            self.assertEqual([t['type'] for t in self.quests[qid]['tasks']], ['checkmark'])
            self.assertIn('manual', str(self.langs['en_us'][f'quest.{qid}.quest_desc']).lower())
            self.assertIn('手动', str(self.langs['zh_cn'][f'quest.{qid}.quest_desc']))
        for qid in ('1000000000040400', '1000000000041000'):
            text = ' '.join(self.langs['en_us'][f'quest.{qid}.quest_desc']).lower()
            self.assertIn('diesel or gasoline', text)
            self.assertIn('either fuel alone', text)
            self.assertNotIn('eight buckets', text)

    def test_aircraft_migration_retains_ids_and_optional_native_teaching(self):
        for index in range(1, 5):
            qid = f'100000000003{index:02X}00'
            self.assertEqual([t['type'] for t in self.quests[qid]['tasks']], ['checkmark'])
            self.assertIn('Immersive Aircraft', str(self.langs['en_us'][f'quest.{qid}.quest_desc']))
        self.assertFalse(any(t.get('item', {}).get('id', '').startswith('immersive_aircraft:')
                             for q in self.quests.values() for t in q['tasks']))
        self.assertEqual(self.quests['1000000000030800']['tasks'][0]['item']['id'],
                         'aeronautics:andesite_propeller')

    def test_bilingual_output_and_checked_in_files_reproduce(self):
        expected_keys = {'file.0000000000000001.title'}
        expected_keys.update(f'chapter.{c["id"]}.title' for c in self.chapters)
        expected_keys.update(f'quest.{qid}.{suffix}' for qid in self.quests for suffix in ('title', 'quest_desc'))
        for table in self.langs.values():
            self.assertEqual(set(table), expected_keys)
            self.assertTrue(all(table.values()))
        generated = {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob('*.snbt')}
        checked_in = {p.relative_to(QUEST_ROOT): p.read_bytes() for p in QUEST_ROOT.rglob('*.snbt')}
        self.assertEqual(generated.keys(), checked_in.keys(), 'No legacy chapter filename may survive')
        for path, content in generated.items():
            with self.subTest(path=path):
                self.assertEqual(content, checked_in[path])


if __name__ == '__main__':
    unittest.main()
