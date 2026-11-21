"""Author the bilingual quest source; output uses the JSON subset of FTB SNBT."""
import json
from pathlib import Path
import tomllib
import argparse
import tempfile

# All task/reward quantities are first guesses to tune in play.
# kind, registry ID, count, EN title, ZH title, EN readout/instruction, ZH readout/instruction
chapters = [
('Surface contact', '落地', [
 ('dimension','starfold:native_planet',1,'Surface contact','地表信号','Planet level detected. The experience preset starts on the home planet without a supplied ship.','当前位于星球维度。体验预设从母星开始，未配发出生飞船。'),
 ('checkmark','',1,'Local timber','木材储备','Manual confirmation: gather at least four logs of any local wood species, then mark complete. Folded faces belong to one continuous planet.','手动确认：收集至少四根任意当地树种的原木后勾选。六个面构成连续星球。'),
 ('item','minecraft:crafting_table',1,'Field bench','野外工作台','Assembly surface required. Craft a workbench from local timber.','等待组装台面。使用本地木材制作工作台。'),
 ('item','minecraft:wooden_pickaxe',1,'First excavation','开始开采','Excavation tool required. Stone and andesite support the first workshop.','等待开采工具。石料与安山岩用于建设第一间工坊。'),
 ('item','minecraft:cobblestone',16,'Stone reserve','石料储备','Stone reserve below target. Mine near the landing area before travelling farther.','石料储备待补充。先在出生区域附近开采，再扩大活动范围。'),
 ('item','minecraft:torch',8,'Working light','作业照明','Lighting stock required. Mark the route between the shelter and the mine.','等待照明补给。标记住所与矿井之间的路线。')]),
('Ground survival', '地面生存', [
 ('item','minecraft:furnace',1,'Smelting station','冶炼站','Heat processing required. Install a furnace near the workbench.','等待热加工设备。在工作台附近安装熔炉。'),
 ('checkmark','',1,'Solid fuel','固体燃料','Manual confirmation: reserve eight coal or charcoal for smelting and lighting, then mark complete. Smelt logs into charcoal if coal is scarce.','手动确认：储备八份煤或木炭用于冶炼和照明后勾选。缺煤时可烧制原木取得木炭。'),
 ('item','minecraft:iron_ingot',8,'Iron supply','铁料补给','Iron sample required. Home ore tables include iron, copper, coal, gold, zinc and redstone, but no osmium.','等待铁料样本。母星矿表包含铁、铜、煤、金、锌、红石，不含锇。'),
 ('item','minecraft:iron_pickaxe',1,'Reliable excavation','稳定开采','Iron tool required. Use shallow iron and copper to build a mining platform before seeking deep redstone and gold.','等待铁制工具。使用浅层铁铜制造采矿平台，再开采深层红石与金。'),
 ('item','minecraft:copper_ingot',8,'Copper conductors','铜材导体','Copper stock required. Fluid handling and electrical connections will use this supply.','等待铜材储备。流体设备与电气连接将使用这批材料。'),
 ('checkmark','',1,'Food reserve','食物储备','Manual confirmation: store at least eight portions of suitable food and establish a renewable crop or livestock supply, then mark complete. Bread is one option.','手动确认：储备至少八份合适食物，并建立可持续农田或畜牧来源后勾选；面包只是其中一种选择。'),
 ('checkmark','',1,'Surface shelter','地表住所','Manual confirmation: build a lit shelter with storage, place a bed of any colour and set a safe respawn point. Mark home in Atlas, leave and use its waypoint to return before marking complete.','手动确认：建好照明住所与储物箱，放置任意颜色的床并设置安全重生点。在 Atlas 标记基地，离开后依靠航点返回，再勾选完成。'),
 ('item','minecraft:shield',1,'Field protection','野外防护','Shield required. No oxygen or suit progression is imposed by this pack.','等待盾牌。整合包未设置氧气与航天服进度。')]),
('Mechanical power', '机械动力', [
 ('item','create:andesite_alloy',8,'Andesite alloy','安山合金','Alloy supply required. Shallow andesite patches remain enabled on the home planet.','等待合金补给。母星保留浅层安山岩矿斑。'),
 ('item','create:water_wheel',1,'Rotational source','旋转动力源','Mechanical power source required. Use flowing water to drive a wheel.','等待机械动力源。利用水流驱动水车。'),
 ('item','create:shaft',8,'Power transmission','动力传输','Shaft stock required. Connect the wheel to the workshop without overstressing the network.','等待传动轴储备。将水车接入工坊，检查应力余量。'),
 ('item','create:mechanical_press',1,'Press line','冲压生产线','Press required. Sheet metal is used in ship components and fuel equipment.','等待冲压设备。船件与燃料设备需要金属板。'),
 ('item','create:iron_sheet',8,'Sheet stock','铁板储备','Sheet stock required. Process iron ingots under the mechanical press.','等待铁板储备。使用机械压力机加工铁锭。'),
 ('item','create:basin',1,'Mixing vessel','搅拌容器','Basin required. Leave space for a mixer and a heat source.','等待搅拌容器。预留搅拌器与热源的安装空间。'),
 ('item','create:mechanical_mixer',1,'Mixing drive','搅拌动力','Mixer required. Check the required rotation speed in JEI before production.','等待搅拌设备。投产前通过 JEI 核对所需转速。'),
 ('item','minecraft:coal_block',1,'Heat-source stock','热源储备','No Nether is available. Craft a Blaze Burner from three iron sheets, a coal block and a copper ingot. The coal block needs coal, not charcoal. The supplied blaze still needs ordinary fuel after placement to heat brass.','下界已移除。使用三块铁板、一块煤炭块和一块铜锭合成烈焰人燃烧室。造炉煤块必须用煤，不能用木炭替代。自带烈焰人，放置后仍需普通燃料加热黄铜。'),
 ('item','create:blaze_burner',1,'Captured heat','稳定热源','Place and fuel the burner beneath the basin for heated mixing; coal or charcoal can maintain heat. A burner is also consumed by the provisional thruster recipe, so budget another one for production.','将燃烧室放在工作盆下方并加燃料，达到加热搅拌所需热量；煤或木炭可供后续燃烧。暂定推进器配方还会消耗燃烧室，须另计生产备料。'),
 ('item','create:brass_ingot',8,'Brass production','黄铜生产','Brass stock required. Mix copper and zinc with the required heat.','等待黄铜储备。按配方加热混合铜与锌。'),
 ('item','create:electron_tube',2,'Control electronics','控制电子元件','Use glass surrounded by eight redstone dust for Rose Quartz, polish it and make electron tubes. The initial helm, gyro and navigation scope use Create parts without off-world osmium.','玻璃周围放置八份红石粉制作玫瑰石英，打磨后制造电子管。初始驾驶台、陀螺仪与导航观察镜使用 Create 部件，不要求外星锇。'),
 ('item','create:precision_mechanism',1,'Precision assembly','精密装配','Keep sequenced assembly: start with a golden sheet and repeat the small cogwheel, large cogwheel and iron nugget sequence five times. Budget five of each added part per full attempt, plus failed attempts and byproducts; a mechanism is not guaranteed. Establish repeatable production.','保留序列装配：以金板起步，小齿轮、大齿轮与铁粒的装配序列重复五次。每次完整尝试须各备五份追加部件，并计入失败与副产物，不能预算为必得一个构件；建立可重复生产。')]),
('Optional planetary transport', '可选星球运输', [
 ('checkmark','',1,'Plan a small rotor transport','规划小型旋翼运输艇','Optional manual planning record. This former Immersive Aircraft hull task keeps its progress ID and now teaches Create Aeronautics. Plan a pilot seat, symmetric lift, cargo space and a walkable boarding route. Build the craft yourself; reference size and payload remain unverified. Ground exploration and industry remain available without aircraft.','可选手动规划记录：原 Immersive Aircraft 机身任务保留进度 ID，现迁移为 Create Aeronautics 教学。规划驾驶位、对称升力、货舱与可步行登乘路线，自行建造小艇；参考尺寸与载重尚未验收。步行勘探和地面工业无须飞机。'),
 ('checkmark','',1,'Prove the rotational supply','验证旋转动力','Optional manual record, migrated from the Immersive Aircraft engine task. Build a survival rotational source and transmission, then record speed, stress, consumption and endurance. Diesel or gasoline does not directly spin a propeller. Use the native controls first; altitude hold, braking assistance and automatic hover are not promised.','可选手动记录：由原 Immersive Aircraft 发动机任务迁移。建立生存旋转动力与传动，记录转速、应力、消耗和续航。柴油或汽油不能直接让螺旋桨旋转。先验证原生控制；不承诺自动定高、辅助刹停或自动悬停。'),
 ('checkmark','',1,'Arrange lift and controls','布置升力与控制','Optional manual record, migrated from the Immersive Aircraft propeller task. Test Aeronautics propeller rotation and thrust direction, lift symmetry and native throttle. A gyro propeller bearing tries to keep its propeller upright; it does not guarantee a balanced or hovering craft. Use JEI and the bundled guide for parts.','可选手动记录：由原 Immersive Aircraft 螺旋桨任务迁移。测试航空学螺旋桨旋向与推力方向、升力对称性及原生油门。陀螺螺旋桨轴承尝试保持螺旋桨朝上，不保证整艇平衡或悬停。通过 JEI 与模组指南核对部件。'),
 ('checkmark','',1,'Empty-craft flight trial','空载试飞','Optional manual flight record, migrated from the Immersive Aircraft biplane task. With survival power, demonstrate lift-off, slow movement, turning, slowing down and landing, then leave and board again. Record the actual inputs and power use. This checkmark has no automatic flight detector; a low-altitude trial does not certify an orbital ship.','可选手动飞行记录：由原 Immersive Aircraft 双翼机任务迁移。使用生存动力验证离地、低速移动、转向、减速与着陆，再离艇并重新登艇。记录实际操作和能耗。此勾选不自动检测飞行，低空试飞不认证入轨船。'),
 ('item','aeronautics:white_envelope',8,'Optional buoyant design','可选浮力设计','Optional free-build branch. Envelopes remain available for buoyant craft, but the small rotor transport does not require a balloon or a runway. This item task does not certify flight.','可选自由建造分支：气囊仍可用于浮空艇，但小型旋翼运输艇不要求气球或跑道。此物品任务不认证飞行。'),
 ('item','simulated:physics_assembler',1,'Physics assembly','物理结构组装','Physics Assembler required. Study its placement and assembly controls before constructing the orbital hull.','等待物理结构组装器。建造入轨船体前确认安装方向与组装操作。'),
 ('item','create:super_glue',1,'Structural bonds','结构连接','Glue required. Join the intended ship blocks and avoid attaching the launch pad.','等待强力胶。连接预定船体方块，避免将起飞平台粘入结构。'),
 ('item','aeronautics:andesite_propeller',1,'Surface survey kit','地表勘察组件','Survey propulsion component acquired. Observe face boundaries and keep clear of unstable fold points. No automatic fold-point detector is supplied.','勘察推进组件已获取。观察面边界，远离不稳定折点。当前未提供折点自动检测任务。')]),
('Fuel and orbit', '燃料与入轨', [
 ('item','createdieselgenerators:oil_scanner',1,'Oil prospecting','油田勘探','Oil scanner required. Oil-field availability in the custom planet generator remains a play-test requirement.','等待油田探测器。自定义星球生成器中的油田可用性仍需实测。'),
 ('item','createdieselgenerators:pumpjack_bearing',1,'Extraction drive','抽油动力','Optional CDG engineering branch. The bearing alone is not an extractor: use the CDG guide and JEI for the complete pumpjack, drilling and power requirements. This route does not gate the compact Starfold extractor, fuel production or the first ship.','可选 CDG 工程分支：轴承本身不能抽油，按 CDG 指南与 JEI 核对完整抽油结构、钻井与动力要求。此路线不阻塞 Starfold 紧凑抽油机、燃料生产或首船。'),
 ('item','create:fluid_tank',4,'Fuel processing','燃料加工','Fluid tanks and a Distillation Controller required. Convert placed Create tanks into a distillation tower; the distillation-tank block has no separate inventory item. Supply heat for tier-one diesel or gasoline.','等待流体储罐与蒸馏控制器。将已放置的 Create 储罐转换为蒸馏塔；蒸馏罐方块没有独立背包物品。接入热源后生产第一级柴油或汽油。'),
 ('checkmark','',1,'First diesel or gasoline batch','首批柴油或汽油','Manual production record: distil naturally extracted crude into diesel OR gasoline and verify the actual output tank. Either fuel alone completes this task; do not submit both. No reliable automatic OR detector is configured here. Record amount, time, heat and energy costs. The old eight-bucket ascent estimate is unverified; size the flight reserve during ship trials.','手动生产记录：将自然抽出的原油蒸馏为柴油或汽油，核对实际成品罐。任意一种即可完成，无须同时提交两种；此处未配置可靠的自动 OR 检测。记录产量、耗时、热量和能耗。旧八桶入轨估计未经验证，飞行储备留待试船标定。'),
 ('item','create:fluid_tank',4,'Onboard storage','船载储罐','Fluid tank stock required. Tanks must belong to the same assembled ship as the thrusters. Leave return fuel in reserve.','等待流体储罐。储罐与推进器必须属于同一组装船体，保留返航燃料。'),
 ('item','starfold:thruster',1,'Thrust hardware','推力设备','One thruster item is only a component check. Install paired nozzles for lift, descent, forward/reverse and lateral control; nozzle exhaust points opposite the push. This pack requires diesel or gasoline in compatible tanks on the same hull. Fuel adds mass; compare available lift with fuelled weight and keep braking margin.','一个推进器物品只检测零件。成对布置升降、前后和侧向喷口，喷口朝向与推力相反。本包需要同船兼容储罐内的柴油或汽油；燃料计入质量，比较升力与加油后船重并保留刹停余量。'),
 ('item','starfold:gyro',1,'Attitude control','姿态控制','Mount and power the gyros: the reference layout uses two, each with its own lever switched on. Gyros do not consume propulsion fuel, but altitude hold fires real lift nozzles and burns fuel while airborne. They do not replace balanced thrust or sufficient lift.','安装陀螺仪并通电：参考布置使用两个，每个各装一个拉杆并打开。陀螺仪本身不耗推进燃料，但悬停会调用真实升力喷口，离地保持高度仍会耗油。陀螺仪不能代替推力平衡或足够升力。'),
 ('item','starfold:helm',1,'Command station','驾驶控制台','Helm acquired. Check ship connections, clearances and reverse thrust before departure.','驾驶台已获取。起飞前检查船体连接、离地间隙与反向推进。'),
 ('item','starfold:scope',1,'Visual instruments','目视仪表','Basic scope acquired. Upgrade with a precision mechanism, an electron tube and two brass sheets before the first trip.','基础观察镜已获取。首航前使用精密构件、电子管与两块黄铜板升级为导航观察镜。'),
 ('advancement','starfold:ship/lift_off',1,'Lift-off record','离地记录','Lift-off advancement required. Perform a controlled ascent before committing to orbit.','等待离地进度记录。先完成可控爬升，再继续入轨。'),
 ('advancement','starfold:ship/space',1,'Space-region record','太空区域记录','Space-entry advancement required. Status on completion: currently in the space region. Keep the helm occupied and monitor fuel.','等待进入太空的进度记录。完成后状态：当前位于太空区域。保持驾驶，监测燃料。'),
 ('item','starfold:airstair',1,'Boarding route','登船路线','Airstair acquired. Rehearse landing and boarding at home. The existing home-return advancement records later off-world player landing history, not this rehearsal or same-ship transport.','登船梯已获取。在母星演练着陆与登船。现有母星返航进度记录后续外星返航的玩家着陆历史，不认证本次演练或同船运输。')]),
('First off-world metals', '第一次跨星：锇', [
 ('advancement','starfold:ship/other_planet',1,'Off-world landing','外星着陆','Other-planet advancement required. Seek a rocky planet or asteroid for osmium and rare deep diamonds. Diamonds occur in the bottom 35% of mineable depth; abundance is a first guess. These worlds are within the current system.','等待外星着陆进度记录。岩质星球与小行星提供锇及深层稀有钻石；钻石位于可挖深度底部35%，丰度为待调初猜。这些星球仍在当前恒星系内。'),
 ('item','mekanism:raw_osmium',8,'Osmium cargo','锇矿货物','Eight raw osmium is the starter cargo threshold, not proof of extraction or transport. Keep it raw until the home unloading record; larger industrial upgrades need additional ore.','8份原锇为启动货物门槛，不证明开采或运输；登记母星卸货前保留原矿。更大工业升级仍需另备锇。'),
 ('item','mekanism:ingot_osmium',8,'Refined osmium','精炼锇材','Osmium ingots required. Mekanism begins after this off-world supply is available.','等待锇锭。取得外星供料后启动 Mekanism 工业线。'),
 ('advancement','starfold:ship/home',1,'Home landing record','母星着陆记录','Player home-landing history required after off-world landing and re-entry to space. This advancement does not verify the same ship or returned cargo; complete the separate unloading record.','等待玩家返航着陆历史：外星着陆并再次入太空后返回母星。该进度不核对同船或运回货物，另完成卸矿记录。'),
 ('item','createaddition:alternator',1,'Mechanical to electrical','机械能转电能','Optional Create-powered route: connect rotation to the electrical line. A heat generator can bootstrap the infuser directly; this branch does not gate circuit processing.','可选旋转供电路线：将 Create 动力接入电气线。也可用热力发电机直接启动灌注机，本分支不阻塞电路加工。'),
 ('item','mekanism:metallurgic_infuser',1,'Circuit processing','电路加工','Metallurgic Infuser required. Bootstrap with a heat generator or use the optional Create alternator route. Heat generator, infuser and two basic circuits consume four osmium ingots in total, excluding operating fuel. Supply power and the specified infusion material.','等待冶金灌注机。可用热力发电机启动，或选用 Create 交流发电机路线。热力发电机、灌注机和两块基础电路合计消耗4锇锭，不含运行燃料。接入电力并提供灌注材料。'),
 ('item','mekanism:basic_control_circuit',2,'Basic control circuits','基础控制电路','Control circuits required. Osmium starts Mekanism processing and later advanced circuits for precision optics.','等待基础控制电路。锇用于启动 Mekanism 加工，并进一步制作精密观察镜所需的高级电路。'),
 ('item','starfold:scope_nav',1,'Navigation optics','导航光学','Navigation scope acquired through Create only, without osmium. Check target locking and relative approach speed before the first departure.','导航观察镜通过 Create 路线制造，无须锇。首次起飞前检查目标锁定与相对接近速度。'),
 ('item','mekanism:electrolytic_separator',1,'Hydrogen production','氢气生产','Electrolytic Separator required. Supply water and power; chemical hydrogen is not yet a thruster fluid.','等待电解分离器。供应水与电力；化学形态氢不能直接作为推进器流体。'),
 ('item','mekanism:rotary_condensentrator',1,'Fluid conversion','流体转换','Rotary Condensentrator required. Condense hydrogen into its fluid form before filling ship tanks.','等待旋转冷凝器。先将氢转为流体形态，再向船载储罐加注。'),
 ('item','mekanism:hydrogen_bucket',1,'Second-tier sample','第二级燃料样本','Liquid hydrogen sample required. Current impulse per mB is twice diesel at the same configured fuel mass. A bucket does not certify a production line or ascent budget; record the separate production check.','等待液氢样本。当前每mB冲量为柴油两倍，配置燃料质量相同。持有一桶不认证生产线或入轨预算，另登记实际生产核对。')]),
('System expansion', '星系扩张', [
 ('item','mekanism:raw_tin',8,'Frozen-world tin','冰冻星锡矿','Tin cargo required. Frozen planet ore tables provide tin and lead.','等待锡矿货物。冰冻类星球矿表提供锡与铅。'),
 ('item','mekanism:raw_lead',8,'Frozen-world lead','冰冻星铅矿','Lead cargo required. Establish a transport route rather than relying on one small expedition.','等待铅矿货物。建立运输路线，避免只依赖单次小规模采集。'),
 ('item','mekanism:fluorite_gem',8,'Crystal-world fluorite','晶体星萤石','Fluorite sample required. Crystal worlds also provide quartz, amethyst and a smaller deep-diamond supply (bottom 35%; first-guess abundance).','等待萤石样本。晶体类星球还提供石英、紫水晶及更少量深层钻石（可挖深度底部35%，丰度为待调初猜）。'),
 ('item','mekanism:advanced_control_circuit',2,'Advanced circuitry','高级控制电路','Advanced circuits required. Prepare the electronics for precision optics.','等待高级控制电路。为精密光学准备电子元件。'),
 ('item','starfold:scope_precise',1,'Precision optics','精密光学','Precision scope acquired. This recipe upgrades optics. Leaving the home system requires an interstellar drive and deuterium; the drive recipe requires a Host Ganglion; there is no separate pilot boss-permission flag.','精密观察镜已获取。该配方升级光学设备。离开母恒星系需要星际引擎和氘；引擎配方需要宿主神经节，不另设驾驶员 boss 许可标记。'),
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
 ('item','mekanismgenerators:deuterium_bucket',1,'Skim deuterium at Amber','在 Amber 掠取氘','Approach Amber and dip the intake into its pressure field, then pipe harvested fluid from storage into a Create Spout and fill an empty bucket on a depot or belt (1000 mB). Ordinary Create tanks do not fill buckets by survival right-click. Heavy-water separation is disabled; rotary conversion only recycles existing deuterium. Reference rate is 10 mB/tick at pressure 0.5 (first guess); ordinary blocks fail at 0.5, osmium pressure hull at 0.85 (provisional). The bucket detector does not certify location.','接近 Amber，让采集口进入气压场，再将已采流体经管道与有动力机械泵送入 Create 注液器，在其下置物台或传送带放空桶，灌装1000mB氘样本。普通 Create 储罐不能靠生存右键装桶。重水电解已禁用，旋转转换只处理已有氘。压力0.5时参考产率10 mB/tick（待调初猜），普通方块从0.5损坏，锇耐压壳从0.85损坏（暂定）。桶检测不认证采集地点。'),
 ('checkmark','',1,'Fill the onboard tank','装满船载储罐','Manual flight-log confirmation, not automatic telemetry: return the sample and fill a Create tank to 8000 mB of deuterium. Check the actual tank gauge before marking complete. Keep separate return fuel; whole-hull pressure can exceed intake pressure.','手动飞行记录，非自动遥测：归还样本，将一个 Create 船载储罐装至8000 mB氘。核对实际液位后再勾选。另留返航燃料；整舰气压可能高于采集口气压。'),
 ('checkmark','',1,'Leave the home system','离开母恒星系','Manual flight-log confirmation: install an interstellar drive on the same hull, leave the home system with deuterium available and verify thrust beyond its boundary, then mark complete. The named starter planets orbit the home sun; other systems exist in the experience universe. Select an actual destination in Atlas before departure. Outside home, thrust requires both an interstellar drive and deuterium; coasting is not blocked. Impulse starts at 25 times diesel, not a measured one-bucket ascent. Host ganglion is required to fabricate the drive.','手动飞行记录：在同一船体安装星际引擎，携带氘离开母恒星系，核对边界外仍有推力后再勾选。具名起步行星围绕母恒星运行，体验宇宙另有其他星系；出发前在 Atlas 选择实际目的地。边界外推进需要星际引擎和氘，不禁止惯性滑行。冲量初值为柴油25倍，不声称一桶入轨已标定。引擎制造需要宿主神经节。')])
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
  'Combat record required: defeat the Host in the hive crater. Each hive planet has its own encounter, and a defeated Host returns after seven in-game days. Its loot table yields four Host Ganglia, the biological component required by the mechanical interstellar drive; recover and store them safely.',
  '等待战斗记录：在巢穴巨坑击败宿主。每颗有巢穴的星球各自记录一次遭遇，击败后经过七个游戏日会再次出现。战利品表掉落四个宿主神经节，为机械线星际引擎提供必需的生物部件；及时拾取并妥善保管。'),
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

