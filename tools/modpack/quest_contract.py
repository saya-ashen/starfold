"""Persisted quest IDs and progression contract, independent of the authoring generator.

Both the committed-pack audit and fresh-generation tests consume these expectations.
Do not derive them from generate-quests.py: they protect saved progress across edits.
"""

FILE_ID = '0000000000000001'
CHAPTER_IDS = tuple(f'{0x1000000000000000 + ci * 0x10000:016X}' for ci in range(9))

# Snapshot of the 87 pre-stage-two quests, including IDs later moved between chapters.
LEGACY_QUEST_IDS = frozenset(
    {f'{0x1000000000000000 + ci * 0x10000 + i * 0x100:016X}'
     for ci, count in enumerate((6, 8, 12, 8, 12, 11, 10, 4, 8))
     for i in range(1, count + 1)}
    | {'1000000000010900', '1000000000060B00', '1000000000080900', '1000000000080A00'}
    | {f'100000000007{i:02X}00' for i in range(8, 12)})
# All 104 quests delivered before Stage 6/7. Task/reward ownership is also persisted.
PRIOR_STAGE_QUEST_IDS = LEGACY_QUEST_IDS | frozenset({
    '1000000000020D00', '1000000000020E00', '1000000000020F00',
    '1000000000021000', '1000000000030900', '1000000000040D00',
    '1000000000040E00', '1000000000040F00', '1000000000041000',
    '1000000000041100', '1000000000041200', '1000000000050C00',
    '1000000000050D00', '1000000000050E00', '1000000000050F00',
    '1000000000060C00', '1000000000060D00',
})
SHIPYARD_QUEST_IDS = frozenset({'1000000000051000', '1000000000060E00'})
NEW_QUEST_IDS = frozenset(f'100000000008{i:02X}00' for i in range(11, 17)) | SHIPYARD_QUEST_IDS
OPTIONAL_QUEST_IDS = frozenset(
    {f'100000000003{i:02X}00' for i in (1, 2, 3, 4, 5, 8, 9)}
    | {'1000000000040200', '1000000000020F00', '1000000000050500'}
    | {f'100000000006{i:02X}00' for i in (1, 2, 3, 6, 7, 8, 9, 10)}
    | {f'100000000008{i:02X}00' for i in (1, 2, 3, 4)}
    | SHIPYARD_QUEST_IDS)
EXPEDITION_CHAIN = (
    '1000000000080A00', '1000000000080C00', '1000000000080D00',
    '1000000000080800', '1000000000080E00', '1000000000080F00',
    '1000000000081000',
)


def validate_stable_ids(chapters):
    """Check chapter IDs, global uniqueness and historical task/reward ownership."""
    assert tuple(chapter['id'] for chapter in chapters) == CHAPTER_IDS, 'Chapter IDs changed'
    seen = {FILE_ID}
    quests = {}

    def unique(identifier):
        assert len(identifier) == 16 and 0 < int(identifier, 16) < 2**63, identifier
        assert identifier not in seen, f'Duplicate quest object ID: {identifier}'
        seen.add(identifier)

    for chapter in chapters:
        unique(chapter['id'])
        for quest in chapter['quests']:
            unique(quest['id'])
            quests[quest['id']] = quest
            for obj in (*quest['tasks'], *quest['rewards']):
                unique(obj['id'])
    assert quests.keys() == PRIOR_STAGE_QUEST_IDS | NEW_QUEST_IDS, 'Quest ID snapshot changed'
    for identifier in PRIOR_STAGE_QUEST_IDS:
        quest = quests[identifier]
        base = int(identifier, 16)
        task_ids = {f'{base + 1:016X}'}
        if identifier == '1000000000040300':
            task_ids.add('1000000000040303')
        assert {task['id'] for task in quest['tasks']} == task_ids, f'Task ownership changed: {identifier}'
        assert [reward['id'] for reward in quest['rewards']] == [f'{base + 2:016X}'], f'Reward ownership changed: {identifier}'


