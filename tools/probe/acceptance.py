#!/usr/bin/env python3
"""Run a named Escape Velocity acceptance slice in a fresh local dev instance."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import select
import shutil
import signal
import socket
import subprocess
import sys
import time
import uuid
from contextlib import contextmanager

from verify import failures

ROOT = Path(__file__).resolve().parents[2]
PROBES = Path(__file__).resolve().parent
# Each tuple is one dedicated-server lifetime and its ordered client sessions.
SLICES = {
    'startup': (('acceptance-join.txt', 'acceptance-join.txt'),),
    'workshop': (('stage2-industry-workshop.txt',),),
    'distillation': (('stage2-industry-boundary.txt',), ('stage2-industry-rejoin.txt',)),
    'oil': (('stage2-oil.txt',), ('stage2-oil-rejoin.txt',)),
    'diesel-flight': (('acceptance-diesel-flight.txt',),),
    'drive-crafting': (('acceptance-drive-crafting.txt',),),
    'hydrogen': (('acceptance-hydrogen.txt',),),
    'host': (('acceptance-host.txt',), ('acceptance-host-rejoin.txt',)),
}
RECEIPTS = {
    'stage2-industry-workshop.txt': ('workshop=OBSERVED_OUTPUT', 'precision=COMPLETED_FIVE_LOOPS'),
    'stage2-industry-boundary.txt': ('distillation=OBSERVED_1000_TO_500_500 batches=2', 'INDUSTRY_LIQUID_CHECKPOINT'),
    'stage2-industry-rejoin.txt': ('liquidPersistence=EQUAL sixPhysicalTanks=true',),
    'stage2-oil.txt': ('OIL_TEST_ASSERT_PASS total initialArtificial=5000', 'OIL_TEST_MUTATION save paused checkpoint'),
    'stage2-oil-rejoin.txt': ('OIL_TEST_ASSERT_PASS persist fullNBT=equal',),
    'acceptance-drive-crafting.txt': ('KP_MACHINES {"action":"readcraft"', 'KP_MACHINES {"action":"readspout"'),
    'acceptance-hydrogen.txt': ('HYDROGEN_TEST product=mekanism:hydrogen amount=100',
                                'HYDROGEN_TEST product=mekanism:hydrogen amount=200'),
    'acceptance-host.txt': ('ACCEPTANCE_HIVE site=',),
}


@contextmanager
def process(command, log, **options):
    with log.open('w') as output:
        job = subprocess.Popen(command, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT,
                               start_new_session=True, **options)
    try:
        yield job
    finally:
        if job.poll() is None:
            os.killpg(job.pid, signal.SIGTERM)
            try:
                job.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(job.pid, signal.SIGKILL)
                job.wait()
        if job.stdin:
            job.stdin.close()


@contextmanager
def display(out, xvfb):
    read_fd, write_fd = os.pipe()
    with os.fdopen(read_fd) as reply:
        try:
            with process([xvfb, '-displayfd', str(write_fd), '-screen', '0', '1280x800x24',
                          '-nolisten', 'tcp'], out / 'display.log', pass_fds=(write_fd,)) as job:
                os.close(write_fd)
                write_fd = None
                if not select.select([reply], [], [], 15)[0]:
                    raise RuntimeError('Xvfb did not assign a display')
                number = reply.readline().strip()
                if not number.isdecimal() or job.poll() is not None:
                    raise RuntimeError('Xvfb failed; see display.log')
                yield f':{number}'
        finally:
            if write_fd is not None:
                os.close(write_fd)


def stage(out, port, fixture=None):
    instance = out / 'instance'
    subprocess.run([sys.executable, str(ROOT / 'tools/modpack/prepare-runtime.py'),
                    '--instance', str(instance)], cwd=ROOT, check=True)
    if fixture:
        shutil.copy2(fixture, instance / 'kubejs/server_scripts/acceptance-fixture.js')
    properties = instance / 'server.properties'
    settings = properties.read_text().splitlines()
    replacements = {'server-port': str(port), 'level-name': 'regression-stage2-acceptance',
                    'server-ip': '127.0.0.1', 'enable-rcon': 'false'}
    properties.write_text('\n'.join([*(line for line in settings if line.split('=', 1)[0] not in replacements),
                                    *(f'{key}={value}' for key, value in replacements.items())]) + '\n')
    player = uuid.UUID(bytes=hashlib.md5(b'OfflinePlayer:Dev').digest(), version=3)
    (instance / 'ops.json').write_text(json.dumps([
        {'uuid': str(player), 'name': 'Dev', 'level': 4, 'bypassesPlayerLimit': False}]) + '\n')
    config = instance / 'config'
    config.mkdir(exist_ok=True)
    (config / 'fml.toml').write_text('earlyWindowControl = false\n')
    return instance


def verify_session(script, client_log, server_log):
    errors = failures(client_log)
    if not re.search(r'\[Server thread/INFO\].*\bDev joined the game\b', server_log):
        errors.append('no server login receipt')
    if not re.search(r'\[Server thread/INFO\].*\[Dev\] CP_PROBE_SERVER_ACK\b', server_log):
        errors.append('no server ACK in this session')
    labels = re.findall(r'^check (\S+)', (PROBES / script).read_text(), re.M)
    observed = iter(re.findall(r'PROBE CHECK PASS (\S+)\s*$', client_log, re.M))
    for label in labels:
        if label not in observed:
            errors.append(f'missing or out-of-order probe check: {label}')
            break
    for receipt in RECEIPTS.get(script, ()):
        if not re.search(r'\[(?:starfold-probe|KubeJS Server)/\].*' + re.escape(receipt), server_log):
            errors.append(f'missing domain receipt: {receipt}')
    return errors


@contextmanager
def recording(args, env, out, name):
    path = out / f'{name}.mp4'
    with process([args.ffmpeg, '-nostdin', '-loglevel', 'warning', '-f', 'x11grab',
                  '-framerate', str(args.record_fps), '-video_size', '1280x800', '-i', env['DISPLAY'],
                  '-vf', f'scale={args.record_width}:-2', '-c:v', 'libx264', '-preset', 'veryfast',
                  '-crf', str(args.record_crf), '-pix_fmt', 'yuv420p', '-movflags', '+faststart',
                  str(path)], out / f'{name}-recording.log', env=env) as video:
        try:
            yield
        finally:
            if video.poll() is None:
                video.send_signal(signal.SIGINT)
                video.wait(timeout=30)
    if video.returncode not in (0, 255) or not path.is_file() or not path.stat().st_size:
        raise RuntimeError(f'Failed recording {path.name}')


def wait_for_client(args, client, env, out, log, tag):
    handoff_timeout = getattr(args, 'handoff_timeout', 0)
    if not handoff_timeout:
        return client.wait(timeout=args.client_timeout)
    deadline = time.monotonic() + args.client_timeout
    with log.open(errors='replace') as output:
        while client.poll() is None:
            if any('PROBE HANDOFF READY' in line for line in output):
                handoff = out / f'handoff-{tag}.json'
                handoff.write_text(json.dumps({
                    'display': env.get('DISPLAY'),
                    'request': str(out / 'instance' / 'probe-handoff-client.txt'),
                    'clientLog': str(log),
                    'serverLog': str(out / f'server-{tag.split("-")[0]}.log'),
                    'timeoutSeconds': handoff_timeout,
                }, indent=2) + '\n')
                print(f'Live probe handoff: {handoff}', flush=True)
                return client.wait(timeout=handoff_timeout)
            if time.monotonic() >= deadline:
                raise subprocess.TimeoutExpired(client.args, args.client_timeout)
            time.sleep(.25)
    return client.returncode


def run_client(args, gradle, env, out, script, tag):
    baritone = getattr(args, 'baritone_jar', None)
    if baritone is None:
        return run_client_session(args, gradle, env, out, script, tag)
    installed = Path(shutil.copy2(baritone, out / 'instance' / 'mods'))
    try:
        return run_client_session(args, gradle, env, out, script, tag)
    finally:
        installed.unlink()


def run_client_session(args, gradle, env, out, script, tag):
    log = out / f'client-{tag}.log'
    client_env = env | {'JAVA_TOOL_OPTIONS': (env.get('JAVA_TOOL_OPTIONS', '')
                                             + f' -Dstarfold.probe="{PROBES / script}"').strip()}
    if getattr(args, 'handoff_timeout', 0):
        client_env['JAVA_TOOL_OPTIONS'] += ' -Dstarfold.probe.handoff=true'
    command = [*args.client_prefix, *gradle, 'runClient', '-PdevProbe', f'-PdevProbePort={args.port}']
    if args.ffmpeg and args.xvfb:
        with recording(args, env, out, f'client-{tag}'):
            with process(command, log, env=client_env, stdin=subprocess.DEVNULL) as client:
                code = wait_for_client(args, client, env, out, log, tag)
    else:
        with process(command, log, env=client_env, stdin=subprocess.DEVNULL) as client:
            code = wait_for_client(args, client, env, out, log, tag)
    text = log.read_text(errors='replace')
    for name in re.findall(r'PROBE SHOT ([\w.-]+\.png) wall ', text):
        screenshot = out / 'instance' / 'screenshots' / name
        if screenshot.is_file():
            shutil.copy2(screenshot, out / f'client-{tag}-{name}')
    if code:
        raise RuntimeError(f'{script} exited {code}; see {log.name}')
    return text


def run_server(args, gradle, env, out, index, scripts, results):
    with socket.socket() as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(('127.0.0.1', args.port))
    log = out / f'server-{index}.log'
    with process([*gradle, '--init-script', str(PROBES / 'acceptance-stdin.gradle'), 'runServer'],
                 log, env=env, stdin=subprocess.PIPE, text=True) as server:
        try:
            deadline = time.monotonic() + args.server_timeout
            while 'Done (' not in log.read_text(errors='replace'):
                if server.poll() is not None or time.monotonic() >= deadline:
                    raise RuntimeError(f'Server did not become ready; see {log.name}')
                time.sleep(1)
            for client_index, script in enumerate(scripts, 1):
                with log.open('rb') as receipts:
                    receipts.seek(0, os.SEEK_END)
                    client_log = run_client(args, gradle, env, out, script, f'{index}-{client_index}')
                    server_log = receipts.read().decode(errors='replace')
                problems = verify_session(script, client_log, server_log)
                results.append({'server': index, 'client': client_index, 'script': script,
                                'pass': not problems, 'problems': problems})
                if problems:
                    raise RuntimeError(f'{script}: ' + '; '.join(problems))
        finally:
            if server.poll() is None:
                server.stdin.write('save-all flush\nstop\n')
                server.stdin.flush()
                server.wait(timeout=90)
        if server.returncode or 'All dimensions are saved' not in log.read_text(errors='replace'):
            raise RuntimeError(f'Server did not save and stop successfully; see {log.name}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('slice', choices=SLICES)
    parser.add_argument('--list', action='store_true', help='Print the execution plan without running it')
    parser.add_argument('--run', action='store_true', help='Explicitly launch the game')
    parser.add_argument('--output', type=Path, help='New run directory below build/')
    parser.add_argument('--port', type=int, default=25589)
    parser.add_argument('--server-timeout', type=int, default=300)
    parser.add_argument('--client-timeout', type=int, default=600)
    parser.add_argument('--xvfb', metavar='EXECUTABLE', help='Start a private X11 display')
    parser.add_argument('--ffmpeg', metavar='EXECUTABLE', help='Record MP4 (requires --xvfb)')
    parser.add_argument('--record-fps', type=int, choices=(10, 30, 60), default=10)
    parser.add_argument('--record-width', type=int, choices=(960, 1280), default=960)
    parser.add_argument('--record-crf', type=int, choices=range(16, 35), default=28, metavar='16..34')
    parser.add_argument('--client-prefix', action='append', default=[], help='Prefix for client command (e.g. steam-run)')
    parser.add_argument('--fixture-script', type=Path, help='Install a test-only KubeJS script in the isolated instance')
    args = parser.parse_args()
    if args.list or not args.run:
        print(json.dumps({'slice': args.slice, 'server_sessions': SLICES[args.slice]}, indent=2))
        return 0
    if not args.output or not args.output.resolve().is_relative_to(ROOT / 'build'):
        parser.error('--output must be a new run directory below build/')
    if not 1024 <= args.port <= 65535 or min(args.server_timeout, args.client_timeout) <= 0:
        parser.error('invalid port or timeout')
    if args.ffmpeg and not args.xvfb:
        parser.error('--ffmpeg requires --xvfb')
    if not args.xvfb and not os.environ.get('DISPLAY'):
        parser.error('provide --xvfb or an existing X11 DISPLAY')
    if args.slice in ('drive-crafting', 'hydrogen', 'host') and not args.fixture_script:
        parser.error('this slice requires --fixture-script, see ACCEPTANCE.md')
    if args.fixture_script and not args.fixture_script.is_file():
        parser.error('--fixture-script must point to an existing file')
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    results = []
    report = {'slice': args.slice, 'runtime': 'pinned Gradle survivalPack plus probes', 'pass': False, 'sessions': results}
    try:
        instance = stage(out, args.port, args.fixture_script)
        gradle = [str(ROOT / 'gradlew'), '--offline', '--console=plain', '-PsurvivalPack',
                  f'-PdevGameDirectory={instance}', '-PdevProbeUsername=Dev']
        env = os.environ.copy()
        if args.slice == 'host':
            env['JAVA_TOOL_OPTIONS'] = (env.get('JAVA_TOOL_OPTIONS', '') + ' -Dstarfold.packProbe=true').strip()
        if args.xvfb:
            env.pop('XAUTHORITY', None)
            env.pop('WAYLAND_DISPLAY', None)
            with display(out, args.xvfb) as name:
                for index, scripts in enumerate(SLICES[args.slice], 1):
                    run_server(args, gradle, env | {'DISPLAY': name}, out, index, scripts, results)
        else:
            for index, scripts in enumerate(SLICES[args.slice], 1):
                run_server(args, gradle, env, out, index, scripts, results)
        report['pass'] = True
    except (OSError, RuntimeError, subprocess.SubprocessError) as error:
        report['error'] = str(error)
        print(error, file=sys.stderr)
    finally:
        (out / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
    return 0 if report['pass'] else 1


if __name__ == '__main__':
    sys.exit(main())
