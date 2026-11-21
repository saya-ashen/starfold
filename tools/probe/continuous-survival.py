#!/usr/bin/env python3
"""Run Copper Swift in one copied world, with one planned restart and continuous capture."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

import acceptance as runtime


FIRST = 'continuous-fleck.txt'
SECOND = 'continuous-hearth.txt'
FIXTURE = runtime.PROBES / 'continuous-survival-fixture.js'
HULL_RECEIPT = re.compile(r'PROBE LOOP HULL ([\da-f-]{36},-?\d+,-?\d+,-?\d+)\s*$', re.M)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path, help='Prepared dock world with untouched distillation output')
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--port', type=int, default=25622)
    parser.add_argument('--xvfb', default='Xvfb')
    parser.add_argument('--ffmpeg', default='ffmpeg')
    parser.add_argument('--client-prefix', action='append', default=[])
    parser.add_argument('--baritone-jar', type=Path, help='Official baritone-api-neoforge-1.11.2.jar')
    parser.add_argument('--run', action='store_true', help='Launch the full game test')
    args = parser.parse_args()
    out = args.output.resolve()
    source = args.source.resolve()
    if not out.is_relative_to(runtime.ROOT / 'build') or out.exists() or out.is_relative_to(source):
        parser.error('--output must be a new directory under build/ outside the source')
    if not (source / 'level.dat').is_file():
        parser.error('--source must be an existing Minecraft world')
    if not 1024 <= args.port <= 65535:
        parser.error('invalid loopback port')
    report = {'pass': False, 'source': str(source), 'sessions': [], 'recording': 'continuous.mp4'}
    if not args.run:
        print(json.dumps(report | {'scripts': [FIRST, SECOND]}, indent=2))
        return 0

    if not args.baritone_jar or not args.baritone_jar.is_file():
        parser.error('--run requires --baritone-jar with the official API release')
    digest = hashlib.sha256(args.baritone_jar.read_bytes()).hexdigest()
    if digest != '27d7cb3c715332dc1d85adcbd9c6998db0b97d41808ecfbe06ebce39fe2c8aba':
        parser.error('--baritone-jar must be the pinned NeoForge 1.11.2 API JAR')
    report['baritone'] = {'version': '1.11.2', 'sha256': digest}
    for executable in (args.xvfb, args.ffmpeg, *args.client_prefix):
        if not shutil.which(executable):
            parser.error(f'executable not found: {executable}')
    out.mkdir(parents=True)
    try:
        first, second = (str(Path(shutil.copy2(runtime.PROBES / name, out)).resolve())
                         for name in (FIRST, SECOND))
        fixture = Path(shutil.copy2(FIXTURE, out))
        instance = runtime.stage(out, args.port, fixture)
        shutil.copytree(source, instance / 'regression-stage2-acceptance', ignore=shutil.ignore_patterns('session.lock'))
        options = instance / 'options.txt'
        options.write_text(options.read_text().replace('maxFps:60', 'maxFps:120'))
        gradle = [str(runtime.ROOT / 'gradlew'), '--offline', '--console=plain', '-PsurvivalPack',
                  f'-PdevGameDirectory={instance}', '-PdevProbeUsername=Dev']
        args.record_fps, args.record_width, args.record_crf = 60, 1280, 18
        args.server_timeout, args.client_timeout = 300, 1200
        args.handoff_timeout = 600
        recorder = argparse.Namespace(**vars(args))
        args.ffmpeg = None  # The single recorder belongs to the enclosing display lifetime.
        env = os.environ.copy()
        for key in ('XAUTHORITY', 'WAYLAND_DISPLAY', 'MESA_EXTENSION_OVERRIDE'):
            env.pop(key, None)
        env['JAVA_TOOL_OPTIONS'] = '-Dstarfold.packProbe=true -Dstarfold.shipProbe=true -Dstarfold.helmProbe=true'
        runtime.RECEIPTS[first] = ('SURVIVAL_LOOP_FUEL createdieselgenerators:diesel moved=1000',
                                   'SURVIVAL_LOOP_FUEL createdieselgenerators:gasoline moved=1000',
                                   'SURVIVAL_LOOP_MINING_COLLECTED rawOsmium=8',
                                   'PROBE RECOVERY fleck-offship')
        runtime.RECEIPTS[second] = ('PROBE RECOVERY fleck-recovered-helm', 'SURVIVAL_LOOP_INFUSER_POWER')
        with runtime.display(out, args.xvfb) as name:
            env['DISPLAY'] = name
            with runtime.recording(recorder, env, out, 'continuous'):
                runtime.run_server(args, gradle, env, out, 1, (first,), report['sessions'])
                identities = HULL_RECEIPT.findall((out / 'client-1-1.log').read_text(errors='replace'))
                if len(identities) != 1:
                    raise RuntimeError('Expected one newly identified hull before restarting')
                hull = identities[0].split(',')[0]
                server_log = (out / 'server-1.log').read_text(errors='replace')
                for checkpoint in ('dock-ready', 'fleck-offship'):
                    if not re.search(rf'PROBE HELMSTATE {checkpoint} side server [^\n]* body {hull}\b', server_log):
                        raise RuntimeError(f'Server did not confirm original hull at {checkpoint}')
                report['hull'] = hull
                env['JAVA_TOOL_OPTIONS'] += ' -Dstarfold.probe.loopHull=' + identities[0]
                runtime.run_server(args, gradle, env, out, 2, (second,), report['sessions'])
                server_log = (out / 'server-2.log').read_text(errors='replace')
                for checkpoint in ('fleck-recovered-helm', 'hearth-returned'):
                    if not re.search(rf'PROBE HELMSTATE {checkpoint} side server [^\n]* body {hull}\b', server_log):
                        raise RuntimeError(f'Server did not confirm original hull at {checkpoint}')
        report['pass'] = True
    except (OSError, RuntimeError, subprocess.SubprocessError) as error:
        report['error'] = str(error)
        print(error, file=sys.stderr)
    finally:
        for log in sorted(out.glob('client-*.log')):
            text = log.read_text(errors='replace')
            checkpoints = re.findall(r'PROBE CHECK PASS (\S+)', text)
            failures = re.findall(r'PROBE FAIL CLOSED ([^\n]+)', text)
            identities = HULL_RECEIPT.findall(text)
            if identities:
                report.setdefault('hull', identities[0].split(',')[0])
            report['lastClientLog'] = log.name
            if checkpoints:
                report['lastCheckpoint'] = checkpoints[-1]
            if failures:
                report['probeFailure'] = failures[-1]
        (out / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
    return 0 if report['pass'] else 1


if __name__ == '__main__':
    sys.exit(main())
