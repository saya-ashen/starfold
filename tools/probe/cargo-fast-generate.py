from pathlib import Path
import runpy
v=runpy.run_path('tools/probe/cargo-more-generate.py')
# Cleanup stays below native-flight capture height; face resets release any previous controlled flight.
pre='''waitworld
cmd gamemode creative
fly off
cmd execute in starfold:native_planet run tp @s -16384 8192 -16384
wait 80
cmd tp @s 5125 140 857
cmd starfold face 1
wait 40
cmd tp @s 5125 180 857
wait 40
cmd starfold face 1
wait 40
fly on
cmd tp @s -16384 8192 -16384
wait 120
cmd sable info @e
cmd sable remove @e
cmd kill @e[tag=cargo]
wait 40
cmd sable info @e
cmd tp @s 5125 180 857
wait 160
cmd starfold face 1
wait 40
fly on
cmd tp @s 5125 180 857
wait 100
cmd sable info @e
cmd sable remove @e
cmd kill @e[tag=cargo]
wait 40
cmd sable info @e
cmd tp @s 5264 180 864
wait 100
cmd sable info @e
cmd sable remove @e
cmd kill @e[tag=cargo]
wait 40
cmd sable info @e
cmd tp @s SITE
wait 160
cmd sable info @e
cmd sable remove @e
cmd kill @e[tag=cargo]
wait 60
cmd sable info @e
cmd effect give @s minecraft:night_vision infinite 0 true
'''
for name,site in [('g2-ordinary','4120.5 202 864.5'),('g2-rotated','5120.5 202 2024.5'),('g1-clear','5700.5 202 1200.5')]:
 p=Path('tools/probe/cargo-'+name+'.txt'); s=p.read_text();s=pre.replace('SITE',site)+s[s.index('cmd fill ~'):]
 s=s.replace('wait 700','wait 160').replace('shot cargo-','shot cargo-'+name+'-')
 s=s.replace('cmd fill ~','check native if dimension starfold:native_planet\ncmd fill ~',1)
 if name=='g1-clear':s=s.replace('5700.5 402 1200.5','5700.5 262 1200.5')
 p.write_text(s)