# Stable ID: stage-one mining is inserted without renumbering existing progress.
miner = ('item', 'starfold_mining:mining_platform', 1,
         'Establish a mining site', '建立采矿点',
         'Craft with four iron ingots, two copper ingots, an iron pickaxe, a furnace and a lever. Place on the home surface, add furnace fuel or FE, then Scan before starting. The 5x5 shaft is excavated layer by layer down to 512 blocks; keep the shaft fenced and remove output regularly. Completed surveys appear in Atlas as historical snapshots. This item task does not certify extraction.',
         '使用四块铁锭、两块铜锭、铁镐、熔炉和拉杆合成。放在母星地表，加入熔炉燃料或 FE，先探矿再启动。平台逐层开挖 5×5、最深 512 格的矿井；围住井口并定期取走产物。完成的探矿结果以历史快照显示在 Atlas。物品任务不认证实际采出。')
entry_ids[id(miner)] = 0x1000000000010900
chapters[1][2].insert(5, miner)

# Stage two extends the existing IDs. Moving an entry never changes its task/reward IDs.
def add_entry(chapter, identifier, entry):
    assert identifier not in entry_ids.values(), f'Duplicate quest ID: {identifier:016X}'
    entry_ids[id(entry)] = identifier
    chapters[chapter][2].append(entry)
    return entry


