from pathlib import Path
p=Path('tools/probe')
base='''waitworld
wait 60
cmd ride @s dismount
cmd gamemode creative
cmd starfold terrain
wait 160
cmd starfold face 0
wait 100
cmd tp @s 300 168 2040 90 0
wait 140
fly on
cmd effect clear @s
cmd forceload add 0 2032 320 2047
wait 160
'''
for kind in ['boat','minecart']:
 s=base+'''cmd kill @e[tag=cornercarrier]
cmd fill 0 164 2037 320 164 2043 minecraft:blue_ice
cmd fill 16 165 2037 320 170 2043 minecraft:air
wait 40
'''
 if kind=='minecart':s+='cmd fill 0 164 2040 320 164 2040 minecraft:redstone_block\ncmd fill 0 165 2040 320 165 2040 minecraft:powered_rail[shape=east_west,powered=true]\n'
 s+=f'cmd summon minecraft:{kind} 300.5 165.1 2040.5 {{Tags:["cornercarrier"],Rotation:[90f,0f]}}\nwait 20\ncmd ride @s mount @e[tag=cornercarrier,limit=1,sort=nearest]\nwait 20\nfly off\ncornerwatch 10\ncorner mounted\nlook 90 0\n'
 if kind=='minecart':s+='cmd data merge entity @e[tag=cornercarrier,limit=1] {Motion:[-0.4d,0.0d,0.0d]}\n'
 else:s+='key forward on\n'
 s+='wait 1100\nkey forward off\ncorner stopped\ncmd data get entity @e[tag=cornercarrier,limit=1]\nshot corner-'+kind+'-stop\ncmd save-all flush\nwait 60\nquit\n'
 (p/f'corner-{kind}.txt').write_text(s)
import importlib.util
spec=importlib.util.spec_from_file_location('rocket','tools/ship/rocket.py'); rocket=importlib.util.module_from_spec(spec);spec.loader.exec_module(rocket)
fixture='cmd fill 290 171 2030 310 171 2050 minecraft:smooth_stone\ncmd fill 290 172 2030 310 183 2050 minecraft:air\n'
fixture+='\n'.join('cmd '+c for c in rocket.build(300,172,2040,signs=False))+'\nwait 40\ncmd sable assemble area 295 172 2037 305 176 2043\nwait 300\ncmd sable info @n\ncmd tp @s 299.5 173.2 2040.5\nwait 3\nfly off\nwait 60\ncorner boarded\ncmd data get entity @s SelectedItem\ncmd clear @s\nwait 20\ntrace on\nfind typewriter\nwait 6\nusehit helm\nwait 20\nuseifidle\nwait 20\nrequiretypewriter\nfind throttle\nwait 6\nthrottle 0\nwait 20\n'
for height in [172,400,1200]:
 s=base+'cmd tp @s 300.5 190 2040.5 90 20\nwait 3\nfly on\nwait 100\ncmd sable info @e\ncmd sable remove @e\nwait 60\n'+fixture
 if height>172:s+=f'press 32 1\nuntil y> {height} 4000\npress 32 0\nwait 100\n'
 s+='cornerwatch 10\nsample 40\ntrace on\ncorner ship-start\nlook 90 5\npress 87 1\nwait 1000\npress 87 0\nwait 80\ncorner ship-stop\nshot corner-ship-'+str(height)+'\ncmd sable info @e\ncmd save-all flush\nwait 60\nquit\n'
 (p/f'corner-ship-{height}.txt').write_text(s)
