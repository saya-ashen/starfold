"""Author the bilingual quest source; output uses the JSON subset of FTB SNBT."""
import json
from pathlib import Path
import tomllib
import argparse
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
mode = parser.add_mutually_exclusive_group(required=True)
mode.add_argument('--output', type=Path, help='Generate into this directory (explicitly replaces matching files).')
mode.add_argument('--check', action='store_true', help='Generate in a temporary directory and compare tracked quests.')
args = parser.parse_args()
temporary = tempfile.TemporaryDirectory() if args.check else None
root = Path(temporary.name) if temporary else args.output
# All task/reward quantities are first guesses to tune in play.
# kind, registry ID, count, EN title, ZH title, EN readout/instruction, ZH readout/instruction
chapters = [
('Surface contact', '落地', [
 ('dimension','starfold:native_planet',1,'Surface contact','地表信号','Planet level detected. The experience preset starts on the home planet without a supplied ship.','当前位于星球维度。体验预设从母星开始，未配发出生飞船。'),
 ('item','minecraft:oak_log',4,'Local timber','木材储备','Timber sample required. Folded faces belong to one continuous planet. Other planets are visible in the sky.','等待木材样本。六个面构成连续星球，天空可见其他星球。'),
 ('item','minecraft:crafting_table',1,'Field bench','野外工作台','Assembly surface required. Craft a workbench from local timber.','等待组装台面。使用本地木材制作工作台。'),
 ('item','minecraft:wooden_pickaxe',1,'First excavation','开始开采','Excavation tool required. Stone and andesite support the first workshop.','等待开采工具。石料与安山岩用于建设第一间工坊。'),
 ('item','minecraft:cobblestone',16,'Stone reserve','石料储备','Stone reserve below target. Mine near the landing area before travelling farther.','石料储备待补充。先在出生区域附近开采，再扩大活动范围。'),
 ('item','minecraft:torch',8,'Working light','作业照明','Lighting stock required. Mark the route between the shelter and the mine.','等待照明补给。标记住所与矿井之间的路线。')]),
('Ground survival', '地面生存', [
 ('item','minecraft:furnace',1,'Smelting station','冶炼站','Heat processing required. Install a furnace near the workbench.','等待热加工设备。在工作台附近安装熔炉。'),
 ('item','minecraft:coal',8,'Solid fuel','固体燃料','Solid fuel stock required. Reserve coal for smelting and field lighting.','等待固体燃料。煤用于冶炼与野外照明。'),
 ('item','minecraft:iron_ingot',8,'Iron supply','铁料补给','Iron sample required. Home ore tables include iron, copper, coal, gold, zinc and redstone, but no osmium.','等待铁料样本。母星矿表包含铁、铜、煤、金、锌、红石，不含锇。'),
 ('item','minecraft:iron_pickaxe',1,'Reliable excavation','稳定开采','Iron tool required. Collect redstone and gold for later control components.','等待铁制工具。后续控制部件需要红石与金。'),
 ('item','minecraft:copper_ingot',8,'Copper conductors','铜材导体','Copper stock required. Fluid handling and electrical connections will use this supply.','等待铜材储备。流体设备与电气连接将使用这批材料。'),
 ('item','minecraft:bread',8,'Food reserve','食物储备','Food reserve required. Establish a renewable crop plot before long flights.','等待食物补给。长途飞行前建立可持续农田。'),
 ('item','minecraft:white_bed',1,'Surface shelter','地表住所','Shelter equipment required. Place the bed in a protected ground base.','等待住所设备。在受保护的地面基地放置床。'),
 ('item','minecraft:shield',1,'Field protection','野外防护','Shield required. No oxygen or suit progression is imposed by this pack.','等待盾牌。整合包未设置氧气与航天服进度。')]),
('Mechanical power', '机械动力', [
 ('item','create:andesite_alloy',8,'Andesite alloy','安山合金','Alloy supply required. Shallow andesite patches remain enabled on the home planet.','等待合金补给。母星保留浅层安山岩矿斑。'),
 ('item','create:water_wheel',1,'Rotational source','旋转动力源','Mechanical power source required. Use flowing water to drive a wheel.','等待机械动力源。利用水流驱动水车。'),
 ('item','create:shaft',8,'Power transmission','动力传输','Shaft stock required. Connect the wheel to the workshop without overstressing the network.','等待传动轴储备。将水车接入工坊，检查应力余量。'),
 ('item','create:mechanical_press',1,'Press line','冲压生产线','Press required. Sheet metal is used in ship components and fuel equipment.','等待冲压设备。船件与燃料设备需要金属板。'),
 ('item','create:iron_sheet',8,'Sheet stock','铁板储备','Sheet stock required. Process iron ingots under the mechanical press.','等待铁板储备。使用机械压力机加工铁锭。'),
 ('item','create:basin',1,'Mixing vessel','搅拌容器','Basin required. Leave space for a mixer and a heat source.','等待搅拌容器。预留搅拌器与热源的安装空间。'),
 ('item','create:mechanical_mixer',1,'Mixing drive','搅拌动力','Mixer required. Check the required rotation speed in JEI before production.','等待搅拌设备。投产前通过 JEI 核对所需转速。'),
 ('item','minecraft:coal_block',1,'Heat-source stock','热源储备','No Nether is available. Craft a Blaze Burner from three iron sheets, a coal block and a copper ingot. Rose Quartz uses glass surrounded by eight redstone dust; polish it for electron tubes.','下界已移除。使用三块铁板、一块煤炭块和一块铜锭合成烈焰人燃烧室。玻璃周围放置八份红石粉可合成玫瑰石英，打磨后制作电子管。'),
 ('item','create:blaze_burner',1,'Captured heat','稳定热源','Burner required. A burner is also consumed by the provisional thruster recipe.','等待烈焰人燃烧室。暂定推进器配方也会消耗燃烧室。'),
 ('item','create:brass_ingot',8,'Brass production','黄铜生产','Brass stock required. Mix copper and zinc with the required heat.','等待黄铜储备。按配方加热混合铜与锌。'),
 ('item','create:electron_tube',2,'Control electronics','控制电子元件','Electron tubes required. The initial helm and gyro use Create electronics, not osmium circuits.','等待电子管。初始驾驶台与陀螺仪使用 Create 电子元件，不要求锇电路。'),
 ('item','create:precision_mechanism',1,'Precision assembly','精密装配','Precision mechanism required. Establish repeatable production before building propulsion.','等待精密构件。制造推进设备前建立稳定生产流程。')]),
('Low-altitude flight', '低空飞行', [
 ('item','immersive_aircraft:hull',1,'Aircraft structure','飞行器结构','Aircraft hull required. This is a low-altitude exploration route, separate from the orbital ship.','等待飞行器机身。低空探索与入轨飞船采用不同设备路线。'),
 ('item','immersive_aircraft:engine',1,'Aircraft engine','飞行器发动机','Engine required. Inspect the aircraft fuel requirements in JEI.','等待飞行器发动机。通过 JEI 核对飞行器燃料要求。'),
 ('item','immersive_aircraft:propeller',1,'Aircraft propeller','飞行器螺旋桨','Propeller required. Clear an open launch and landing area.','等待螺旋桨。清理起飞与降落区域。'),
 ('item','immersive_aircraft:biplane',1,'Reconnaissance aircraft','侦察飞机','Aircraft ready for a surface survey. Cross a face boundary at safe altitude; this item task does not verify the flight.','侦察机准备就绪。保持安全高度尝试跨面飞行；物品任务不验证实际飞行。'),
 ('item','aeronautics:white_envelope',8,'Buoyant structure','浮力结构','Envelope stock required. Aeronautics provides an alternative low-altitude craft route.','等待气囊储备。Aeronautics 提供另一条低空载具路线。'),
 ('item','simulated:physics_assembler',1,'Physics assembly','物理结构组装','Physics Assembler required. Study its placement and assembly controls before constructing the orbital hull.','等待物理结构组装器。建造入轨船体前确认安装方向与组装操作。'),
 ('item','create:super_glue',1,'Structural bonds','结构连接','Glue required. Join the intended ship blocks and avoid attaching the launch pad.','等待强力胶。连接预定船体方块，避免将起飞平台粘入结构。'),
 ('item','aeronautics:andesite_propeller',1,'Surface survey kit','地表勘察组件','Survey propulsion component acquired. Observe face boundaries and keep clear of unstable fold points. No automatic fold-point detector is supplied.','勘察推进组件已获取。观察面边界，远离不稳定折点。当前未提供折点自动检测任务。')]),
('Fuel and orbit', '燃料与入轨', [
 ('item','createdieselgenerators:oil_scanner',1,'Oil prospecting','油田勘探','Oil scanner required. Oil-field availability in the custom planet generator remains a play-test requirement.','等待油田探测器。自定义星球生成器中的油田可用性仍需实测。'),
 ('item','createdieselgenerators:pumpjack_bearing',1,'Extraction drive','抽油动力','Pumpjack bearing required. Follow the mod guide to build the full extraction structure.','等待抽油机轴承。按模组说明搭建完整抽油结构。'),
 ('item','create:fluid_tank',4,'Fuel processing','燃料加工','Fluid tanks and a Distillation Controller required. Convert placed Create tanks into a distillation tower; the distillation-tank block has no separate inventory item. Supply heat for tier-one diesel or gasoline.','等待流体储罐与蒸馏控制器。将已放置的 Create 储罐转换为蒸馏塔；蒸馏罐方块没有独立背包物品。接入热源后生产第一级柴油或汽油。'),
 ('item','createdieselgenerators:diesel_bucket',1,'First fuel batch','首批燃料','Diesel sample required for this tutorial route. Gasoline is also accepted by thrusters. Initial ascent budget target: about eight buckets, subject to tuning.','等待柴油样本。推进器也接受汽油。初始入轨预算目标约八桶，仍需实测调整。'),
 ('item','create:fluid_tank',4,'Onboard storage','船载储罐','Fluid tank stock required. Tanks must belong to the same assembled ship as the thrusters. Leave return fuel in reserve.','等待流体储罐。储罐与推进器必须属于同一组装船体，保留返航燃料。'),
 ('item','starfold:thruster',1,'Thrust hardware','推力设备','Thruster acquired. No fuel means no thrust after the merged fuel system is installed. Thrust and ship mass determine ascent performance.','推进器已获取。合并燃料系统后，无燃料则无推力。推力与船体质量决定爬升表现。'),
 ('item','starfold:gyro',1,'Attitude control','姿态控制','Gyro acquired. Mount it on the assembled ship; the gyro does not consume propulsion fuel.','陀螺仪已获取。安装至组装船体；陀螺仪不消耗推进燃料。'),
 ('item','starfold:helm',1,'Command station','驾驶控制台','Helm acquired. Check ship connections, clearances and reverse thrust before departure.','驾驶台已获取。起飞前检查船体连接、离地间隙与反向推进。'),
 ('item','starfold:scope',1,'Visual instruments','目视仪表','Basic scope acquired. Upgrade with a precision mechanism, an electron tube and two brass sheets before the first trip.','基础观察镜已获取。首航前使用精密构件、电子管与两块黄铜板升级为导航观察镜。'),
 ('advancement','starfold:ship/lift_off',1,'Lift-off record','离地记录','Lift-off advancement required. Perform a controlled ascent before committing to orbit.','等待离地进度记录。先完成可控爬升，再继续入轨。'),
 ('advancement','starfold:ship/space',1,'Space-region record','太空区域记录','Space-entry advancement required. Status on completion: currently in the space region. Keep the helm occupied and monitor fuel.','等待进入太空的进度记录。完成后状态：当前位于太空区域。保持驾驶，监测燃料。'),
 ('item','starfold:airstair',1,'Boarding route','登船路线','Airstair acquired. Rehearse landing and boarding at home. The existing home-return advancement only certifies a later off-world round trip, not this rehearsal.','登船梯已获取。在母星演练着陆与登船。现有母星返航进度仅认证后续外星往返，不认证本次演练。')]),
('First off-world metals', '第一次跨星：锇', [
 ('advancement','starfold:ship/other_planet',1,'Off-world landing','外星着陆','Other-planet advancement required. Seek a rocky planet or asteroid for osmium and rare deep diamonds. Diamonds occur in the bottom 35% of mineable depth; abundance is a first guess. These worlds are within the current system.','等待外星着陆进度记录。岩质星球与小行星提供锇及深层稀有钻石；钻石位于可挖深度底部35%，丰度为待调初猜。这些星球仍在当前恒星系内。'),
 ('item','mekanism:raw_osmium',8,'Osmium cargo','锇矿货物','Osmium sample required. Reserve part of the cargo for circuits and the return logistics line.','等待锇矿样本。为电路与返航物流预留部分货物。'),
 ('item','mekanism:ingot_osmium',8,'Refined osmium','精炼锇材','Osmium ingots required. Mekanism begins after this off-world supply is available.','等待锇锭。取得外星供料后启动 Mekanism 工业线。'),
 ('advancement','starfold:ship/home',1,'Home landing record','母星着陆记录','Off-world round-trip advancement required. Land back on the home planet after the other-planet and return-to-space records.','等待外星往返进度记录。取得外星着陆与再次入太空记录后，返回母星着陆。'),
 ('item','createaddition:alternator',1,'Mechanical to electrical','机械能转电能','Alternator required. Connect Create rotation to the electrical production line.','等待交流发电机。将 Create 旋转动力接入电气生产线。'),
 ('item','mekanism:metallurgic_infuser',1,'Circuit processing','电路加工','Metallurgic Infuser required. Supply power and the specified infusion material.','等待冶金灌注机。接入电力并提供配方所需灌注材料。'),
 ('item','mekanism:basic_control_circuit',2,'Basic control circuits','基础控制电路','Control circuits required. Osmium starts Mekanism processing and later advanced circuits for precision optics.','等待基础控制电路。锇用于启动 Mekanism 加工，并进一步制作精密观察镜所需的高级电路。'),
 ('item','starfold:scope_nav',1,'Navigation optics','导航光学','Navigation scope acquired through Create only, without osmium. Check target locking and relative approach speed before the first departure.','导航观察镜通过 Create 路线制造，无须锇。首次起飞前检查目标锁定与相对接近速度。'),
 ('item','mekanism:electrolytic_separator',1,'Hydrogen production','氢气生产','Electrolytic Separator required. Supply water and power; chemical hydrogen is not yet a thruster fluid.','等待电解分离器。供应水与电力；化学形态氢不能直接作为推进器流体。'),
 ('item','mekanism:rotary_condensentrator',1,'Fluid conversion','流体转换','Rotary Condensentrator required. Condense hydrogen into its fluid form before filling ship tanks.','等待旋转冷凝器。先将氢转为流体形态，再向船载储罐加注。'),
 ('item','mekanism:hydrogen_bucket',1,'Second-tier sample','第二级燃料样本','Liquid hydrogen sample required. Target ascent economy is about three buckets for the reference ship; this is a first guess, not a measured result.','等待液氢样本。参考船入轨目标约三桶，仅为待调初值，未经过实测。')]),
('System expansion', '星系扩张', [
 ('item','mekanism:raw_tin',8,'Frozen-world tin','冰冻星锡矿','Tin cargo required. Frozen planet ore tables provide tin and lead.','等待锡矿货物。冰冻类星球矿表提供锡与铅。'),
 ('item','mekanism:raw_lead',8,'Frozen-world lead','冰冻星铅矿','Lead cargo required. Establish a transport route rather than relying on one small expedition.','等待铅矿货物。建立运输路线，避免只依赖单次小规模采集。'),
 ('item','mekanism:fluorite_gem',8,'Crystal-world fluorite','晶体星萤石','Fluorite sample required. Crystal worlds also provide quartz, amethyst and a smaller deep-diamond supply (bottom 35%; first-guess abundance).','等待萤石样本。晶体类星球还提供石英、紫水晶及更少量深层钻石（可挖深度底部35%，丰度为待调初猜）。'),
 ('item','mekanism:advanced_control_circuit',2,'Advanced circuitry','高级控制电路','Advanced circuits required. Prepare the electronics for precision optics.','等待高级控制电路。为精密光学准备电子元件。'),
 ('item','starfold:scope_precise',1,'Precision optics','精密光学','Precision scope acquired. This recipe upgrades optics. Leaving the home system requires an interstellar drive and deuterium; boss permission is not enforced.','精密观察镜已获取。该配方升级光学设备。离开母恒星系需要星际引擎和氘；boss 许可尚未强制。'),
 ('item','mekanism:raw_uranium',8,'Dry-world uranium','干旱星铀矿','Uranium cargo required. Dry-world ore tables provide uranium and gold; plan shielding and processing before reactor work.','等待铀矿货物。干旱类星球矿表提供铀与金；建设反应堆前规划屏蔽与加工。'),
 ('item','minecraft:ancient_debris',2,'Volcanic salvage','火山星残骸','Ancient debris required. Volcanic ore tables include debris, gold and quartz. Avoid lava during excavation.','等待远古残骸。火山类星球矿表包含残骸、金与石英；开采时避开岩浆。'),
 ('item','minecraft:netherite_scrap',2,'Heat-resistant stock','耐热材料储备','Netherite scrap required. This material is not a pressure-hull certification.','等待下界合金碎片。该材料不构成抗压船体认证。'),
 ('item','mekanism:advanced_fluid_tank',1,'Cargo capacity','流体货运能力','Advanced tank required. Verify fluid transfer between Mekanism storage and ship-compatible tanks.','等待高级流体储罐。核对 Mekanism 储罐与船载储罐之间的流体输送。'),
 ('item','mekanismtools:steel_paxel',1,'Outpost maintenance','前哨维护','Steel Paxel acquired. Stock tools and repair materials at established outposts.','钢制多用工具已获取。为已建立的前哨补充工具与维修材料。')]),
('Anomalies and combat', '异常星球与 boss', [
 ('item','minecraft:iron_chestplate',1,'Combat protection','战斗防护','Armour required. Prepare a safe retreat route before approaching anomalous terrain.','等待护甲。接近异常地形前确认安全撤离路线。'),
 ('item','minecraft:bow',1,'Ranged capability','远程作战能力','Ranged weapon required. Keep clear of unstable fold-point terrain during combat.','等待远程武器。战斗时远离不稳定折点地形。'),
 ('item','minecraft:arrow',64,'Ammunition reserve','弹药储备','Ammunition required. Supply counts are initial tuning guesses.','等待弹药补给。补给数量为待实测调整的初值。'),
 ('item','minecraft:golden_apple',2,'Recovery supplies','恢复补给','Recovery supply required. The Flesh survey requires supplies and a retreat route from the hive crater.','等待恢复补给。血肉勘测需要补给，以及通往巢穴巨坑外的撤离路线。'),
 ]),
('Gas giants and interstellar flight', '气态巨行星与星际航行', [
 ('item','mekanism:ingot_steel',32,'Hull material reserve','船体材料储备','Steel stock required for ship equipment. Pressure tolerance belongs to each block, not the ship as a whole.','等待船载设备钢材。耐压值按方块计算，不是整舰护盾。'),
 ('item','minecraft:iron_block',16,'Structural reserve','结构材料储备','Structural stock required. Ordinary iron still fails at pressure 0.5; replace exposed structure with pressure hull before deep collection.','等待结构材料补给。普通铁块仍从压力0.5开始损坏；深层采集前将暴露结构换为耐压壳。'),
 ('item','mekanism:elite_control_circuit',2,'Long-range controls','远程控制组件','Elite circuit stock required. Reserve four elite circuits for the interstellar drive after skimming deuterium.','等待精英控制电路。掠取氘后，为星际引擎预留四块精英电路。'),
 ('item','mekanism:ultimate_fluid_tank',1,'High-capacity storage','大容量储存','Storage hardware required. Verify ship fuel extraction support before relying on this tank in flight.','等待储存设备。依赖该储罐飞行前，验证船载燃料抽取支持情况。'),
 ('item','starfold:gas_scoop',1,'Build the gas scoop','制造气体采集口','Intake required. Assemble a precision mechanism, two advanced circuits, a Create tank and a rotary condensentrator. Hydrogen machinery must be available first. Mount the scoop and an empty compatible tank on the same Sable hull.','等待采集口。使用精密构件、两块高级电路、Create 储罐和旋转冷凝器制造，需先建立液氢工业。采集口与空的兼容储罐必须安装在同一艘 Sable 船体。'),
 ('item','mekanismgenerators:deuterium_bucket',1,'Skim deuterium at Amber','在 Amber 掠取氘','Approach Amber and dip the intake into its pressure field, then take a deuterium bucket sample from the filled tank. Heavy-water separation is disabled; rotary conversion only recycles existing deuterium. Reference rate is 10 mB/tick at pressure 0.5 (first guess); ordinary blocks fail at 0.5, osmium pressure hull at 0.85 (provisional). The bucket detector does not certify location.','接近 Amber，让采集口进入气压场，再从产出罐取一桶氘样本。重水电解已禁用，旋转转换只处理已有氘。压力0.5时参考产率10 mB/tick（待调初猜），普通方块从0.5损坏，锇耐压壳从0.85损坏（暂定）。桶检测不认证采集地点。'),
 ('checkmark','',1,'Fill the onboard tank','装满船载储罐','Manual flight-log confirmation, not automatic telemetry: return the sample and fill a Create tank to 8000 mB of deuterium. Check the actual tank gauge before marking complete. Keep separate return fuel; whole-hull pressure can exceed intake pressure.','手动飞行记录，非自动遥测：归还样本，将一个 Create 船载储罐装至8000 mB氘。核对实际液位后再勾选。另留返航燃料；整舰气压可能高于采集口气压。'),
 ('checkmark','',1,'Leave the home system','离开母恒星系','Manual flight-log confirmation: install an interstellar drive on the same hull, leave the home system with deuterium available and verify thrust beyond its boundary, then mark complete. Every experience planet orbits the home sun. Outside home, thrust requires both an interstellar drive and deuterium; coasting is not blocked. Impulse starts at 25 times diesel, not a measured one-bucket ascent. Host ganglion is required to fabricate the drive.','手动飞行记录：在同一船体安装星际引擎，携带氘离开母恒星系，核对边界外仍有推力后再勾选。体验行星都围绕母恒星运行。边界外推进需要星际引擎和氘，不禁止惯性滑行。冲量初值为柴油25倍，不声称一桶入轨已标定。引擎制造需要宿主神经节。')])
]