deep_supply = add_entry(2, 0x1000000000020D00, (
    'checkmark', '', 1, 'Deep supply and output handling', '深层供料与产物收纳',
    'Manual extraction record: obtain actual gold and redstone with the mining platform, not just a survey. Follow normalized layer depth rather than vanilla Y levels. Record siting, scanning, excavation time, fuel or FE, hand mining and clearing waste. Connect usable storage to the 18-slot output; check full storage and fluid stops without lost items. Stay loaded and online; offline mining is not supplied.',
    '手动开采记录：使用采矿平台实际取得金与红石，不能只凭扫描结果勾选。按归一化地层深度找矿，不照搬原版 Y 值。记录选址、扫描、开挖耗时、燃料或 FE、手挖量与废石清理。为 18 格输出接入可用收纳，检查满库存与遇流体停止且不吞产物。保持区块加载与所有者在线，未提供离线采矿。'))
crafters = add_entry(2, 0x1000000000020F00, (
    'item', 'create:mechanical_crafter', 1, 'Mechanical crafting facility', '机械合成设施',
    'Learn a powered mechanical crafting grid for later large recipes. Current first-ship helm, gyro and thruster recipes use ordinary shaped crafting; this optional workshop technique is not their recipe requirement. One crafter item does not certify a full grid; budget its transmission separately.',
    '学习供后续大型配方使用的机械合成阵列。当前首船驾驶台、陀螺仪和推进器采用普通有序合成，机械阵列不是这些配方的要求。取得一个机械合成器不代表完整阵列、朝向、输出或应力达标；另计其传动预算。'))
