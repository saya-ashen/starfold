#!/usr/bin/env python3
"""Rename the project everywhere at once: text, file and directory names, the generated ship structures and the
hashes tests pin for them.

    python3 tools/rename_project.py --id foldpoint --name "Foldpoint" --name-zh 折点

The current names are read from project-names.json and written back there, so the tool can be run again.
--id is the mod id: also the resource namespace, the Java package (dev.<id>), the command (/<id>) and the
jar's base name; lower case letters, digits and underscores. The experience pack's namespace is <id>_pack.
Run from a clean working tree; review with git diff --stat, then build and test.
"""
import argparse, hashlib, json, pathlib, re, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
NAMES = ROOT / 'project-names.json'
STRUCTURES = [ROOT / 'src/main/resources/data' / '{id}' / 'structure' / n for n in ('starter_ship.nbt', 'starter_ship_weapon.nbt')]
BINARY = {'.png', '.ogg', '.jar', '.nbt', '.class', '.zip', '.gz'}


def tracked():
    return [ROOT / p for p in subprocess.run(['git', 'ls-files', '-z'], cwd=ROOT, capture_output=True, check=True).stdout.decode().split('\0') if p]


def rename(pairs, zh_pairs=()):
    """Replace every old with its new, longest old first, in tracked text files and in their paths."""
    pairs = sorted(pairs, key=lambda p: -len(p[0]))
    def swap(text, table):
        for old, new in table:
            text = text.replace(old, new)
        return text
    changed = 0
    for path in tracked():
        if not path.is_file() or path.suffix in BINARY or path == NAMES:
            continue
        try:
            text = path.read_text(encoding='utf-8')
        except UnicodeDecodeError:
            continue
        # The Chinese name stands where Chinese readers see it; everywhere else the display name does.
        table = (sorted(zh_pairs, key=lambda p: -len(p[0])) if path.name.startswith('zh_') or '_zh' in path.name else []) + pairs
        new = swap(text, table)
        if new != text:
            path.write_text(new, encoding='utf-8')
            changed += 1
    moved = 0
    for path in sorted(tracked(), key=lambda p: -len(p.parts)):
        rel = path.relative_to(ROOT).as_posix()
        target = swap(rel, pairs)
        if target != rel:
            (ROOT / target).parent.mkdir(parents=True, exist_ok=True)
            subprocess.run(['git', 'mv', '-k', rel, target], cwd=ROOT, check=True)
            moved += 1
    return changed, moved


def structures(mod_id):
    return [pathlib.Path(str(p).format(id=mod_id)) for p in STRUCTURES]


def digests(mod_id):
    return [hashlib.sha256(p.read_bytes()).hexdigest() for p in structures(mod_id) if p.exists()]


def regenerate(mod_id):
    """The ship structures are compressed NBT holding block ids: rebuild them from the generator, never patch bytes."""
    code = ("import sys,pathlib,tempfile;sys.dont_write_bytecode=True;import shipgen\n"
            "hull=shipgen.runtime_build('B')\n"
            "with tempfile.TemporaryDirectory() as d:\n"
            "    shipgen.export(hull,pathlib.Path(d));pathlib.Path(sys.argv[1]).write_bytes((pathlib.Path(d)/'ship.nbt').read_bytes())\n"
            "    pathlib.Path('commands.txt').write_bytes((pathlib.Path(d)/'commands.txt').read_bytes())\n"
            "pathlib.Path(sys.argv[2]).write_bytes(shipgen.weapon_structure_bytes())\n")
    subprocess.run([sys.executable, '-c', code, *map(str, structures(mod_id))], cwd=ROOT / 'tools/ship/pack', check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--id', required=True)
    parser.add_argument('--name', required=True)
    parser.add_argument('--name-zh')
    parser.add_argument('--map', action='append', default=[], metavar='OLD=NEW', help='an extra replacement, for names that never followed the pattern')
    args = parser.parse_args()
    if not re.fullmatch(r'[a-z][a-z0-9_]{1,62}', args.id):
        parser.error('--id must be lower case letters, digits and underscores')
    if subprocess.run(['git', 'status', '--porcelain', '--untracked-files=no'], cwd=ROOT, capture_output=True, check=True).stdout.strip():
        parser.error('working tree has changes: commit or stash them first')
    now = json.loads(NAMES.read_text(encoding='utf-8'))
    new = {'id': args.id, 'name': args.name, 'name_zh': args.name_zh or args.name}
    before = digests(now['id'])
    pairs = [tuple(m.split('=', 1)) for m in args.map] + [(now['id'], new['id']), (now['name'], new['name'])]
    zh = [(now['name_zh'], new['name_zh']), (now['name'], new['name_zh'])] if new['name_zh'] != new['name'] or now['name_zh'] != now['name'] else []
    if now['name_zh'] != now['name']:
        pairs.append((now['name_zh'], new['name_zh']))
    changed, moved = rename(pairs, zh)
    regenerate(new['id'])
    after = digests(new['id'])
    pinned, _ = rename(list(zip(before, after))) if before != after else (0, 0)
    NAMES.write_text(json.dumps(new, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'{changed} files rewritten, {moved} paths moved, structures regenerated, {pinned} files had a pinned hash updated')
    print('next: build and run the tests; old worlds do not load under a new id')


if __name__ == '__main__':
    main()
