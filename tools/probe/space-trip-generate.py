from pathlib import Path
root = Path(__file__).resolve().parents[2]
s = (root/'tools/probe/rocket-trip.txt').read_text()
s = s[s.index('waitworld'):s.index('until y> 1000')]
s = s.replace('waitworld\nwait 100', 'waitworld\nlog T1-start\nwait 100', 1)
s += '''until y> 1800 4000
log T1-sampling-start
sample 20
until region 1600
log T1-region
abilities T1-region
wait 299
sample 0
log T1-plus15s
abilities T1-plus15s
skylook 0
wait 4
shot space-trip-1-planet
skylook sun
wait 4
shot space-trip-1-sun
throttle ?
cmd sable info @n
cmd data get entity @s Pos
wait 40
quit
'''
(root/'tools/probe/space-t1.txt').write_text(s)
diagnostic = s.replace('T1-', 'T1diag-').replace('sample 20\nuntil region', 'sample 20\nuntil y> 2130 1600\ntrace on\nuntil region').replace('wait 299\nsample 0', 'wait 59\ntrace off\nwait 239\nsample 0').replace('space-trip-1-', 'space-trip-2-')
(root/'tools/probe/space-t1-diagnostic.txt').write_text(diagnostic)