# Move navigation before lift-off without changing existing quest/task/reward IDs.
entry_ids = {id(entry): 0x1000000000000000 + ci * 0x10000 + (i + 1) * 0x100
             for ci, (_, _, entries) in enumerate(chapters) for i, entry in enumerate(entries)}
chapters[4][2].insert(9, chapters[5][2].pop(7))
# Retire chapter 7 IDs 0500–0700; new Flesh records start after them.
flesh = [
 ('checkmark', '', 1, 'Reach Flesh', '抵达血肉',
  'Manual survey record: locate Flesh, the outermost moon of Amber, and confirm landing. The checkmark does not detect the planet.',
  '手动勘测记录：定位 Amber 最外侧卫星血肉，确认着陆后登记。勾选不自动检测所在星球。'),
 ('checkmark', '', 1, 'Enter the hive crater', '进入巢穴巨坑',
  'Manual survey record: follow the hive ridges into the giant crater. Record a retreat route before approaching the Host. The checkmark does not detect entry.',
  '手动勘测记录：沿巢脊进入巨坑后登记。接近宿主前记录撤离路线。勾选不自动检测进入。'),
 ('kill', 'starfold_biotech:host', 1, 'Defeat the Host', '击败宿主',
  'Combat record required: defeat the Host in the hive crater. The encounter yields four Host Ganglia, the biological component required by the mechanical interstellar drive.',
  '等待战斗记录：在巢穴巨坑击败宿主。遭遇掉落四个宿主神经节，为机械线星际引擎提供必需的生物部件。'),
 ('item', 'starfold_biotech:host_ganglion', 1, 'Recover a Host Ganglion', '获得宿主神经节',
  'Ganglion sample required. Reserve one for mechanical crafting of the interstellar drive; the remaining samples can support later drive construction. Quantity is provisional.',
  '等待神经节样本。保留一个用于机械合成星际引擎，其余样本可供后续引擎制造。用量暂定。')]
