from pathlib import Path
old=Path('tools/probe/space-trip3-tangent.txt').read_text()
setup=old[:old.index('log R2-launch')]
# The pilot boards at relative x=-0.5,z=+0.5; replace only the floor directly below, not any thruster.
setup=setup.replace('wait 10\ncmd tp @s ~ ~14 ~','cmd setblock ~-1 ~-2 ~ minecraft:glass\ncmd setblock ~-1 ~-2 ~1 minecraft:glass\nwait 10\ncmd tp @s ~ ~14 ~')
def arrival(p):
 return f'''requirechart
requireplanet {p}
log L{2 if p==0 else 1}-arrival
look 0 90
wait 4
shot space-trip4-arrival-{p}
wait 396
log L{2 if p==0 else 1}-plus20
abilities arrival
cmd sable info @e
aboardelsequit
requiretypewriter
press 340 1
until y< 170 3000
wait 400
press 340 0
wait 80
log L{2 if p==0 else 1}-land
surfacehere
census 16
abilities land
cmd sable info @e
look 0 60
wait 4
shot space-trip4-land-{p}
'''
s=setup+'''log L1-launch
sample 20
until y> 1900 4000
trace on
until region 1600
log L1-region
throttle 0
steer 1 420
'''+arrival(1)+'''log L2-launch
throttle 15
until region 4000
log L2-region
throttle 0
steer 0 540
'''+arrival(0)+'''log L3-launch
throttle 15
until region 4000
log L3-region
wait 200
throttle 0
wait 800
log L3-save
abilities L3-save
cmd sable info @e
look 0 0
wait 4
shot space-trip4-save
quit
'''
Path('tools/probe/space-trip4-tour.txt').write_text(s)
Path('tools/probe/space-trip4-rejoin.txt').write_text('''waitworld
log L3-login
trace on
sample 20
cmd sable info @e
wait 200
log L3-loaded
abilities L3-loaded
cmd sable info @e
find typewriter
wait 6
hit rejoin-helm
usehit rejoin-first
wait 40
log L3-one-click
useifidle
wait 40
log L3-two-click
requiretypewriter
wait 200
log L3-rejoined
cmd sable info @e
look 0 0
wait 4
shot space-trip4-rejoined
quit
''')
