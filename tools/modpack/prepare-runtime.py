"""Stage the pinned survival pack for Gradle -PsurvivalPack runs; never downloads.

The default isolated instance is build/survival-stage1/instance; --instance selects
another directory for independent validation. The Gradle sources supply
Starfold modules, Sable, and GeckoLib; all other exact pinned archives go in mods/.
Existing saves, options, server properties, and player files are left intact.
Only previously managed config/kubejs files are pruned when removed from source;
without a manifest, migration recognizes only legacy chapters 00.snbt..08.snbt.
"""
from pathlib import Path
import hashlib
import json
import shutil
import tomllib

ROOT = Path(__file__).resolve().parents[2]
PACK = ROOT / 'modpack'
DEST = ROOT / 'build/survival-stage1/instance'
CACHE = ROOT / 'build/pi/jars'


def stage_managed_files():
    for name in ('config', 'kubejs'):
        if not (PACK / name).is_dir():
            raise FileNotFoundError(PACK / name)
    sources = {path.relative_to(PACK).as_posix(): path
               for name in ('config', 'kubejs')
               for path in (PACK / name).rglob('*') if path.is_file()}
    manifest = DEST / '.prepare-runtime-managed.json'
    pending = manifest.with_suffix('.json.tmp')
    if manifest.is_symlink() or pending.is_symlink():
        raise ValueError(f'Symlink in managed-file manifest: {manifest}')
    if manifest.exists():
        previous = json.loads(manifest.read_text())
        if not isinstance(previous, list) or not all(isinstance(p, str) for p in previous):
            raise ValueError(f'Invalid managed-file manifest: {manifest}')
        previous = set(previous)
    else:
        # Before manifests, only these exact obsolete pack chapters are identifiable.
        previous = {f'config/ftbquests/quests/chapters/{i:02}.snbt' for i in range(9)}
    for relative in previous | sources.keys():
        path = Path(relative)
        if (path.is_absolute() or len(path.parts) < 2
                or path.parts[0] not in ('config', 'kubejs')
                or '..' in path.parts or path.as_posix() != relative):
            raise ValueError(f'Invalid managed-file path: {relative}')
        # Never follow runtime links into saves or other unmanaged directories.
        if any(DEST.joinpath(*path.parts[:i]).is_symlink()
               for i in range(1, len(path.parts) + 1)):
            raise ValueError(f'Symlink in managed-file path: {relative}')
        target = DEST / relative
        if relative in sources and target.exists() and not target.is_file():
            raise ValueError(f'Directory in managed-file path: {relative}')
    for relative, source in sources.items():
        target = DEST / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    for relative in previous - sources.keys():
        target = DEST / relative
        if target.is_file():
            target.unlink()
    pending.write_text(json.dumps(sorted(sources), indent=2) + '\n')
    pending.replace(manifest)


def main():
    archives = []
    for metadata in sorted((PACK / 'mods').glob('*.pw.toml')):
        pin = tomllib.loads(metadata.read_text())
        archive = CACHE / pin['filename']
        digest = hashlib.new(pin['download']['hash-format'], archive.read_bytes()).hexdigest()
        if digest != pin['download']['hash'] or pin.get('pin') is not True:
            raise ValueError(f'Unverified archive: {metadata}')
        # These two are on the Gradle runtime classpath, already pinned there.
        supplied = metadata.name in ('sable.pw.toml', 'geckolib.pw.toml')
        archives.append((archive, supplied))
    mods = DEST / 'mods'
    mods.mkdir(parents=True, exist_ok=True)
    wanted = {archive.name for archive, supplied in archives if not supplied}
    unexpected = {p.name for p in mods.glob('*.jar')} - wanted
    if unexpected:
        raise ValueError(f'Unexpected instance archives; inspect before removal: {sorted(unexpected)}')
    for archive, supplied in archives:
        if not supplied:
            shutil.copy2(archive, mods / archive.name)
    stage_managed_files()
    (DEST / 'eula.txt').write_text('eula=true\n')
    properties = DEST / 'server.properties'
    if not properties.exists():
        properties.write_text('\n'.join([
            'level-name=regression-settlement-20261109', 'level-seed=20261009',
            'level-type=starfold_worlds:experience', 'gamemode=survival',
            'force-gamemode=false', 'difficulty=normal', 'generate-structures=true',
            'online-mode=false', 'enforce-secure-profile=false', 'allow-flight=true',
            'server-ip=127.0.0.1', 'server-port=25576', 'max-players=4', 'view-distance=6',
            'simulation-distance=6', 'spawn-protection=0', 'max-tick-time=-1',
            'motd=Isolated stage-one survival acceptance', '',
        ]))
    options = DEST / 'options.txt'
    if not options.exists():
        options.write_text('lang:zh_cn\nrenderDistance:6\nsimulationDistance:6\n'
                           'maxFps:60\nenableVsync:false\nguiScale:3\nmipmapLevels:2\n')
    receipt = {'instance': str(DEST), 'archives': [
        {'jar': archive.name, 'supplied_by_gradle': supplied}
        for archive, supplied in archives], 'extras': False}
    (DEST.parent / 'staging.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(f'Staged {len(archives)} verified pinned archives in {DEST}; Extras disabled')


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--instance', type=Path, default=DEST,
                        help='Instance directory; pair with Gradle -PdevGameDirectory using the same path.')
    options = parser.parse_args()
    DEST = options.instance if options.instance.is_absolute() else ROOT / options.instance
    main()