workshop = add_entry(2, 0x1000000000020E00, (
    'checkmark', '', 1, 'Run the workshop again', '重复运行工坊',
    'Manual processing record: run powered pressing and heated mixing, then make brass, electron tubes and successful precision mechanisms in separate production batches. Actually craft a helm or gyro using its current shaped recipe. Verify speed, stress, burner fuel, assembly losses and output storage. Owning machines alone does not complete this record.',
    '手动加工记录：实际运行动力冲压与加热搅拌，分批生产黄铜、电子管和成功的精密构件，并按当前普通有序配方实际制造驾驶台或陀螺仪。核对转速、应力、热源燃料、装配损耗与产物收纳。仅持有机器不能完成此记录。'))
parts_budget = add_entry(2, 0x1000000000021000, (
    'checkmark', '', 1, 'First-ship materials and repair stock', '首船材料与维修备料',
    'Manual materials record: use current JEI recipes to list workshop machines, transmission, heat fuel, mechanical crafters, compact oil extractor, distillation and pipes as facility costs. Separately list batch inputs, failed assembly attempts, hull, assembler, glue, helm, gyro, thrusters, navigation scope, tanks, boarding access and repair spares; manufacture the agreed first-ship component stock. Do not invent a fixed ship size or fuel budget; ship assembly and trial flight follow in the next stage.',
    '手动物料记录：按当前 JEI 列出工坊机器、传动、热源燃料、机械合成器、紧凑抽油机、蒸馏与管道等设施成本。另列每批投入、装配失败、船壳、组装器、胶、驾驶台、陀螺仪、推进器、导航镜、储罐、登船通道与维修备件，制造约定首船的零件储备。不预设固定船型或燃料预算；船体组装与试飞属于下一阶段。'))
# Deep supply comes from the stage-one platform, before bulk workshop production.
chapters[2][2].remove(deep_supply)
chapters[2][2].insert(0, deep_supply)

cargo = add_entry(3, 0x1000000000030900, (
    'checkmark', '', 1, 'Optional cargo round trip', '可选载货往返',
    'Optional manual transport record: carry a recorded batch of ore, supplies or equipment between base and a mine or oil field with survival power. Compare empty, symmetric loaded and uneven loads; verify unloading, intact cargo, leaving and boarding again. Test face crossing and save/rejoin on the ground and in flight separately; record power, controls, atmosphere and any unsupported cases. No automatic cargo or flight detector is supplied.',
    '可选手动运输记录：使用生存动力在基地与矿区或油田之间运送一批有记录的矿物、补给或设备并往返。比较空载、对称满载与偏载，核对装卸、货物完整、离艇与再次登艇。另测跨面、地面和飞行中保存重进，记录能耗、操作、大气影响及未支持边界。未提供货运或飞行自动检测。'))
