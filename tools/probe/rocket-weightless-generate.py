#!/usr/bin/env python3
"""Independent disposable-world runs; each fits a 360-second client timeout."""
from pathlib import Path
root = Path(__file__).resolve().parents[2]
original = (root / 'tools/probe/rocket-weightless.txt').read_text()
fixture = original.split('waitworld', 1)[1].split('cmd attribute @s minecraft:generic.gravity base set 0', 1)[0]
fixture = fixture.replace('find typewriter\n', 'find throttle\nwait 5\nthrottle ?\nfind typewriter\n')
fixture = fixture.replace('wait 40\nlog assembled', 'cmd tellraw @s "RW_ORIGIN"\ncmd sable info @n\nwait 40\nlog assembled')
fixture = fixture.replace('cmd gamemode creative', 'cmd attribute @s minecraft:generic.gravity base set 0.08\ncmd gamemode creative')
for case in ('still', 'forward', 'vertical', 'turn', 'long', 'throttle'):
    lines = ['# Disposable devworld-create1 only; no Escape before waitworld.', 'waitworld', f'log RW_START-{case}']
    lines.extend(fixture.strip().splitlines())
    lines.extend(['cmd attribute @s minecraft:generic.gravity base set 0', 'wait 39', 'cmd attribute @s minecraft:generic.gravity base get'])
    def sample(label):
        lines.extend([f'log {label}', f'abilities {label}', f'cmd tellraw @s "RW_SAMPLE {label}"', 'cmd time query gametime', 'cmd data get entity @s Pos', 'cmd sable info @n'])
    def measure(label, ticks):
        for tick in range(20, ticks + 1, 20):
            lines.append('wait 19')
            sample(f'{label}-t{tick}')
    def press(key, state):
        lines.append(f'press {key} {state}')
    sample(case + '-baseline-t0')
    if case == 'still':
        measure('still', 100)
    elif case in ('forward', 'long'):
        press(87, 1); press(341, 1)
        measure(case, 100 if case == 'forward' else 300)
        press(341, 0); press(87, 0)
        measure(case + '-brake', 100)
    elif case == 'vertical':
        press(32, 1); measure('up', 60); press(32, 0)
        measure('up-brake', 60)
        press(340, 1); measure('down', 60); press(340, 0)
        measure('down-brake', 60)
    elif case == 'turn':
        press(65, 1); measure('turn', 120); press(65, 0)
        measure('turn-brake', 100)
    else:
        lines.append('throttle 15'); measure('throttle', 400)
        lines.append('throttle 0'); measure('throttle-brake', 100)
    lines.extend(['cmd attribute @s minecraft:generic.gravity base set 0.08', 'wait 19', 'cmd attribute @s minecraft:generic.gravity base get', 'wait 19', 'quit'])
    out = root / f'build/space/weightless-{case}.txt'
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text('\n'.join(lines) + '\n')
    if case == 'still':
        (root / 'tools/probe/rocket-weightless-measured.txt').write_text(out.read_text())
    print(out.relative_to(root), len(lines), 'lines')
