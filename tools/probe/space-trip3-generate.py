from pathlib import Path
base=Path('tools/probe/space-trip2-b-fresh.txt').read_text()
cleanup='''waitworld
log R-clean-login
wait 120
cmd sable info @e
cmd sable remove @e
wait 100
cmd sable info @e
cmd gamemode creative
fly on
cmd tp @s -16078 4551 -8095
wait 200
cmd sable info @e
cmd sable remove @e
wait 100
cmd sable info @e
cmd tp @s -16476 15581 -16565
wait 200
cmd sable info @e
cmd sable remove @e
wait 100
cmd sable info @e
cmd tp @s -16384 8192 -16384
wait 200
cmd sable info @e
cmd sable remove @e
wait 100
cmd sable info @e
'''
setup=cleanup+base[base.index('waitworld\ncmd gamemode creative'):base.index('log T4b-fresh-launch')]
setup=setup.replace('cmd sable remove @e\ncmd fill','cmd sable info @e\ncmd sable remove @e\nwait 80\ncmd sable info @e\ncmd fill')
setup=setup.replace('usehit helm\nwait 10','usehit helm\nwait 20\nuseifidle\nwait 20\nrequiretypewriter')
r1='''log R1-launch
sample 20
until y> 1920 4000
trace on
until region 1600
log R1-entry
wait 400
log R1-full20
throttle 0
log R1-off
until w< 1 2000
log R1-below1
wait 200
log R1-end
skylook 1
wait 4
shot space-trip3-1-B
cmd sable info @e
trace off
sample 0
quit
'''
Path('tools/probe/space-trip3-r1.txt').write_text(setup+r1)

def arrival(planet,name):
 return f"""requirechart
requireplanet {planet}
log {name}-arrival
abilities {name}-arrival
cmd sable info @n
look 90 60
wait 4
shot space-trip3-2-{name}-arrival
wait 396
log {name}-plus20
abilities {name}-plus20
throttle ?
census 16
aboardelsequit
requiretypewriter
press 340 1
wait 3200
press 340 0
wait 80
log {name}-land
surfacehere
census 16
abilities {name}-land
cmd sable info @n
look 90 30
wait 4
shot space-trip3-2-{name}-land
"""
r2="""log R2-launch
sample 20
until y> 1920 4000
trace on
until region 1600
log R2-entry
skylook 1
wait 4
shot space-trip3-2-mid-B
until ref 1 4000
throttle 0
log R2-reference
requireref 1
wait 1200
log R2-turned-rest
heading 1 400
wait 30
skylook 1
wait 4
shot space-trip3-2-B-aligned
press 340 1
until chart 3600
press 340 0
throttle 0
"""+arrival(1,'R3-B')+"""requiretypewriter
aboardelsequit
throttle 15
log R4-takeoff
until region 4000
log R4-region
throttle 0
wait 1000
heading 0 400
wait 30
log R4-heading
skylook 0
wait 4
shot space-trip3-2-mid-A
burn 0
until ref 0 4000
press 87 0
press 341 0
press 340 0
throttle 0
log R4-reference
requireref 0
wait 1200
log R4-turned-rest
skylook 0
wait 4
shot space-trip3-2-A-aligned
press 340 1
until chart 4000
press 340 0
throttle 0
"""+arrival(0,'R4-A')+"""log R4-end
trace off
sample 0
quit
"""
Path('tools/probe/space-trip3-tour.txt').write_text(setup+r2)
s=(setup+r2).replace('space-trip3-2-','space-trip3-3-')
s=s.replace('press 340 1\nwait 3200\npress 340 0','press 340 1\nuntil y< 180 3200\nwait 400\npress 340 0')
s=s.replace('wait 1000\nheading 0','wait 400\nheading 0')
s=s.replace('burn 0\nuntil ref 0 4000','log R4-tangent-burn\npress 87 1\npress 341 1\nuntil ref 0 3600')
Path('tools/probe/space-trip3-tangent.txt').write_text(s)