extractor = add_entry(4, 0x1000000000040D00, (
    'item', 'starfold_mining:oil_extractor', 1, 'Compact oil extractor', '紧凑抽油机',
    'Craft the Starfold Mining oil extractor using the current JEI recipe. Compare discovered natural CDG oil fields and deploy on the correct planet and chunk. Use the device survey and controlled well operation, fuel or FE and a fluid outlet; no aircraft or CDG pumpjack bearing is required. The item task does not certify extraction. Record actual well costs and time rather than assuming a long hand-built pipe shaft.',
    '按当前 JEI 配方制作 Starfold Mining 抽油机。比较已发现的自然 CDG 油田，在正确星球与区块部署，使用设备勘探与受控建井、燃料或 FE 及流体输出。无须飞机或 CDG 大型抽油机轴承。物品任务不认证抽油；记录实际建井成本与耗时，不假定必须手铺数百格井管。'))
pipes = add_entry(4, 0x1000000000040E00, (
    'item', 'create:mechanical_pump', 1, 'Pump, pipes and crude storage', '泵、管道与原油储罐',
    'Provide fluid pipes, a powered mechanical pump and separate crude/output tanks. Check pump direction and connections in JEI and the guide. Keep crude, diesel and gasoline lines separate. Test external tank transfer before relying on a distillation tower; one pump item does not certify a working line.',
    '备齐流体管道、带旋转动力的机械泵与独立原油及成品储罐，按 JEI 与指南核对泵向和连接。分开原油、柴油和汽油管路。先验证外罐输送，再依赖蒸馏塔；一个泵物品不认证管线已运行。'))
extraction = add_entry(4, 0x1000000000040F00, (
    'checkmark', '', 1, 'Natural crude into an external tank', '自然原油进入外罐',
    'Manual extraction record: survey a natural CDG field, complete the well operation and transfer real crude from the compact extractor into an external tank. Log field coordinates, energy and time; CDG deposits are chunk reserves, not crude ore blocks. Compare reserve and tank quantities before/after extraction, a full output tank and save/rejoin; check no loss, duplication or depletion while blocked. Atlas discoveries are historical snapshots, not a live reserve gauge. Keep the owner online and chunks loaded.',
    '手动抽油记录：勘探自然 CDG 油田、完成建井，再由紧凑抽油机向外部储罐输出真实原油。记录油田坐标、能源与耗时；CDG 油藏是区块储量，不是原油矿块。对照抽取、外罐装满及保存重进前后的储量与罐量，检查无丢失、复制或堵塞空耗。Atlas 发现是历史快照，不是实时余量表。保持所有者在线、区块加载。'))
refill = add_entry(4, 0x1000000000041000, (
    'checkmark', '', 1, 'Repeat the fuel supply', '再次补充成品燃料',
    'Manual production record: after the first batch, extract and distil a second batch of diesel OR gasoline from natural crude. Either fuel alone suffices. Measure both batches separately: crude input, finished output, time, energy, heat fuel and maintenance. Prove the factory can refill storage without commands or injected oil; record production capacity rather than a fixed ascent bucket count.',
    '手动生产记录：首批之后，再从自然原油抽取并蒸馏第二批柴油或汽油，任意一种即可。分开记录两批的原油投入、成品产量、耗时、能耗、热源燃料与维护。无指令补料或注入原油，证明工厂可以再次补充储罐；记录产能，不用固定入轨桶数验收。'))
preflight = add_entry(4, 0x1000000000041100, (
    'checkmark', '', 1, 'Assemble and inspect before departure', '起飞前组装与验船',
    'Manual ground inspection: glue and assemble the intended hull without the launch pad. Install helm, powered gyros, thrusters, navigation scope and compatible fuel tanks on that hull; switch on the gyro levers and switch off manual thruster redstone. Test supply, reverse thrust and clearances. Fit outward-facing airstairs below doors; settle, wait for deployment and walk aboard before right-clicking the helm. Remain settled after taking control until the launch marker is recorded. Use the navigation scope to check target lock and relative approach speed before the first departure. Log dry/fuelled mass and return reserve; actual flight trials set the fuel budget. This checkmark does not detect installation.',
    '手动地面检查：用胶与组装器组装预定船体，排除起飞平台。将驾驶台、通电陀螺仪、推进器、导航镜与兼容燃料罐装在同船体，打开陀螺仪拉杆、关闭推进器手动红石。测试供油、反向推进和间隙；舱门下朝外安装登船梯，停稳等梯展开，沿梯上船后右键驾驶台。接管后保持停稳，确认起飞点记录再起飞。首次离开母星前用导航镜核对目标锁定与相对接近速度。记录干重、加油质量与返航余量，实际试飞再定燃料预算。此勾选不自动检测安装。'))

# Runtime reference: free-built hull, with bounded supplied-fixture evidence only.
first_ship_reference = {
    'en_us': [
        'Optional B6-F reference: 185 placed blocks; supplied 4000 mB diesel local-flight fixture passed, without survival or payload qualification. Coordinates: deck Y=0, west (-X) forward. Read each five-character row X=-2 to +2, rows Z=-2 to +2.',
        'Y=-6,-5,-4,-2,-1: CCCCC / CCCCC / CCCCC / CCCCC / CCCCC',
        'Y=-3: TnCnT / wCCCe / BCCCB / wCCCe / TsCsT',
        'Y=0: PPAPP / PPvPP / P^P^P / PPvPP / PPAPP',
        'Y=1: ..d.. / ..... / G.H.G / ..... / ..d..',
        'Y=2: ..d.. / ..... / l.N.l / ..... / ..d..',
        'C cobble; P oak planks; T fluid tank; B cargo barrel facing outward; n/s/w/e/up(^)/down(v) are NOZZLE EXHAUST directions. A outward airstair, d open door half, G gyro, l powered lever, H helm facing west, N navigation scope facing west. Dots are air. Keep both outer boarding approaches clear.',
        'Placed items: 136 cobble, 19 planks, 12 thrusters, 2 gyros, 2 levers, 1 helm, 1 navigation scope, 2 airstairs, 2 door items, 4 tanks, 2 barrels. Glue, assembler, tools, fuel and spares are separate. Recover the captured assembler after assembly. Standard door crafting leaves one extra door.',
        'Keep ground service platforms outside the glue selection. Fuel must be extractable from tanks on this hull. Four full tanks can be too heavy: measure dry/wet mass and lift margin; hovering burns fuel. Equal initial fills do not guarantee balanced draining. Old B6 handling measurements do not certify this changed ship.',
    ],
    'zh_cn': [
        '可选 B6-F 参考：185格船体，供给4000mB柴油的独立夹具短飞已通过，未验从零生存及载荷。甲板Y=0，船首向西（-X）；每行五格从X=-2到+2，各行从Z=-2到+2。',
        'Y=-6,-5,-4,-2,-1：CCCCC / CCCCC / CCCCC / CCCCC / CCCCC',
        'Y=-3：TnCnT / wCCCe / BCCCB / wCCCe / TsCsT',
        'Y=0：PPAPP / PPvPP / P^P^P / PPvPP / PPAPP',
        'Y=1：..d.. / ..... / G.H.G / ..... / ..d..',
        'Y=2：..d.. / ..... / l.N.l / ..... / ..d..',
        'C圆石，P橡木板，T流体储罐，B朝外货桶；n/s/w/e/^/v分别为朝北/南/西/东/上/下排气的喷口，推力相反。A朝外登船梯，d打开的门上下半部，G陀螺仪，l已打开拉杆，H朝西驾驶台，N朝西导航镜。点号是空气；两侧登船路线保持净空。',
        '船体物品：136圆石、19木板、12推进器、2陀螺仪、2拉杆、1驾驶台、1导航镜、2登船梯、2门物品、4储罐、2货桶。胶、组装器、工具、燃料和备件另备；组装后挖回被捕获的组装器。门正常一批制作会多余一扇。',
        '岸侧维护平台留在胶框外；推进燃料须在同船可抽取罐中。四罐全满可能超重，先测干重/湿重与升力余量；悬停仍耗油。初始等量不保证放油始终平衡；旧B6操纵数据不认证这艘改船。',
    ],
}