for i, entry in enumerate(flesh, 8):
    entry_ids[id(entry)] = 0x1000000000070000 + i * 0x100
chapters[7][2].extend(flesh)
# Append new identifiers after assigning the old ones; insertion must not renumber old tasks.
aluminum = ('item', 'starfold_materials:aluminum_hull_plating', 4,
            'Fabricate aluminum hull plating', '制作铝合金外壳板',
            'Lightweight panel stock required. Find bauxite ore on desert planets and smelt it. Eight aluminum ingots yield four panels. Mass 0.5 per full block; default pressure limit 0.5. Values are provisional.',
            '等待轻质面板。采集沙漠类星球的铝土矿，冶炼后使用八块铝锭制造四块板材。完整方块质量0.5，默认耐压0.5，数值暂定。')
pressure_hull = ('item', 'starfold_materials:osmium_pressure_hull', 4,
                 'Fabricate osmium pressure hull', '制作锇合金耐压壳',
                 'Pressure hull stock required before gas collection. Five osmium ingots and four iron ingots yield four blocks. Provisional mass 4 per full block, pressure limit 0.85. Slabs use half mass, double slabs full mass. Other exposed components retain their own limits; no whole-ship pressure shield.',
                 '采气前等待耐压壳储备。五块锇锭与四块铁锭制造四块耐压壳。暂定完整方块质量4，耐压0.85；半砖质量减半，双半砖恢复完整质量。其他暴露船件仍按各自耐压判定，不形成整舰压力护盾。')
