from pathlib import Path
p=Path('tools/probe')
old=(p/'space-trip4-tour.txt').read_text()
setup=old[old.index('waitworld\ncmd gamemode creative'):old.index('log L1-launch')]
setup=setup.replace('cmd sable remove @e\n','')
# Preserve daylight progression, freshly generated world, one assembly only.
a='''cmd execute at @s run summon marker ~8 ~-2 ~8 {Tags:["loop_a"]}
cmd execute at @s run forceload add ~-32 ~-32 ~32 ~32
cmd execute at @e[tag=loop_a,limit=1] run setblock ~ ~ ~ chest{Items:[{Slot:0b,id:"minecraft:cobblestone",count:17},{Slot:5b,id:"minecraft:iron_ingot",count:3},{Slot:26b,id:"minecraft:oak_log",count:9}]}
cmd execute at @e[tag=loop_a,limit=1] run setblock ~1 ~ ~ gold_block
cmd execute at @e[tag=loop_a,limit=1] run setblock ~2 ~ ~ lapis_block
cmd execute at @e[tag=loop_a,limit=1] run setblock ~2 ~1 ~ redstone_block
'''
setup=setup.replace('wait 10\ncmd tp @s ~ ~14 ~',a+'wait 10\ncmd tp @s ~ ~14 ~')
def check(tag,stage):
 s=f'log {stage}-checks\ncmd data get entity @e[tag=loop_{tag},limit=1] Pos\n'
 off='~-10' if tag=='b' else '~'
 s+=f'cmd execute at @e[tag=loop_{tag},limit=1] run data get block ~ {off} ~ Items\n'
 for slot in (0,5,26):s+=f'cmd execute at @e[tag=loop_{tag},limit=1] run data get block ~ {off} ~ Items[{{Slot:{slot}b}}]\n'
 blocks=('gold_block','lapis_block','redstone_block') if tag=='a' else ('copper_block','amethyst_block','emerald_block')
 for xyz,block in zip(('~1 ~ ~','~2 ~ ~','~2 ~1 ~'),blocks):s+=f'cmd execute at @e[tag=loop_{tag},limit=1] if block {xyz} {block} run say CHECK_{stage}_{block}_PASS\n'
 if tag=='b':
  for y in range(1,11):s+=f'cmd execute at @e[tag=loop_b,limit=1] if block ~-1 ~-{y} ~ air run say CHECK_{stage}_shaft_{y}_PASS\n'
  s+='cmd execute at @e[tag=loop_b,limit=1] run data get block ~3 ~ ~\n'
 s+=f'loopstate {stage}\nwait 40\n'
 return s
helm='''find typewriter
wait 6
usehit first
wait 40
useifidle
wait 40
requiretypewriter
find throttle
wait 6
throttle 0
'''
def launch(tag):return f'''log {tag}-launch
throttle 15
sample 100
until region 4000
log {tag}-region
throttle 0
'''
def land(planet,tag):return f'''steer {planet} 540
requirechart
requireplanet {planet}
log {tag}-arrival
loopstate {tag}-arrival
aboardelsequit
requiretypewriter
press 340 1
until y< 170 3200
wait 500
press 340 0
wait 100
log {tag}-landed
surfacehere
loopstate {tag}-landed
shot space-loop-{tag}-landed
'''
def exit_ship(tag):return f'''sample 0
cmd execute at @s run summon marker ~ ~ ~ {{Tags:["loop_board_{tag}"]}}
cmd execute at @s run forceload add ~-48 ~-48 ~48 ~48
find typewriter
wait 6
usehit release
wait 40
# Pilot-only surface relocation: no ship movement or reconstruction.
cmd execute at @s positioned ~12 ~ ~ run spreadplayers ~ ~ 0 1 false @s
wait 120
fly off
loopstate {tag}-walk-start
look 0 0
key forward on
wait 110
key forward off
wait 40
loopstate {tag}-walk-end
'''
end='''sample 0
cmd save-all flush
wait 80
loopstate final-save
wait 20
shot space-loop-final
quit
'''
s1=setup+check('a','S1-A')+'''loopstate S1-hull-baseline
sample 100
until region 4000
throttle 0
'''+land(1,'S1-B')+exit_ship('b')+'''cmd execute at @s align xyz run summon marker ~ ~ ~ {Tags:["loop_b"]}
cmd execute at @s run forceload add ~-32 ~-32 ~32 ~32
cmd execute at @e[tag=loop_b,limit=1] run fill ~-1 ~-10 ~ ~ ~-1 ~ air
cmd execute at @e[tag=loop_b,limit=1] run setblock ~ ~-10 ~ chest{Items:[{Slot:0b,id:"minecraft:copper_ingot",count:7},{Slot:5b,id:"minecraft:coal",count:13},{Slot:26b,id:"minecraft:raw_iron",count:11}]}
cmd execute at @e[tag=loop_b,limit=1] run setblock ~1 ~ ~ copper_block
cmd execute at @e[tag=loop_b,limit=1] run setblock ~2 ~ ~ amethyst_block
cmd execute at @e[tag=loop_b,limit=1] run setblock ~2 ~1 ~ emerald_block
cmd execute at @e[tag=loop_b,limit=1] run setblock ~3 ~ ~ furnace{Items:[{Slot:0b,id:"minecraft:raw_iron",count:64},{Slot:1b,id:"minecraft:coal",count:16}]}
loopstate S1-furnace-start
wait 220
'''+check('b','S1-B')+end
head='waitworld\nlog session-login\nloopstate login\nwait 160\n'
s2=head+check('b','S2-B')+'''loopstate S2-ground-before-board
cmd tp @s @e[tag=loop_board_b,limit=1]
wait 100
aboardelsequit
'''+helm+launch('S2')+'''steer 0 50
log S2-space-stop
loopstate S2-space-stop
wait 100
'''+end
s3=head+'''aboardelsequit
'''+helm+land(0,'S3-A')+exit_ship('a_return')+'''cmd tp @s @e[tag=loop_a,limit=1]
wait 160
'''+check('a','S3-A')+end
s4=head+check('a','S4-A')+'''cmd tp @s @e[tag=loop_board_a_return,limit=1]
wait 160
aboardelsequit
'''+helm+launch('S4')+land(1,'S4-B')+exit_ship('b_return')+'''cmd tp @s @e[tag=loop_b,limit=1]
wait 160
'''+check('b','S4-B')+end
for i,s in enumerate((s1,s2,s3,s4),1):
 # Guards jump to penultimate line; always finish safely, never continue fixture commands.
 (p/f'space-loop-s{i}.txt').write_text(s)
