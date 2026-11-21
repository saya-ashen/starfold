"""Exercise the independent contract against mutations of committed quest fixtures."""
import copy
import json
from pathlib import Path
import unittest

from quest_contract import dependency_ancestors, validate_graph_contract, validate_stable_ids


QUEST_ROOT = Path(__file__).resolve().parents[2] / 'modpack/config/ftbquests/quests'


class QuestContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.chapters = [json.loads('\n'.join(line for line in path.read_text().splitlines()
                                           if not line.startswith('//')))
                        for path in sorted((QUEST_ROOT / 'chapters').glob('*.snbt'))]
        cls.quests = {q['id']: q for chapter in cls.chapters for q in chapter['quests']}

    def test_committed_fixture_satisfies_contract(self):
        validate_stable_ids(self.chapters)
        validate_graph_contract(self.quests)

    def test_rejects_chapter_and_quest_id_changes(self):
        chapters = copy.deepcopy(self.chapters)
        chapters[0]['id'] = '1000000000090000'
        with self.assertRaisesRegex(AssertionError, 'Chapter IDs changed'):
            validate_stable_ids(chapters)
        for change in ('remove', 'rename'):
            with self.subTest(change=change):
                chapters = copy.deepcopy(self.chapters)
                if change == 'remove':
                    chapters[0]['quests'].pop()
                else:
                    chapters[0]['quests'][0]['id'] = '1000000000090100'
                with self.assertRaisesRegex(AssertionError, 'Quest ID snapshot changed'):
                    validate_stable_ids(chapters)

    def test_rejects_cross_object_collisions(self):
        chapters = copy.deepcopy(self.chapters)
        quest = chapters[0]['quests'][0]
        quest['tasks'][0]['id'] = quest['rewards'][0]['id']
        with self.assertRaisesRegex(AssertionError, 'Duplicate quest object ID'):
            validate_stable_ids(chapters)

    def test_rejects_task_and_reward_ownership_changes(self):
        for kind, message in (('tasks', 'Task'), ('rewards', 'Reward')):
            with self.subTest(kind=kind):
                chapters = copy.deepcopy(self.chapters)
                first, second = chapters[0]['quests'][:2]
                # A swap preserves the global ID set but corrupts saved ownership.
                first[kind][0]['id'], second[kind][0]['id'] = (
                    second[kind][0]['id'], first[kind][0]['id'])
                with self.assertRaisesRegex(AssertionError, f'{message} ownership changed'):
                    validate_stable_ids(chapters)
        chapters = copy.deepcopy(self.chapters)
        quest = next(q for c in chapters for q in c['quests'] if q['id'] == '1000000000040300')
        quest['tasks'] = [task for task in quest['tasks'] if task['id'] != '1000000000040303']
        with self.assertRaisesRegex(AssertionError, 'Task ownership changed'):
            validate_stable_ids(chapters)

    def test_graph_traversal_rejects_missing_parents_and_cycles(self):
        for graph, message in (
                ({'a': {'dependencies': ['missing']}}, 'Missing dependency'),
                ({'a': {'dependencies': ['b']}, 'b': {'dependencies': ['a']}}, 'cycle')):
            with self.subTest(message=message):
                with self.assertRaisesRegex(AssertionError, message):
                    dependency_ancestors(graph)
        self.assertEqual(dependency_ancestors({'a': {'dependencies': ['b']}, 'b': {}}),
                         {'a': {'b'}, 'b': set()})

    def test_rejects_optional_drift_and_core_blockers(self):
        quests = copy.deepcopy(self.quests)
        quests['1000000000030400']['optional'] = False
        with self.assertRaisesRegex(AssertionError, 'Optional quest snapshot changed'):
            validate_graph_contract(quests)
        quests = copy.deepcopy(self.quests)
        quests['1000000000040100']['dependencies'].append('1000000000030400')
        with self.assertRaisesRegex(AssertionError, 'Core quest depends on optional branch'):
            validate_graph_contract(quests)

    def test_rejects_progression_gate_regressions(self):
        for identifier, parents, message in (
                ('1000000000040100', [], 'Quest gate changed'),
                ('1000000000080E00', ['1000000000080A00'], 'Quest gate changed'),
                ('1000000000080A00', ['1000000000080B00'], 'Drive must join'),
                ('1000000000040A00', [], 'Missing prerequisite'),
                ('1000000000070A00', ['1000000000060D00', '1000000000080500'],
                 'Unexpected prerequisite')):
            with self.subTest(quest=identifier):
                quests = copy.deepcopy(self.quests)
                quests[identifier]['dependencies'] = parents
                with self.assertRaisesRegex(AssertionError, message):
                    validate_graph_contract(quests)


if __name__ == '__main__':
    unittest.main()
