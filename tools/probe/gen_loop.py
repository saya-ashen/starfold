import sys
def fly(target,tag,shots=True):
    t=f"""cmd starfold nav target {target}
key jump on
wait 4
fly on
key sprint on
until controlled 1500
nav {tag}-controlled
wait 200
key jump off
nav {tag}-high
aim track
key forward on
until alt< 6000 1500
nav {tag}-cruise
shot loop-{tag}-cruise
until alt< 3000 600
key forward off
key sprint off
nav {tag}-near
cmd starfold nav brake on
wait 100
nav {tag}-braked
shot loop-{tag}-near
cmd starfold nav land on
key forward on
until alt< 1500 3000
nav {tag}-assist-zone
until alt< 600 3000
key forward off
aim free
nav {tag}-flare
key sneak on
until free 6000
nav {tag}-handoff
until ground 2000
key sneak off
fly off
wait 60
nav {tag}-landed
shot loop-{tag}-landed
cmd starfold status
"""
    return t
head="""waitworld
wait 40
cmd starfold terrain
wait 160
waitworld
cmd effect give @s minecraft:night_vision infinite 0 true
"""
def phase1():
    return head+"""cmd gamerule doDaylightCycle false
cmd time set noon
cmd kill @e[type=marker]
cmd starfold face 0
wait 100
fly off
cmd spreadplayers 1000 1000 0 1 false @s
wait 200
nav a-start
cmd execute at @s run fill ~2 ~ ~2 ~4 ~2 ~4 oak_planks hollow
cmd execute at @s run setblock ~-2 ~ ~ chest{Items:[{Slot:0b,id:"minecraft:cobblestone",count:17},{Slot:1b,id:"minecraft:iron_ingot",count:3}]}
cmd execute at @s run summon marker ~ ~ ~ {Tags:["a_base"]}
cmd clear @s
cmd item replace entity @s hotbar.3 with minecraft:cobblestone 11
cmd starfold mark add a_base_1123
wait 20
look 45 20
wait 10
shot loop-a-base
cmd execute at @e[tag=a_base,limit=1] run data get block ~-2 ~ ~ Items
"""+fly('b','ab')+"""cmd execute at @s run fill ~2 ~ ~2 ~4 ~3 ~4 copper_block hollow
cmd execute at @s run setblock ~-2 ~ ~ chest{Items:[{Slot:0b,id:"minecraft:copper_ingot",count:7}]}
cmd execute at @s run fill ~-1 ~-3 ~3 ~1 ~-1 ~5 air
cmd execute at @s run summon marker ~ ~ ~ {Tags:["b_base"]}
cmd starfold mark add b_base_1123
cmd starfold mark target a_base_1123
cmd execute at @e[tag=b_base,limit=1] run data get block ~-2 ~ ~ Items
cmd data get entity @s Inventory
wait 20
look 45 30
wait 10
shot loop-b-base
nav b-before-quit
cmd save-all flush
wait 60
quit
"""
def phase2():
    return head+"""wait 100
nav b-reloaded
shot loop-b-reloaded
cmd execute at @e[tag=b_base,limit=1] run data get block ~-2 ~ ~ Items
cmd execute at @e[tag=b_base,limit=1] if block ~3 ~ ~2 copper_block
cmd execute at @e[tag=b_base,limit=1] if block ~ ~-2 ~4 air
cmd data get entity @s Inventory
cmd starfold mark list
cmd starfold nav target a
key jump on
wait 4
fly on
key sprint on
until controlled 1500
wait 200
key jump off
aim track
key forward on
until alt< 9000 1500
key forward off
key sprint off
aim free
wait 40
nav deep-before-quit
shot loop-deep-before-quit
cmd save-all flush
wait 60
quit
"""
def phase3(a,b):
    return head+"""wait 40
nav deep-reloaded
shot loop-deep-reloaded
key sprint on
aim track
key forward on
until alt< 3000 1500
key forward off
key sprint off
cmd starfold nav brake on
wait 100
cmd starfold nav land on
key forward on
until alt< 600 3000
key forward off
aim free
nav ba-flare
key sneak on
until free 6000
until ground 2000
key sneak off
fly off
wait 60
nav ba-landed
shot loop-ba-landed
"""+f"""cmd forceload add {a[0]} {a[2]}
wait 40
cmd data get block {a[0]} {a[1]} {a[2]} Items
cmd execute if block {a[0]+5} {a[1]} {a[2]+3} oak_planks
cmd data get entity @s Inventory
cmd forceload remove {a[0]} {a[2]}
"""+fly('b','ab2')+f"""cmd forceload add {b[0]} {b[2]}
wait 40
cmd data get block {b[0]} {b[1]} {b[2]} Items
cmd execute if block {b[0]+5} {b[1]} {b[2]+3} copper_block
cmd execute if block {b[0]+2} {b[1]-2} {b[2]+4} air
cmd forceload remove {b[0]} {b[2]}
cmd data get entity @s Inventory
cmd save-all flush
wait 60
quit
"""
which=sys.argv[1]
if which=='1':open('loop-p1.txt','w').write(phase1())
elif which=='2':open('loop-p2.txt','w').write(phase2())
else:
    a=list(map(int,sys.argv[2].split(',')));b=list(map(int,sys.argv[3].split(',')));open('loop-p3.txt','w').write(phase3(a,b))