entry_ids[id(aluminum)] = 0x1000000000060B00
entry_ids[id(pressure_hull)] = 0x1000000000080900
chapters[6][2].append(aluminum)
chapters[8][2].insert(4, pressure_hull)
drive = ('item', 'starfold:interstellar_drive', 1,
         'Build the interstellar drive', '制作星际引擎',
         'Use mechanical crafting to combine four osmium pressure hull blocks, four elite circuits, a bucket of skimmed deuterium and one Host Ganglion (provisional). Install the drive on the same hull as the thrusters and fuel tank. Leaving the home system requires an interstellar drive and deuterium for thrust; the Host encounter gates drive fabrication. The item detector does not certify installation.',
         '使用机械合成组装四块锇耐压壳、四块精英电路、一桶掠取的氘和一个宿主神经节（暂定）。将引擎与推进器、燃料罐安装在同一船体。离开母恒星系的推进需要星际引擎和氘；宿主遭遇构成引擎制造门槛。物品检测不认证安装。')
entry_ids[id(drive)] = 0x1000000000080A00
chapters[8][2].insert(-1, drive)

def write(path, value, comment=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text((f'// {comment}\n' if comment else '') + json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def code(value):
    return f'{value:016X}'


langs = {'en_us': {'file.0000000000000001.title': tomllib.loads(Path('modpack/pack.toml').read_text())['name']},
         'zh_cn': {'file.0000000000000001.title': '飞行任务记录'}}
write(root / 'data.snbt', {'version': 13, 'default_consume_items': False, 'fallback_locale': 'en_us', 'progression_mode': 'linear'})
write(root / 'chapter_groups.snbt', {'chapter_groups': []})
previous = None
count = 0
for chapter_index, (en, zh, entries) in enumerate(chapters):
    base = 0x1000000000000000 + chapter_index * 0x10000
    chapter_id = code(base)
    for locale, title in [('en_us', en), ('zh_cn', zh)]:
        langs[locale][f'chapter.{chapter_id}.title'] = f'{chapter_index} · {title}'
    quests = []
    for index, (kind, registry, amount, ent, zht, end, zhd) in enumerate(entries):
        entry_base = entry_ids[id(chapters[chapter_index][2][index])]
        quest_id = code(entry_base)
        task = {'id': code(entry_base + 1), 'type': kind}
        if kind == 'item':
            task.update(item={'id': registry, 'count': 1}, count=amount)
        elif kind == 'kill':
            task.update(entity=registry, value=amount)
        elif kind != 'checkmark':
            task[kind] = registry
        reward_item = 'minecraft:bread' if chapter_index < 2 else 'minecraft:iron_ingot'
        quest = {'id': quest_id, 'x': float(index % 4) * 2, 'y': float(index // 4) * 2,
                 'tasks': [task], 'rewards': [{'id': code(entry_base + 2),
                                             'type': 'item', 'item': {'id': reward_item, 'count': 1}, 'count': 2}]}
        if previous:
            quest['dependencies'] = [previous]
        if chapters[chapter_index][2][index] is drive:
            quest['dependencies'].append(code(entry_ids[id(flesh[2])]))
        previous = quest_id
        quests.append(quest)
        for locale, title, description in [('en_us', ent, end), ('zh_cn', zht, zhd)]:
            langs[locale][f'quest.{quest_id}.title'] = title
            langs[locale][f'quest.{quest_id}.quest_desc'] = [description]
        count += 1
    # The distillation controller is part of the same processing milestone.
    if chapter_index == 4:
        q = quests[2]
        q['tasks'].append({'id': code(base + 0x303), 'type': 'item',
                           'item': {'id': 'createdieselgenerators:distillation_controller', 'count': 1}, 'count': 1})
    write(root / f'chapters/{chapter_index:02d}.snbt',
          {'id': chapter_id, 'group': '', 'order_index': chapter_index, 'quests': quests},
          'All task counts and reward quantities are first guesses to tune in play.')
for locale, table in langs.items():
    write(root / f'lang/{locale}.snbt', table)
if args.check:
    expected = Path('modpack/config/ftbquests/quests')
    generated = {p.relative_to(root): p.read_bytes() for p in root.rglob('*') if p.is_file()}
    tracked = {p.relative_to(expected): p.read_bytes() for p in expected.rglob('*') if p.is_file()}
    differences = sorted(str(p) for p in generated.keys() | tracked.keys() if generated.get(p) != tracked.get(p))
    temporary.cleanup()
    assert not differences, f'Quest output differs: {differences}'
    print(f'PASS: {len(generated)} quest files reproduce byte-for-byte.')
else:
    print(f'Wrote {len(chapters)} chapters, {count} quests, and two FTB translation tables to {root}.')
