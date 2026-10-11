package dev.starfold;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

/** Literal pages: written books cannot switch with the reader's client language. */
final class NativeStarterBooks {
    static List<String> pages(String language){return language.equals("zh_cn")?ZH:EN;}
    static ItemStack book(String language){
        var stack=new ItemStack(Items.WRITTEN_BOOK);
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT,new WrittenBookContent(Filterable.passThrough(language.equals("zh_cn")?"飞船指南":"Ship Guide"),"Starfold",0,pages(language).stream().map(s->Filterable.<Component>passThrough(Component.literal(s))).toList(),true));
        return stack;
    }
    private static final List<String> ZH=List.of(
        "1 · 欢迎登船\n这是一艘已组装的飞船，可从母星起飞，探索其他星球。无需再次组装。工程舱箱子有中文和 English 指南。\n走上舷梯，右键船头的控制器",
        "2 · 驾驶\n右键船首的控制器接管驾驶。R 离开驾驶；在太空或未落稳放梯时须再次按 R 确认。不要在太空弃船。陀螺仪旁的拉杆应保持开启。",
        "3 · 操纵\n松手悬停；空格加大升力；Shift 减小升力靠重力下降，Ctrl 更快。地面 W/S 前进/后退，A/D 转向。太空 W 加油门，S 减速，A/D 滚转，鼠标转向。Ctrl 加力；配合方向键使用。",
        "4 · 视角\nF5 依次切换追随、船上镜头、第一人称。看镜头编号和朝向标签。Alt 按住自由观察，松开回正。前风挡可直接观察地平线；朝下镜头可观察采矿位置。",
        "5 · 导航\n首次 G 选择最近的其他星球，当前星球排最后；F 锁定船首附近目标或解除锁定。I · 砾星 · 9/12 表示代号、名称、目标序号/总数，不是距离。顺序随星系而定。",
        "5 · 巡航\n太空 W/S 调整油门，松键保持巡航。太空中的最高速度由朝前的推进器和船重决定：引擎越多越快，船越重越慢。接近目标自动限速；看仪表上限和相对速度。",
        "6 · 抵达\n靠近星球会显示抵达预告，并对正进入。锁定目标后，只有目标星球会自动进入抵达；刚离开的星球不会。若不想进入，按 Space+Ctrl 取消，向外飞离。抵达时不要继续猛推油门；看高度和下降速度。",
        "7 · 着陆\nShift 下降，按住 Ctrl 更快。低空看“下方”，找陆地或停机坪及“舷梯可放下”。沿琥珀色箭头找可降落点。W/S 移动，A/D 转向。回到母星时对准停机坪中心的十字；船体整体在坪内即可放下舷梯。看高度、下降速度和倾角，等落稳放梯再按 R。不是自动避障。",
        "7 · 下船\n落稳且门槛距地不超过 10 格时，舷梯自动展开。起飞即收起。水面、悬空、斜坡/障碍挡住台阶或头部、太空无重力时可能不展开。等梯子展开再按 R。",
        "8 · 回家\n从星球起飞会自动保存起飞点标记。返回母星后，跟随 HUD 的标记方向和距离回到出发地；标记不是传送。远行前先在附近练习起降。",
        "9 · 星图（一）\n炉心：蓝绿陆海；补给建家；夜间怪物。\n砧星：灰色裸岩；采铁铜；陨坑落差。\n霜原：雪白冰球；采冰和深层矿；滑冰、流浪者。",
        "9 · 星图（二）\n赭沙：橙红沙地；采金铜；沙陷、尸壳。\n群澜：蓝海零星小岛；海洋探索；溺水、溺尸。\n苍翠：大片翠绿；采丛林木与可可；夜怪、陷阱。",
        "9 · 星图（三）\n菌港：灰紫菌土；食物与巨菇；仍防跌落。\n砾星：小型粉色岩体；集中采铁铜；落差、近核心。\n烬岩：炭黑夹橙斑；石英与下界金；岩浆块、陡坡。",
        "9 · 星图（四）\n象牙：浅绿象牙色；紫颂与末地石；末影人、陨坑。\n晶棱：紫白晶面；水晶与深层青金石；晶洞落差。\n寂痕：黑青幽匿；幽匿与深矿；尖啸可能引来监守者。",
        "10 · 补给\n生活舱箱子：面包 32、熟牛肉 16。工程舱箱子：火把 32、铁镐、水桶和中英指南。船上已有床，可睡眠，无需再造两张床。",
        "10 · 生存\n可在船上睡眠；远行前建议在地面设置稳定床位。太空离船后没有自动回船功能。死亡返回母星或有效床位，船留在原处。",
        "11 · 驾驶舱控制台\n先按 R 离座再扳拉杆；悬停时可能需再按 R 确认。仪表尚不控制设备。钻机 降下/收回：灭=降下，亮=收回。钻机 运转/停止：灭=运转，亮=停止。炮塔 升起/收回：亮=升起，灭=收回。默认收回并停止；创造动力，不用加煤。",
        "11 · 停机位\n工程舱顶是露天机库，橙色边线和灯标出停机坪。降在飞机位，下机等一秒收纳。右键放出并登机；潜行右键只放出。左舷后角的铜舱盖用手开合，下面的梯子直通工程舱，向船头穿门进入生活舱和驾驶舱；原路返回。进出后随手关上舱盖。装有飞机模组时，工程舱箱子有四旋翼。",
        "11 · 可选炮塔\n先着陆，再打开炮塔拉杆。船顶加特林升起 3 格，在终点接合齿轮，自动射击斜距 100 格内的敌对生物，清理周围后再下船作业。全类型创造弹药盒，无限弹药；转向锁定需数秒。船首下方不再有炮塔；无武器模组时只有封板。",
        "11 · 炮塔安全\n关闭拉杆收回炮塔，离开终点只断开齿轮传动；升降途中仍可能射击。起飞前收回炮塔；起飞远行和着陆前收回钻头。地形和船体会遮挡射线，紧贴船边尤其登船梯脚下是盲区，出舱仍须检查。不要让飞船靠钻头支撑。",
        "11 · 船腹采矿\n离开停机坪，在岩石上方 10–40 格悬停。R 离座，先将方向杆拨至降下，再将运转杆拨至运转。绳索带 3×3 钻头向下挖，船不用跟着下降。熔岩会毁掉散落物；核心和顶角墙不可钻。",
        "11 · 回收矿物\n先停钻，再拨方向杆至收回，然后恢复运转让绳索上卷。完全收回后再次停止。箱子随钻头带矿回舱，从工程舱底梯取用。不要在钻头伸出时飞走或着陆；先收回两种设备。",
        "10 · 修造\n进度页“建造飞船”介绍安山岩、手摇压片机、胶水和物理组装器。改造时只组装船体，绝不能带上地面。推进器反向推力，升力须大于重量，左右配平；保持两台陀螺仪供电。"
    );
    private static final List<String> EN=List.of(
        "1 · Welcome aboard\nThis assembled ship can leave home and visit other planets. Do not assemble it again. Guides are in the engineering chest.\nWalk up the airstair; right-click the bow controller.",
        "2 · Helm\nRight-click the controller near the bow to pilot. R leaves the helm; in space or without landed stairs, press R again to confirm. Stay aboard in space. Keep both gyro levers switched on.",
        "3 · Controls\nRelease to hover; Space adds lift; Shift cuts lift to fall under gravity, Ctrl faster. Near ground: W/S forward/back, A/D turn. In space: W raises throttle, S slows, A/D roll, mouse steers. Hold Ctrl with a direction to boost.",
        "4 · Views\nF5 cycles chase, ship cameras and first person. Read the camera number and direction label. Hold Alt to look around; release to centre. The front windscreen shows the horizon; the downward camera helps with mining.",
        "5 · Navigation\nFirst G picks the nearest other planet; current planet is last. F locks near the nose or clears. I · Fleck · 9/12 is code, name, index/total, not distance.",
        "5 · Cruise\nIn space W/S adjust throttle; release to keep cruising. Space top speed depends on forward thrusters and hull mass: more engines are faster, heavier ships slower. Read your caps on the HUD. Approach limits speed.",
        "6 · Arrival\nWith a target locked, only that planet starts automatic arrival; the planet just left does not. Notice, align, then enter. Space+Ctrl cancels; fly away. Watch height/descent; ease throttle.",
        "7 · Landing\nShift descends; hold Ctrl to go faster. Below: land/pad + Stairs can deploy. Follow amber arrow. W/S move; A/D turn. At home aim for pad cross; keep hull inside. Watch height/descent/tilt. Settle; stairs down, then R.",
        "7 · Airstair\nAfter settling within 10 blocks of ground, stairs deploy. Lift retracts them. Water, a drop, slopes or blocked step/headroom can prevent deployment; so can zero gravity. Wait for stairs before leaving the helm.",
        "8 · Coming home\nLeaving a planet saves a launch-site marker automatically. On return, follow the HUD marker direction and distance to your departure site. It is not a teleport. Practise local take-off and landing first.",
        "9 · Star chart (1)\nHearth: blue-green land/sea; resupply; night mobs.\nAnvil: grey rock; iron/copper; crater drops.\nRime: snow-white; ice/deep ores; slippery ice, strays.",
        "9 · Star chart (2)\nSaffron: orange-red sand; gold/copper; husks, loose sand.\nPelagia: blue with tiny islands; sea exploration; drowning/drowned.\nViridia: deep green; jungle wood/cocoa; night mobs, traps.",
        "9 · Star chart (3)\nHaven: muted mauve; food/giant fungi; falls still hurt.\nFleck: small pink rock; iron/copper; drops, nearby core.\nCinder: charcoal/orange; quartz/Nether gold; magma blocks, steep ridges.",
        "9 · Star chart (4)\nIvory: pale green-ivory; chorus/end stone; endermen, pits.\nPrism: purple/white; crystals/deep lapis; geode drops.\nHush: black-teal; sculk/deep ores; shrieks may summon Wardens.",
        "10 · Provisions\nCabin: 32 bread, 16 cooked beef. Engineering: 32 torches, iron pickaxe, water bucket, both guides. Beds are already aboard and usable; no need to craft two more.",
        "10 · Survival\nYou can sleep aboard; a stable ground bed is recommended before long trips. Leaving the ship in space has no automatic return. Death sends you home or to a valid bed; your ship stays where you left it.",
        "11 · Cockpit console\nPress R to leave helm before using levers; confirm with R again if asked. Instruments do not control devices. Creative power: no coal. Signs label drill direction, drill run/stop and turret raise/stow.",
        "11 · Lever polarity\nDrill direction: unlit LOWER, lit RETURN. Drill run: unlit RUN, lit STOP. Turret: lit RAISE, unlit STOW. Start stowed/stopped. To return drill: STOP, select RETURN, RUN until aboard, then STOP again.",
        "11 · Aircraft dock\nEngineering roof: open hangar. Land, step off; stows in 1s. Right-click: board; Sneak-click: release only. Port-aft copper hatch opens by hand; its ladder leads to engineering, then forward to cabin. Close it behind you.",
        "11 · Optional turret\nLand first, then raise roof minigun 3 blocks to clear mobs before outside work. Auto-fires at hostiles within 100 blocks slant range. Infinite creative ammo; wait for lock. No gun under bow. No weapon mods: hull plate.",
        "11 · Device safety\nStow turret before take-off. Gears disconnect in transit, but the moving gun may still fire. Hull/terrain hide mobs, especially at the airstair foot: check before exiting. Stow drill before flight/landing; never rest on it.",
        "11 · Belly mining\nHover 10-40 blocks above rock, off the pad. R, select LOWER, then RUN. Rope lowers the 3x3 head; keep the ship hovering. Lava destroys loose drops. Core/corner walls cannot be drilled. Return and STOP before flight or landing.",
        "11 · Collecting ore\nSTOP, select RETURN, then RUN to wind the head aboard. STOP when fully stowed. The carried chest returns with its contents; reach it via the engineering bay ladder. No coal or manual transfer from a deployed head needed.",
        "10 · Building\nThe ship advancement tab covers andesite, hand pressing, glue and the Physics Assembler. Assemble only hull blocks, never terrain. Nozzles push opposite their facing. Lift must exceed weight. Balance thrust and keep two gyros powered."
    );
    private NativeStarterBooks(){}
}