def dependency_ancestors(quests):
    """Resolve the whole graph, rejecting missing parents and cycles before traversal."""
    resolved = {}

    def ancestors(identifier, visiting=frozenset()):
        assert identifier in quests, f'Missing dependency: {identifier}'
        assert identifier not in visiting, f'Quest dependency cycle: {identifier}'
        if identifier not in resolved:
            found = set(quests[identifier].get('dependencies', []))
            for parent in list(found):
                found.update(ancestors(parent, visiting | {identifier}))
            resolved[identifier] = found
        return resolved[identifier]

    for identifier in quests:
        ancestors(identifier)
    return resolved


def validate_graph_contract(quests):
    """Check optional branches and the authored workshop/flight/expedition gates."""
    ancestors = dependency_ancestors(quests)
    optional = {identifier for identifier, quest in quests.items() if quest.get('optional', False)}
    assert optional == OPTIONAL_QUEST_IDS, 'Optional quest snapshot changed'
    for identifier, parents in ancestors.items():
        if identifier not in optional:
            assert not parents & optional, f'Core quest depends on optional branch: {identifier}'

    # Exact edges express the records that must be completed immediately beforehand.
    edges = {
        '1000000000051000': ['1000000000050E00'],
        '1000000000060E00': ['1000000000051000', '1000000000060C00'],
        '1000000000040100': ['1000000000020E00'],  # Workshop opens fuel independently of ship parts.
        '1000000000041200': ['1000000000040A00'],  # Local reboarding trial.
        '1000000000050C00': ['1000000000041200'],
        '1000000000040B00': ['1000000000050C00'],
        '1000000000050D00': ['1000000000050200'],  # Raw cargo through loading and home unloading.
        '1000000000050400': ['1000000000050D00'],
        '1000000000050E00': ['1000000000050400'],
        '1000000000050300': ['1000000000050E00'],
        '1000000000050500': ['1000000000050300'],  # Optional alternator; heat can bootstrap circuits.
        '1000000000050600': ['1000000000050300'],
        '1000000000050F00': ['1000000000050B00'],
        '1000000000060D00': ['1000000000060C00'],
    }
    edges.update({f'100000000006{i:02X}00': ['1000000000050F00']
                  for i in (1, 2, 3, 6, 7, 8, 9, 10)})
    edges.update({current: [previous] for previous, current in zip(EXPEDITION_CHAIN, EXPEDITION_CHAIN[1:])})
    for identifier, parents in edges.items():
        assert quests[identifier]['dependencies'] == parents, f'Quest gate changed: {identifier}'
    assert set(quests['1000000000080A00']['dependencies']) == {
        '1000000000080B00', '1000000000070A00', '1000000000070B00'}, 'Drive must join gas and Host branches'

    preflight = {'1000000000050800', '1000000000030600', '1000000000030700',
                 '1000000000040C00', '1000000000041000', '1000000000041100'}
    required = {
        '1000000000040A00': preflight | {'1000000000021000'},
        '1000000000040B00': preflight | {'1000000000050C00'},
        '1000000000050100': preflight | {'1000000000041200'},
        '1000000000021000': {'1000000000020E00'},
        '1000000000041100': {'1000000000021000'},
        '1000000000041000': {'1000000000040400'},
        '1000000000040F00': {'1000000000040D00', '1000000000040E00'},
        '1000000000060C00': {'1000000000050F00'},
        '1000000000080500': {'1000000000060D00'},
        '1000000000070A00': {'1000000000060D00'},
    }
    for identifier, parents in required.items():
        assert parents <= ancestors[identifier], f'Missing prerequisite: {identifier}'
    excluded = {
        '1000000000040100': {'1000000000021000'},
        '1000000000041200': {'1000000000040B00'},
        '1000000000030900': {'1000000000040A00'},
        '1000000000080B00': {'1000000000070A00'},
        '1000000000070A00': {'1000000000080B00', '1000000000080500'},
    }
    for identifier, parents in excluded.items():
        assert not parents & ancestors[identifier], f'Unexpected prerequisite: {identifier}'
    assert not any(parent.startswith('100000000005') for parent in ancestors['1000000000050800']), 'First navigation must not require off-world osmium'