local_trial = add_entry(4, 0x1000000000041200, (
    'checkmark', '', 1, 'Land, reboard and repair at home', '母星着陆、再登船与维修',
    'Manual local-flight record: after lift-off, demonstrate short low-altitude movement, turning and braking near the launch site, then land and settle before leaving the helm. Walk down the deployed airstair, board again and resume control. While parked, rehearse a repair by replacing a non-critical hull block, restore its bond and inspect the ship again. Log fuel before/after, load, controls and any damage; keep a landing reserve. The lift-off advancement alone does not certify these actions, and this checkmark does not detect them. B6-F is a reference layout proposal, not a measured fuelled flight guarantee. Space entry and off-world mining follow separately.',
    '手动母星试飞记录：离地后在起飞点附近完成短程低空移动、转向与刹停，着陆停稳后再退出驾驶。沿展开的登船梯步行离船、再次登船并恢复驾驶。停泊时演练更换一块非关键船壳，恢复连接并重新验船。记录前后燃料、载荷、操作与损伤，保留着陆余量。离地进度不认证这些动作，勾选也不自动检测。B6-F 仅为参考布置提案，不保证加油后的实测性能；进入太空和外星采矿分别留待后续。'))

# Stage four adds explicit manual voyage records without reusing old progress IDs.
trip_departure = add_entry(4, 0x1000000000050C00, (
    'checkmark', '', 1, 'Plan the first mining round trip', '登记首次采矿往返',
    'Manual departure record, not automatic telemetry. Compare Fleck (home moon) and Anvil (rocky) in Atlas; choose current distance, approach speed and actual ore survey, not a fixed planet number. Both are in the home system and need no interstellar drive. Record the ship UUID, home marker, target, cargo baseline, tank readings and a separate landing reserve. Budget outbound and return ascent, transfer and braking sequentially; 123 mB from a local trial is not a trip budget. Pack an iron pickaxe, Ultimine, food, lights and repair tools. Mark home before departure and rehearse finding the parked ship. The checkmark does not detect readiness.',
    '手动出发记录，非自动遥测：在 Atlas 比较母星卫星 Fleck 与岩质星 Anvil，按当前距离、接近速度及实际探矿选择，不固定星球编号。两者在母恒星系内，无须星际引擎。登记船体UUID、基地标记、目标、货物基线、各罐液位和独立着陆储备。按去程与返程起飞、转移、制动逐段预算；近地短飞123mB不代表往返预算。带铁镐、Ultimine、食物、照明和维修工具。出发前标记基地并演练找回停泊船。勾选不自动检测准备情况。'))
trip_loading = add_entry(5, 0x1000000000050D00, (
    'checkmark', '', 1, 'Load and check the return leg', '装矿与返程复核',
    'Manual loading record, not automatic cargo tracking. Mine at least eight raw osmium on the selected world with an iron pickaxe and Ultimine, preserving a safe exit. Load it into the recorded ship, log exact raw-ore count, ship UUID and each tank, then recheck return lift and fuel with the current mass. Item possession does not prove mining location or ship transport. Record a landing marker before leaving the ship; stop spending propulsion fuel while safely parked. If return fuel is insufficient, remain parked and arrange a fuel delivery or rescue ship; local oil is not guaranteed.',
    '手动装矿记录，非自动货物追踪：在所选星球用铁镐及 Ultimine 采至少8份原锇，保留安全出口。装入登记船体，记录原矿数量、船体UUID与各罐液位，按当前质量重新核对返程升力与燃料。持有物品不证明采集地点或船运。离船前登记着陆点，安全停泊后停止浪费推进燃料。返航油不足时停泊，安排送油或救援船；不保证当地能产油。'))
trip_delivery = add_entry(5, 0x1000000000050E00, (
    'checkmark', '', 1, 'Unload the same-ship shipment', '同船卸矿核对',
    'Manual delivery record, not automatic same-ship certification. After the home landing record, compare the departure, loading and arrival ship UUID, unload at least eight raw osmium into home storage and reconcile counts and tank readings. Keep the ore raw until unloading is recorded, then smelt it. The home advancement follows player history and does not prove ship identity or cargo provenance. An offline voyage-log checker only checks consistency of declared observations; retain screenshots or server receipts. Do not claim an unrecorded leg or replacement ship as the same-ship trip.',
    '手动交付记录，非自动同船认证：母星着陆后核对出发、装矿和抵达的船体UUID，将至少8份原锇卸入基地仓库，对齐数量与各罐液位。登记卸货前保持原矿，之后再冶炼。母星返航进度按玩家历史推进，不证明船体身份或货物来源。离线航程日志工具只核对所填观察的一致性，保留截图或服务器回执。缺失航段或更换船体不能写成同船往返。'))

# Stage-five records are manual observations, never automatic plant or route telemetry.
hydrogen_run = add_entry(5, 0x1000000000050F00, (
    'checkmark', '', 1, 'Repeat the liquid-hydrogen supply', '重复供应液氢',
    'Manual production record, not automatic telemetry: supply water and sustained power to the Electrolytic Separator, send chemical hydrogen to a Rotary Condensentrator in condensing mode, then transfer fluid hydrogen to a compatible ship tank. Configure the unused oxygen output so it cannot block production. Record two separately collected batches, input/output quantities, power, ports, mode and tank readings. Verify extraction support; a bucket alone does not prove this chain. Budget ascent and return from actual mass and gravity; no fixed bucket promise.',
    '手动生产记录，非自动遥测：向电解分离器供水与持续电力，将化学氢送入冷凝模式的旋转冷凝器，再将流体氢输送至兼容船载储罐。处理不用的氧气输出，避免堵塞。记录两次分别收取的批次、输入产出量、电源、端口、模式与液位；核对抽取支持。一桶样本不证明此生产链。按实际质量和重力预算起飞返航，不承诺固定桶数。'))
upgrade_record = add_entry(6, 0x1000000000060C00, (
    'checkmark', '', 1, 'Compare the transport upgrade', '核对运输船升级',
    'Manual engineering record, not flight certification: compare the same hull before and after replacing cobblestone ballast with aluminum hull plating. Record actual dry mass, cargo, fuel type and volume, effective gravity and lift nozzles; compare wet mass, lift margin and ideal delta-v. Keep access and nozzle directions intact. Hydrogen has twice diesel impulse per mB, not half fuel mass. Cargo mass must be observed or explicitly modelled. Lower mass does not establish handling, endurance or pressure safety; full-block plating still has default pressure limit 0.5.',
    '手动工程记录，非飞行认证：比较同一布局以铝轻质板替换圆石压舱前后，登记实际干质量、货物、燃料种类与体积、有效重力及升力喷口，核对湿质量、升力余量和理想Δv。保持通行与喷口方向。液氢每mB冲量为柴油两倍，燃料质量并不减半。货物质量须实测或明确建模。减重不认证操纵、续航或耐压；完整轻质板默认耐压仍为0.5。'))
