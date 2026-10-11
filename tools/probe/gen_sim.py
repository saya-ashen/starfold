import sys
# site: name, seam axis, function (a,b)->(x,z); a=0 is the first halo column, b is lateral (0..52)
# usage: gen_sim.py <out> [face-size origin-x origin-z]: the planet the fixture is built on; the first planet by default
SIZE,OX,OZ=(int(v) for v in sys.argv[2:5]) if len(sys.argv)>4 else (2048,0,0)
def siteA(a,b): return (OX+SIZE+a, OZ+SIZE//2-14+b)
def siteB(a,b): return (OX+4096+SIZE//2-20+b, OZ+SIZE+a)
SITES={'A':(siteA,'east','west',-90,(OX+SIZE-8,OZ+SIZE//2+6)),'B':(siteB,'south','north',0,(OX+4096+SIZE//2,OZ+SIZE-8))}
def gen(key,tag):
    f,fw,bw,yaw,(px,pz)=SITES[key]; out=[]; c=out.append
    def P(a,b,y=170): x,z=f(a,b); return f"{x} {y} {z}"
    c(f"cmd tp @s {px} 176 {pz} {yaw} 35"); c("wait 20"); c(f"look {yaw} 35"); c("wait 700")  # a deep planet takes over ten seconds to make the chunks about a far teleport
    c("cmd kill @e[type=!player]")
    c(f"cmd fill {P(-18,0)} {P(18,52,176)} air"); c(f"cmd fill {P(-18,0,169)} {P(18,52,169)} stone"); c("wait 20")
    # redstone both ways, repeaters, comparator reading a chest across the seam
    c(f"cmd fill {P(-8,4)} {P(8,4)} redstone_wire")
    c(f"cmd fill {P(-11,8)} {P(3,8)} redstone_wire"); c(f"cmd setblock {P(-12,8)} redstone_lamp")
    c(f"cmd fill {P(-2,12)} {P(1,12)} repeater[facing={bw}]"); c(f"cmd setblock {P(2,12)} redstone_lamp")
    c(f'cmd setblock {P(0,16)} chest{{Items:[{{Slot:0b,id:"minecraft:stone",count:64}}]}}'); c(f"cmd setblock {P(-1,16)} comparator[facing={fw}]"); c(f"cmd setblock {P(-2,16)} redstone_lamp")
    # pistons
    c(f"cmd setblock {P(-3,20)} sticky_piston[facing={fw}]"); c(f"cmd setblock {P(-2,20)} gold_block"); c(f"cmd setblock {P(-1,20)} emerald_block")
    c(f"cmd setblock {P(2,24)} sticky_piston[facing={bw}]"); c(f"cmd setblock {P(1,24)} gold_block"); c(f"cmd setblock {P(0,24)} emerald_block")
    # water
    c(f"cmd setblock {P(-2,28)} water"); c(f"cmd setblock {P(2,30)} water"); c(f"cmd setblock {P(-1,32)} water"); c(f"cmd setblock {P(1,32)} water")
    # isolated lights: ghost-side source lights the owned side and vice versa
    c(f"cmd setblock {P(3,0)} glowstone"); c(f"cmd setblock {P(-4,52)} glowstone")
    c("wait 20")
    c(f"cmd setblock {P(-9,4)} redstone_block"); c(f"cmd setblock {P(4,8)} redstone_block"); c(f"cmd setblock {P(-3,12)} redstone_block")
    c(f"cmd setblock {P(-4,20)} redstone_block"); c(f"cmd setblock {P(3,24)} redstone_block")
    c("wait 120"); c(f"log {tag}-built")
    for a,b in [(-1,4),(0,4),(4,4),(7,4),(-12,8),(0,12),(2,12),(-1,16),(-2,16),(-2,20),(-1,20),(0,20),(1,24),(0,24),(-1,24),(1,28),(-1,30),(0,32)]: c(f"blockat {P(a,b)}")
    for a,b in [(-2,0),(-4,0),(2,52),(5,52)]: c(f"light {P(a,b)}")
    c(f"shot {tag}-0-built")
    c(f"cmd setblock {P(-4,20)} air"); c(f"cmd setblock {P(3,24)} air"); c(f"cmd setblock {P(3,0)} air"); c("wait 40"); c(f"log {tag}-retracted")
    for a,b in [(-2,20),(-1,20),(0,20),(1,24),(0,24),(-1,24)]: c(f"blockat {P(a,b)}")
    c(f"light {P(-2,0)}")
    # mob chase across the seam
    c(f"cmd fill {P(-16,39)} {P(16,39)} glass"); c(f"cmd fill {P(-16,41)} {P(16,41)} glass"); c(f"cmd fill {P(-16,41,171)} {P(16,41,171)} glass"); c(f"cmd fill {P(-16,39,171)} {P(16,39,171)} glass")
    c(f"cmd setblock {P(-17,40)} glass"); c(f"cmd setblock {P(17,40)} glass")
    hx,hz=f(-14,40); vx,vz=f(14,40)
    c(f"cmd summon minecraft:husk {hx+.5} 170 {hz+.5}"); c(f"cmd summon minecraft:villager {vx+.5} 170 {vz+.5} {{NoAI:1b}}")
    for i,w in enumerate([20,100,100,200]): c(f"wait {w}"); c(f"log {tag}-mobs{i}"); c("entities")
    c(f"shot {tag}-1-mobs")
    return out
head="""waitworld
wait 40
cmd starfold terrain
wait 100
waitworld
cmd effect give @s minecraft:night_vision infinite 0 true
cmd difficulty easy
cmd gamerule doDaylightCycle false
cmd gamerule doMobSpawning false
cmd time set noon
fly on""".split("\n")
lines=head+gen('A','simA')+gen('B','simB')+["cmd starfold status","wait 10","quit"]
open(sys.argv[1],'w').write("\n".join(lines)+"\n")
