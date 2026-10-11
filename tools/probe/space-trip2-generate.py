from pathlib import Path
base=Path('tools/probe/space-carry-verified.txt').read_text().split('until y> 1800')[0]
base=base.replace('log T1diag-start\nwait 100','log T4-cleanup\nwait 100\ncmd sable info @e\ncmd sable remove @e\nwait 40\ncmd sable info @e').replace('leadsite 3720 262','leadsite 4800 262')
tail='''log T4a-launch
sample 20
until y> 2130 4000
trace on
until region 1600
log T4a-region
until ref 1 3400
log T4a-reference-end
skylook 0
wait 4
shot space-trip2-2-mid-planet0
skylook 1
wait 4
shot space-trip2-2-mid-planet1
skylook sun
wait 4
shot space-trip2-2-mid-sun
throttle 0
wait 1200
log T4a-end
throttle ?
cmd sable info @e
trace off
sample 0
quit
'''
Path('tools/probe/space-trip2-a-clean.txt').write_text(base+tail)