route_record = add_entry(6, 0x1000000000060D00, (
    'checkmark', '', 1, 'Repeat a supplied resource route', '重复有补给的资源路线',
    'Manual route record, not automatic transport proof: choose one needed resource world; record two completed deliveries with ship UUID, raw cargo counts, tank readings and departure/arrival observations. Mark the outpost, store food, light, tools, spare hull and compatible fuel, and arrange rescue fuel before leaving home. Reconcile production, shipment and base unloading. Stay parked if reserves are insufficient. Optional resource tasks do not prove travel; no requirement to visit every planet or build a reactor. Structures and random loot are not core gates.',
    '手动路线记录，非自动运输认证：按需求选择一条资源路线，登记两次完成交付的船体UUID、原矿数量、液位与出发抵达观察。标记前哨，储备食物、照明、工具、备用船壳与兼容燃料，离家前安排救援供油。对齐生产、装货和基地卸货；储备不足就停泊。可选资源物品任务不证明旅行，无须遍访星球或建设反应堆，主线不依赖随机结构战利品。'))

# Stage six joins independently prepared gas and Host routes. Stage seven uses
# explicit manual observations, not invented automatic voyage/outpost detectors.
gas_withdrawal = add_entry(8, 0x1000000000080B00, (
    'checkmark', '', 1, 'Withdraw and repeat the gas collection', '撤离并重复采气',
    'Manual gas-flight record, not automatic telemetry: log intake pressure, the highest hull exposure, tank amounts, damage and propulsion reserve before entry and after safe withdrawal. Collect a second separately measured batch and withdraw again. Pressure hull does not shield the scoop, controls or tanks; retreat before the weakest component limit. A full 8000 mB tank is storage evidence only, not an expedition budget. Reserve a separate deuterium bucket for crafting and retain flight fuel.',
    '手动采气飞行记录，非自动遥测：入场与安全撤离后登记采集口压力、船体最高暴露压力、液位、损伤与推进储备，再单独记录第二批并再次撤离。耐压壳不保护采集口、控制件或储罐，须在最弱部件阈值前撤退。8000mB满罐只证明储存，不是远征预算；另留制造用氘桶及飞行燃料。'))
drive_installation = add_entry(8, 0x1000000000080C00, (
    'checkmark', '', 1, 'Inspect the installed interstellar drive', '核对同船星际引擎',
    'Manual installation record, not automatic detection: install the crafted drive on the same assembled hull as thrusters and extractable deuterium tanks. Record hull UUID, drive connection, dry/wet mass, fuel and reverse thrust. The item task only proves possession. Carry ordinary fuel separately for home-system operations if desired; it adds mass and cannot propel beyond home. Coasting needs neither fuel nor drive, but braking does.',
    '手动安装记录，非自动检测：将自制引擎装在推进器与可抽取氘罐所属的同一组装船体，登记船体UUID、连接、干湿质量、燃料与反推。物品任务只证明持有。可另带母星系作业用普通燃料，但它增加质量且不能提供母星系外推力；惯性滑行不需引擎或燃料，制动需要。'))
expedition_plan = add_entry(8, 0x1000000000080D00, (
    'checkmark', '', 1, 'Plan a destination and return reserve', '登记远征目的地与返航储备',
    'Manual expedition plan, not route certification: select another star and an actual landing candidate in Atlas; record system/planet identifiers, current range and approach speed, home marker, hull UUID, cargo and each fuel tank. Budget outbound acceleration, target-relative braking, landing, return ascent and return braking sequentially with unspent reserve. Mark outside-home legs explicitly in the offline planner; it checks declared fuel/drive eligibility only. Pack food, light, tools, repair parts and delivered rescue fuel; no fixed bucket or travel-time promise.',
    '手动远征计划，非航线认证：在 Atlas 选择另一恒星和实际着陆候选，登记星系/星球标识、当前距离与接近速度、基地标记、船体UUID、货物和各燃料罐。按去程加速、目标相对制动、着陆、返程起飞及制动逐段预算，保留不动用储备。离线工具须显式标注母星系外航段，只核对声明的燃料/引擎资格。带食物、照明、工具、维修件并安排送达救援燃料，不承诺固定桶数或航时。'))
expedition_arrival = add_entry(8, 0x1000000000080E00, (
    'checkmark', '', 1, 'Land in another system', '在另一星系着陆',
    'Manual arrival record, not automatic destination or same-ship detection: confirm the selected world belongs to another system, slow against its relative motion and land safely. Compare departure and arrival hull UUID, cargo, fuel and damage. Do not count boundary crossing alone as arrival. Retain map observations and screenshots or server receipts; player landing advancements do not prove system identity or cargo provenance.',
    '手动抵达记录，非自动目的地或同船检测：确认所选世界属于另一星系，按相对运动减速并安全着陆。核对出发抵达船体UUID、货物、燃料及损伤；仅越过边界不算抵达。保留地图观察、截图或服务器回执，玩家着陆进度不证明星系身份或货物来源。'))
expedition_outpost = add_entry(8, 0x1000000000080F00, (
    'checkmark', '', 1, 'Supply and reopen an expedition outpost', '补给远征据点并重进',
    'Manual outpost record, not automatic construction detection: mark the landing site, establish a lit shelter and storage with food, tools, repair stock and compatible delivered fuel. Choose one useful local resource or construction objective and record a real shipment. Save, leave and rejoin; verify the same ship, cargo, tank amounts, markers and usable outpost. Stay parked when return fuel is insufficient. Other systems are not guaranteed unique ores or local deuterium production; do not assume a self-fuelling base.',
    '手动据点记录，非自动建造检测：标记着陆点，建立照明住所与仓库，储备食物、工具、维修件及送达的兼容燃料。选一种有用当地资源或建设目标并记录真实货运。保存退出再重进，核对同船、货物、液位、标记与据点可用性。返程油不足就停泊；其他星系不保证独占矿或当地氘生产，不默认据点能自行供油。'))
expedition_return = add_entry(8, 0x1000000000081000, (
    'checkmark', '', 1, 'Return and reopen the expedition route', '返航并恢复远征路线',
    'Manual expedition closure, not automatic transport certification: return to the marked home base in the recorded ship, reconcile cargo unloading, tank readings and repairs, and replenish the outpost shipment for another departure. Record replacement hulls or rescue/refuelling explicitly rather than claiming an uninterrupted same-ship voyage. Keep evidence for both directions. This closes the first expedition goal while further exploration remains open; no reactor, random structure loot or visit to every world is required.',
    '手动远征收尾，非自动运输认证：用登记船体返回标记基地，对齐卸货、液位和维修，再为下一次出发补充据点运输物资。换船、救援或补油须明确登记，不冒充无中断同船航程；保留双向证据。此项完成首次远征目标，之后仍可开放探索；不要求反应堆、随机结构战利品或遍访全部世界。'))

# The assembler/glue keep their former aviation IDs but now teach mandatory ship construction.
assembler, glue = chapters[3][2][5:7]
chapters[3][2].remove(assembler)
chapters[3][2].remove(glue)
# Author chapter order explicitly; dependency construction below never crosses the optional branches.
oil = {entry_ids[id(entry)]: entry for entry in chapters[4][2]}
def old_oil(index):
    return oil[0x1000000000040000 + index * 0x100]
chapters[4][2][:] = [old_oil(1), old_oil(2), extractor, pipes, extraction,
                     old_oil(3), old_oil(4), refill, assembler, glue,
                     *[old_oil(i) for i in range(5, 10)], oil[0x1000000000050800],
                     old_oil(12), preflight, old_oil(10), local_trial, trip_departure, old_oil(11)]
# Unload the raw cargo at home before refining; retain all legacy object IDs.
metals = {entry_ids[id(entry)]: entry for entry in chapters[5][2]}
chapters[5][2][:] = [metals[0x1000000000050100], metals[0x1000000000050200],
                     trip_loading, metals[0x1000000000050400], trip_delivery,
                     metals[0x1000000000050300],
                     *[metals[0x1000000000050000 + i * 0x100] for i in (5, 6, 7, 9, 10, 11)]]
