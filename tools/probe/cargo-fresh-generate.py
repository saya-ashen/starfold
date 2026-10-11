from pathlib import Path
old=Path('tools/probe/cargo-g1-clear.txt').read_text()
# Reuse the proven build/board sequence; fresh face centre, no historical world sites.
pre='''waitworld
wait 120
cmd gamemode creative
cmd sable info @e
cmd sable remove @e
cmd kill @e[tag=cargo]
wait 60
cmd sable info @e
fly off
cmd execute in starfold:native_planet run tp @s -16384 8192 -16384
wait 160
cmd sable info @e
cmd sable remove @e
cmd kill @e[tag=cargo]
wait 60
cmd sable info @e
cmd tp @s 5120.5 140 1024.5
cmd starfold face 1
wait 80
fly on
cmd tp @s SITE
wait 160
cmd sable info @e
cmd sable remove @e
cmd kill @e[tag=cargo]
wait 80
cmd sable info @e
cmd effect give @s minecraft:night_vision infinite 0 true
check native if dimension starfold:native_planet
cmd tp @s SITE
'''
build=old[old.index('cmd fill ~'):old.index('cmd summon minecraft:cow')]
bare=build[:]
bare='\n'.join(l for l in bare.splitlines() if 'minecraft:chest' not in l)+'\n'
Path('tools/probe/cargo-fresh-bare.txt').write_text(pre.replace('SITE','5120.5 202 1024.5')+bare+'''strict
sample 20
trace on
log G0-launch
throttle 15
until region 4000
log G0-region
cargo bare-region
throttle 0
wait 100
shot cargo-10-bare-region
quit
''')
cargo=old[old.index('cmd summon minecraft:cow'):old.index('log G1-launch')]
flight='''strict
log G1-launch
throttle 15
until region 4000
log G1-region
cargo leave
shot cargo-11-leave
throttle 0
wait 600
log G1-coast30
cargo coast30
press 340 1
until chart 3200
log G1-arrive
cargo arrive
shot cargo-11-arrive
requireplanet 0
aboardelsequit
until y< 210 3000
wait 400
press 340 0
wait 80
log G1-land
cargo land
surfacehere
shot cargo-11-land
cargowatch 0
sample 0
trace off
fly off
'''
search=''
for label,site in [('chart','5120.5 202 1024.5'),('region','-16384 8192 -16384')]:
 search+=f'cmd tp @s {site}\nwait 160\nlog search-{label}\ncargo search-{label}\n'
 for kind in ['cow','villager','armor_stand','item','item_frame']:
  search+=f'cmd execute as @e[type=minecraft:{kind}] run data get entity @s Pos\n'
search+='wait 40\nquit\n'
Path('tools/probe/cargo-fresh-g1.txt').write_text(pre.replace('SITE','5120.5 202 1024.5')+build+cargo+flight+search)
