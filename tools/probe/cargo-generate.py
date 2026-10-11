from pathlib import Path
old=Path('tools/probe/space-trip4-tour.txt').read_text()
setup=old[:old.index('log L1-launch')]
# Fixed launch pad makes later chart searches reproducible; all flight remains key/dial driven.
setup=setup.replace('leadsite 4800 262','cmd tp @s 5264.5 262 864.5 180 20')
setup=setup.replace('cmd setblock ~-1 ~-2 ~ minecraft:glass','cmd setblock ~2 ~-1 ~ minecraft:chest{Items:[{Slot:0b,id:"minecraft:diamond",count:7},{Slot:13b,id:"minecraft:iron_ingot",count:23},{Slot:26b,id:"minecraft:apple",count:11}]}\ncmd setblock ~-1 ~-2 ~ minecraft:glass')
setup=setup.replace('throttle 15','throttle 0')
setup=setup.replace('cmd sable remove @e','cmd sable remove @e\ncmd kill @e[tag=cargo]')
setup=setup.replace('cmd fill ~-10 ~-3', 'wait 300\ncmd sable info @e\ncmd sable remove @e\nwait 100\ncmd sable info @e\ncmd fill ~-10 ~-3',1)
cargo='''cmd summon minecraft:cow ~3 ~ ~-1 {Tags:["cargo"],CustomName:'"cargoCow1"',PersistenceRequired:1b}
cmd summon minecraft:cow ~3 ~ ~1 {Tags:["cargo"],CustomName:'"cargoCow2"',PersistenceRequired:1b}
cmd summon minecraft:villager ~-2 ~ ~1 {Tags:["cargo"],CustomName:'"cargoVillager"',PersistenceRequired:1b}
cmd summon minecraft:armor_stand ~-2 ~ ~-1 {Tags:["cargo"],CustomName:'"cargoStand"'}
cmd summon minecraft:item ~1 ~ ~-1 {Tags:["cargo"],CustomName:'"cargoIron"',Item:{id:"minecraft:iron_ingot",count:17},PickupDelay:32767s}
cmd summon minecraft:item ~1 ~ ~1 {Tags:["cargo"],CustomName:'"cargoGold"',Item:{id:"minecraft:gold_ingot",count:19},PickupDelay:32767s}
cmd summon minecraft:item ~-1 ~ ~1 {Tags:["cargo"],CustomName:'"cargoApple"',Item:{id:"minecraft:apple",count:21},PickupDelay:32767s}
cmd item replace entity @s weapon.mainhand with minecraft:item_frame
find glass
wait 6
usehit frame-place
wait 20
cmd tag @e[type=minecraft:item_frame] add cargo
cmd execute as @e[type=minecraft:item_frame] run data merge entity @s {CustomName:'"cargoFrame"',Item:{id:"minecraft:emerald",count:1}}
cmd item replace entity @s weapon.mainhand with minecraft:air
wait 60
cargo loaded
cargowatch 20
sample 100
trace on
'''
# Reacquire the helm after placement and retain the dial coordinate.
cargo+='''find typewriter
wait 6
useifidle
wait 20
requiretypewriter
'''
end='''cargo final
shot cargo-G1-final
sample 0
trace off
cargowatch 0
fly on
cmd tp @s 5264.5 262 864.5
wait 160
log search-chart
cargo search-chart
cmd execute as @e[type=minecraft:cow] run data get entity @s Pos
cmd execute as @e[type=minecraft:villager] run data get entity @s Pos
cmd execute as @e[type=minecraft:armor_stand] run data get entity @s Pos
cmd execute as @e[type=minecraft:item] run data get entity @s Pos
cmd execute as @e[type=minecraft:item_frame] run data get entity @s Pos
cmd tp @s -16384 8192 -16384
wait 160
log search-region
cargo search-region
cmd execute as @e[type=minecraft:cow] run data get entity @s Pos
cmd execute as @e[type=minecraft:villager] run data get entity @s Pos
cmd execute as @e[type=minecraft:armor_stand] run data get entity @s Pos
cmd execute as @e[type=minecraft:item] run data get entity @s Pos
cmd execute as @e[type=minecraft:item_frame] run data get entity @s Pos
wait 40
quit
'''
s=setup+cargo+'''log G1-launch
throttle 15
press 32 1
wait 200
throttle ?
hullblocks launch10
until region 2200
press 32 0
log G1-region
cargo leave
shot cargo-G1-leave
throttle 0
wait 600
log G1-coast30
cargo coast30
press 340 1
until chart 2200
press 340 0
log G1-arrive
cargo arrive
shot cargo-G1-arrive
requirechart
aboardelsequit
press 340 1
until y< 170 2400
wait 240
press 340 0
wait 80
log G1-land
cargo land
surfacehere
'''+end
Path('tools/probe/cargo-g1.txt').write_text(s)