chapters[5][2].append(hydrogen_run)
# Resource errands are independent choices. Precision optics and lightweight panels
# have their own component requirements rather than demanding every biome's cargo.
expansion = {entry_ids[id(entry)]: entry for entry in chapters[6][2]}
resource_indices = (1, 2, 3, 6, 7, 8, 9, 10)
chapters[6][2][:] = [aluminum, expansion[0x1000000000060400],
                     expansion[0x1000000000060500], upgrade_record, route_record,
                     *[expansion[0x1000000000060000 + i * 0x100] for i in resource_indices]]
# Rotor components precede the manual flight trial; envelopes are a separate free-build option.
air = {entry_ids[id(entry)]: entry for entry in chapters[3][2]}
chapters[3][2][:] = [air[0x1000000000030000 + i * 0x100] for i in (1, 2, 8, 3, 4, 9, 5)]
optional_ids = {entry_ids[id(entry)] for entry in chapters[3][2]} | {0x1000000000040200, 0x1000000000050500, entry_ids[id(crafters)]}
optional_ids.update(0x1000000000060000 + i * 0x100 for i in resource_indices)
# Generic reserve samples remain useful optional stock, not arbitrary late-game gates.
optional_ids.update(0x1000000000080000 + i * 0x100 for i in (1, 2, 3, 4))
dependencies = {}


def chain(entries, start=None):
    previous = start
    for entry in entries:
        identifier = entry_ids[id(entry)]
        assert identifier not in dependencies, f'Duplicate dependency definition: {identifier:016X}'
        dependencies[identifier] = [previous] if previous is not None else []
        previous = identifier
    return previous


chain([entry for _, _, entries in chapters[:3] for entry in entries if entry is not crafters])
chain([crafters], 0x1000000000020C00)
chain([entry for entry in chapters[3][2] if entry is not air[0x1000000000030500]],
      entry_ids[id(workshop)])
chain([air[0x1000000000030500]], 0x1000000000030100)
# Fuel production opens with the working workshop. Finish the separate ship-parts
# budget before preflight rather than requiring every ship part before prospecting.
orbit_end = chain([entry for entry in chapters[4][2] if entry_ids[id(entry)] not in optional_ids],
                  entry_ids[id(workshop)])
dependencies[entry_ids[id(preflight)]].append(entry_ids[id(parts_budget)])
chain([old_oil(2)], 0x1000000000040100)
chain([entry for _, _, entries in chapters[5:7] for entry in entries
       if entry_ids[id(entry)] not in optional_ids], orbit_end)
chain(chapters[7][2], entry_ids[id(route_record)])
gas = {entry_ids[id(entry)]: entry for entry in chapters[8][2]}
chain([gas[0x1000000000080000 + i * 0x100] for i in (9, 5, 6, 7, 11)],
      entry_ids[id(route_record)])
chain([drive, drive_installation, expedition_plan, gas[0x1000000000080800],
       expedition_arrival, expedition_outpost, expedition_return], entry_ids[id(gas_withdrawal)])
for i in (1, 2, 3, 4):
    chain([gas[0x1000000000080000 + i * 0x100]], entry_ids[id(route_record)])
chain([metals[0x1000000000050500]], 0x1000000000050300)
for i in resource_indices:
    chain([expansion[0x1000000000060000 + i * 0x100]], entry_ids[id(hydrogen_run)])
dependencies[entry_ids[id(drive)]].extend([entry_ids[id(flesh[2])], entry_ids[id(flesh[3])]])
# Display the gas route before the join and expedition, preserving every old ID.
chapters[8][2][:] = [gas[0x1000000000080000 + i * 0x100] for i in (1, 2, 3, 4, 9, 5, 6, 7, 11, 10, 12, 13, 8, 14, 15, 16)]


def validate_dependencies(graph, optional):
    """Resolve the graph independently of chapter/display order, rejecting optional core gates."""
    done, visiting = set(), set()
    def visit(identifier):
        assert identifier in graph, f'Missing dependency: {identifier:016X}'
        assert identifier not in visiting, f'Dependency cycle: {identifier:016X}'
        if identifier in done:
            return
        visiting.add(identifier)
        for parent in graph[identifier]:
            assert identifier in optional or parent not in optional, 'Core quest depends on optional branch'
            visit(parent)
        visiting.remove(identifier)
        done.add(identifier)
    for identifier in graph:
        visit(identifier)


validate_dependencies(dependencies, optional_ids)


def write(path, value, comment=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text((f'// {comment}\n' if comment else '') + json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def code(value):
    return f'{value:016X}'


def generate(root):
    langs = {'en_us': {'file.0000000000000001.title': tomllib.loads(Path('modpack/pack.toml').read_text())['name']},
             'zh_cn': {'file.0000000000000001.title': '飞行任务记录'}}
    write(root / 'data.snbt', {'version': 13, 'default_consume_items': False, 'fallback_locale': 'en_us', 'progression_mode': 'linear'})
    write(root / 'chapter_groups.snbt', {'chapter_groups': []})
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
            if dependencies[entry_base]:
                quest['dependencies'] = [code(parent) for parent in dependencies[entry_base]]
            if entry_base in optional_ids:
                quest['optional'] = True
            if entry_base == 0x1000000000040200:
                quest.update(x=10.0, y=0.0)
            quests.append(quest)
            for locale, title, description in [('en_us', ent, end), ('zh_cn', zht, zhd)]:
                langs[locale][f'quest.{quest_id}.title'] = title
                langs[locale][f'quest.{quest_id}.quest_desc'] = [description]
                if entry_base == entry_ids[id(preflight)]:
                    langs[locale][f'quest.{quest_id}.quest_desc'].extend(first_ship_reference[locale])
            count += 1
        # The distillation controller is part of the same processing milestone.
        if chapter_index == 4:
            q = next(q for q in quests if q['id'] == '1000000000040300')
            q['tasks'].append({'id': code(base + 0x303), 'type': 'item',
                               'item': {'id': 'createdieselgenerators:distillation_controller', 'count': 1}, 'count': 1})
        # FTB Quests saves chapters by ID; matching its filename prevents duplicate chapters after shutdown.
        legacy = root / f'chapters/{chapter_index:02d}.snbt'
        if legacy.exists():
            legacy.unlink()
        write(root / f'chapters/{chapter_id}.snbt',
              {'id': chapter_id, 'group': '', 'order_index': chapter_index, 'quests': quests},
              'All task counts and reward quantities are first guesses to tune in play.')
    for locale, table in langs.items():
        write(root / f'lang/{locale}.snbt', table)
    return count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--output', type=Path, help='Generate into this directory (explicitly replaces matching files).')
    mode.add_argument('--check', action='store_true', help='Generate in a temporary directory and compare tracked quests.')
    args = parser.parse_args()
    if args.check:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            generate(root)
            expected = Path('modpack/config/ftbquests/quests')
            generated = {p.relative_to(root): p.read_bytes() for p in root.rglob('*') if p.is_file()}
            tracked = {p.relative_to(expected): p.read_bytes() for p in expected.rglob('*') if p.is_file()}
            differences = sorted(str(p) for p in generated.keys() | tracked.keys() if generated.get(p) != tracked.get(p))
            assert not differences, f'Quest output differs: {differences}'
            print(f'PASS: {len(generated)} quest files reproduce byte-for-byte.')
    else:
        count = generate(args.output)
        print(f'Wrote {len(chapters)} chapters, {count} quests, and two FTB translation tables to {args.output}.')


if __name__ == '__main__':
    main()
