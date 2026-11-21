#!/usr/bin/env python3
"""Summarize one Devtools capture. Intervals use capture-relative monotonic timestamps."""
import argparse
from collections import Counter, defaultdict
import csv
import math
from pathlib import Path
import re
from statistics import median


CARDINAL = ((-1, 0), (1, 0), (0, -1), (0, 1))


def read(path):
    with path.open(newline='') as source:
        return list(csv.DictReader(source))


def key(row):
    return int(row['chunk_x']), int(row['chunk_z'])


def at(row):
    return int(row['elapsed_ns'])


def interval(start, finish):
    if start is None or finish is None or finish < start:
        return None
    return finish - start


def stats(name, values):
    values = sorted(x / 1e6 for x in values if x is not None)
    if values:
        p95 = values[math.ceil(len(values) * .95) - 1]
        print(f'{name:44} {len(values):6}  {median(values):9.2f}  {p95:9.2f}  {values[-1]:9.2f}')
    else:
        print(f'{name:44}      0')


def report(path):
    suffix = path.name.removeprefix('chunks-')
    rows = read(path)
    chunks = defaultdict(lambda: defaultdict(list))
    for row in rows:
        chunks[key(row)][row['stage']].append(row)
    counts = Counter(row['stage'] for row in rows)
    print('Stage counts:', ', '.join(f'{k}={v}' for k, v in sorted(counts.items())))
    print(f'{"Interval (ms)":44} {"n":>6}  {"p50":>9}  {"p95":>9}  {"max":>9}')

    def once(events, name):
        batch = events.get(name, [])
        return at(batch[0]) if len(batch) == 1 else None

    durations = defaultdict(list)
    request_counts = Counter()
    for events in chunks.values():
        submitted = once(events, 'FULL_CONVERSION_SUBMITTED')
        dispatched = once(events, 'FULL_CONVERSION_DISPATCHED')
        conversion = once(events, 'FULL_CONVERSION')
        if interval(submitted, dispatched) is not None and interval(dispatched, conversion) is not None:
            durations['FULL conversion: submit → dispatch'].append(dispatched - submitted)
            durations['FULL conversion: dispatch → main thread'].append(conversion - dispatched)
        request = once(events, 'FULL_REQUEST')
        if len(events['FULL_REQUEST']) > 1:
            request_counts['ambiguous coordinates'] += 1
        if request is not None:
            deps = once(events, 'FULL_DEPENDENCIES_READY')
            ready = once(events, 'FULL_READY')
            request_counts['complete' if interval(request, ready) is not None else 'without matched ready'] += 1
            fill = once(events, 'FILL')
            label = 'New' if fill is not None else 'All other'
            for name, start, finish in (
                ('request → dependencies', request, deps),
                ('dependencies → callback', deps, once(events, 'FULL_CALLBACK')),
                ('request → ready', request, ready),
            ):
                durations[f'{label}: {name}'].append(interval(start, finish))
        sent = events['IMAGE_SENT']
        if len(events['IMAGE_FIRST_ATTEMPT']) == 1:
            request_counts['unique image attempt with send' if once(events, 'IMAGE_SENT') is not None
                           else 'unique image attempt without matched send'] += 1
        if len(sent) == 1:
            image = sent[0]
            send = at(image)
            if image['source_x'] and image['source_z']:
                source = chunks.get((int(image['source_x']), int(image['source_z'])), {})
                ready = once(source, 'FULL_READY')
                if ready is not None and ready > send:
                    request_counts['image sent before matched ready'] += 1
                durations['Source ready → image sent'].append(interval(ready, send))
                load = once(events, 'CLIENT_LOAD')
                draw = once(events, 'FIRST_DRAW')
                if interval(send, load) is not None and interval(load, draw) is not None:
                    request = once(source, 'FULL_REQUEST')
                    if interval(request, send) is not None:
                        durations['Source request → first draw'].append(draw - request)
            durations['First stream attempt → sent'].append(interval(once(events, 'IMAGE_FIRST_ATTEMPT'), send))
            durations['Image sent → client load'].append(interval(send, once(events, 'CLIENT_LOAD')))
        load = once(events, 'CLIENT_LOAD')
        draw = once(events, 'FIRST_DRAW')
        if load is not None:
            request_counts['unique client load with draw' if interval(load, draw) is not None else 'unique client load without matched draw'] += 1
        if interval(load, draw) is None:
            continue
        durations['Client load → first draw'].append(draw - load)
        section = events['FIRST_DRAW'][0].get('section_y')
        if not section:
            continue

        def section_event(name):
            matches = [r for r in events[name] if r.get('section_y') == section and load <= at(r) <= draw]
            return matches[0] if len(matches) == 1 else None

        visible = section_event('CLIENT_SECTION_VISIBLE')
        scheduled = section_event('CLIENT_MESH_SCHEDULED')
        compiled = section_event('CLIENT_MESH_COMPILED')
        for name, event in [('Visible list', visible), ('Mesh task creation', scheduled)]:
            if event is not None:
                durations[f'Client load → {name}'].append(at(event) - load)
        light = once(events, 'CLIENT_LIGHT_APPLIED')
        if light is not None and load <= light <= draw:
            durations['Client load → packet light applied'].append(light - load)
        x, z = key(events['FIRST_DRAW'][0])
        neighbor_loads = [once(chunks.get((x + dx, z + dz), {}), 'CLIENT_LOAD') for dx, dz in CARDINAL]
        if visible is not None and all(t is not None for t in neighbor_loads):
            last = max(neighbor_loads)
            if last <= at(visible):
                durations['Client load → final neighbor if later'].append(max(0, last - load))
                durations['Final neighbor → visible list'].append(at(visible) - last)
        if compiled is not None:
            duration = int(compiled['duration_ns'])
            durations['CPU compilation of first draw section'].append(duration)
            durations['Compile end → first draw'].append(draw - at(compiled))
            if scheduled is not None:
                durations['Mesh task creation → compile start'].append(interval(at(scheduled), at(compiled) - duration))
    for name, values in sorted(durations.items()):
        stats(name, values)
    for name in ('FILL_QUEUED', 'FILL', 'COLUMN_EVALUATION', 'SECTION_WRITE', 'BASE_HEIGHT', 'FULL_CONVERSION', 'IMAGE_SENT'):
        stats(name + ' duration', (int(r['duration_ns']) for r in rows if r['stage'] == name))
    generation = path.with_name(f'generation-{suffix}')
    if generation.exists():
        stages = defaultdict(list)
        by_chunk = defaultdict(lambda: defaultdict(list))
        for row in read(generation):
            status = row['status'].removeprefix('minecraft:')
            duration = int(row['duration_ns'])
            stages[status].append(duration)
            by_chunk[key(row)][status].append((at(row), at(row) + duration))
        for name, values in sorted(stages.items()):
            stats('Generation / ' + name, values)
        full_wait = []
        for coord, statuses in by_chunk.items():
            native = chunks.get(coord, {}).get('FULL_CONVERSION', [])
            if len(native) == len(statuses['full']) == 1:
                start, finish = statuses['full'][0]
                execution = at(native[0])
                # JFR uses a wall-clock anchor taken next to the monotonic anchor.
                if start <= execution <= finish:
                    full_wait.append(execution - start)
        stats('Generation / FULL before main-thread body', full_wait)
        order = 'structure_starts structure_references biomes noise surface carvers features initialize_light light spawn full'.split()
        for earlier, later in zip(order, order[1:]):
            stats(f'Gap / {earlier} → {later}', (interval(events[earlier][0][1], events[later][0][0])
                  for events in by_chunk.values() if len(events[earlier]) == len(events[later]) == 1))
    frames = path.with_name(f'profile-{suffix}')
    if frames.exists():
        frame_rows = read(frames)
        if frame_rows:
            stats('Frame interval', (float(r['frame_ms']) * 1e6 for r in frame_rows))
    summary = path.with_name(f'profile-{suffix}.summary.txt')
    if summary.exists():
        dropped = re.findall(r'^([A-Z_]+) recorded=\d+ dropped=([1-9]\d*)', summary.read_text(), re.M)
        print('Dropped stage events:', ', '.join(f'{s}={n}' for s, n in dropped) or '0')
    print('Pairing coverage:', ', '.join(f'{k}={v}' for k, v in sorted(request_counts.items())))
    print('Repeated coordinates and unordered spans are excluded from each pairing. Missing draws can include out-of-view or empty chunks.')


if __name__ == '__main__':
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('chunks_csv', type=Path, help='chunks-*.csv with its companion files in the same directory')
    report(cli.parse_args().chunks_csv)
