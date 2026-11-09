# 数据包：星球类型、宇宙布局与恒星系

[English](DATAPACKS.md)

数据包可以给星海折越增加新的星球类型，改变母星系之外的宇宙如何排布，也可以手写一整个恒星系：每颗太阳在哪里、
有哪些行星绕它运行、行星多大、如何自转。本页是这些文件的参考，适用于 Minecraft 1.21.1（NeoForge）。

数据包设定的是星球如何**生成**。它可引用 Java 注册的地形步骤和规则，但不能安装新的 Java 实现，也不会把新星球放进已有的世界：一个世界生成什么，在创建时
就随世界保存了（见[已保存的世界保留什么](#已保存的世界保留什么)）。更换世界使用的数据包之前请先备份。

## 气态巨行星实验分支（D1）

气态巨行星是没有地形生成器和着陆编号的目录天体。开发分支支持可选的 `suns[].gas_giants`；已有世界不会仅因
重新加载数据包就新增天体。体验预设仍**未启用**气态巨行星：最大下潜深度限制和有界实机验收尚未完成。

气压是包括已加载挂载运动结构范围在内的**整舰保守估计**，不依赖导航锁定。仪表显示服务器采样值，而非相机气压；
未知或过期样本不能当作安全零值。40 tick 接收保鲜预算会扣除服务器报告的样本年龄，但不能测量网络传输延迟。
达到 50% 气压后，已加载飞船可能通过普通服务端方块修改丢失合格结构方块。v1 有意保护方块实体/库存、复杂机械/
自定义子类、流体、不可破坏或非完整方块，以及挂载运动结构内部方块；只有精确匹配的普通方块、柱体和玻璃类可受损。
缺少同一 plot 内已加载的邻区块时会跳过移除，而不是暗中分配区块。成功移除后的间隔会跨舰船休眠及保存重载保留，
不引入 HP/累计暴露计数，也不模拟离线损坏。直接移除最多每舰每间隔一次、每服务器 tick 两次；原生分裂/悬浮后续
反应不属于总方块损失上限。此未完成特性只应用一次性世界/舰船副本测试，本段不代表实机或发布验收通过。

## 文件放在哪里

| 内容 | 包内路径 | 引用写法 |
|---|---|---|
| 星球类型 | `data/<命名空间>/starfold/planet_type/<名称>.json` | `demo:ash` |
| 宇宙布局 | `data/<命名空间>/starfold/universe/<名称>.json` | `demo:ash_universe` |
| 恒星系 | `data/<命名空间>/starfold/system/<名称>.json` | `handwritten:twin_lanterns` |
| 世界预设 | `data/<命名空间>/worldgen/world_preset/<名称>.json` | `demo:my_world` |
| 创建界面列出的世界类型 | `data/minecraft/tags/worldgen/world_preset/normal.json` | `{"replace": false, "values": ["demo:my_world"]}` |
| 星球上允许的结构集 | `data/starfold/tags/worldgen/structure_set/planets.json` | 用 `"replace": false` 加入自己的结构集 ID |

类型、布局和恒星系的路径里，命名空间后面那一段固定是 **`starfold`**；只有紧跟 `data/` 的那一段是你自己的命名空间。

定义了类型，并不会让它出现在任何世界里。必须有地方引用它：布局的 `types`、恒星系的 `suns[].planets[].type`，或世界
预设里的星球条目。同样，新布局也只有被预设或恒星系引用时才会用到。

## 星球类型

类型由 `shape`、`terrain`、`life` 和 `environment` 组成。全部 13 种类型使用同一个固定规划与逐点求值流程；类型差异只来自配方。旧 `algorithm`、标量 `relief`、`ocean`、旧气候字段、`life.biomes`、`life.regions`、`palette` 和 `minerals` 格式已删除，需要新世界。

`shape.size.min/max` 是每面宽度，416–2048、16 的倍数；`depth_ratio` 为 0–0.75，深度按 `round(size × 2 / π × depth_ratio)` 限在 `[191, floor(radius × .75)]`。`shell` 为 `bedrock` 或 `core_shell`。`life.biome` 为图表外回退群系；`creatures/features/structures` 分别控制生物、BiomeScatter 植被、固定结构，省略均为 true。干燥类型应显式关闭不需要的开关。

`environment` 提供大气、重力及温度偏移；省略时大气为类地，重力按尺寸求值。类型配方在物化时固定，规划保存完整配方和节点/要素；reload 只影响之后创建的星球。

### 地形配方（terrain，version=2）

所有下列字段必填；拒绝未知字段、未注册类型、越界参数和不支持的版本。参数单位为格、摄氏度或 0–1 比例。

| 字段 | 含义与范围 |
|---|---|
| `planner.stages` | 顺序执行的已注册步骤 ID；内置 plates、landforms、erosion、climate、biomes、volumes，命名空间均为 starfold |
| `land_fraction` | 0–1，大尺度大陆比例 |
| `relief` | mountains 0–175、hills 0–64、roughness 0–8；母星初稿 160/24/1 |
| `landforms[]` | type + parameters，最多 64 组；内置 crater、volcano、canyon、glacial_rift、hive、dunes、plateau |
| `volumes[]` | 最多64组，注册的overhang、arch、undercut、cave_mouth、sinkhole、floating_rock类型及参数 |
| `erosion` | rivers 布尔、strength 0–2、river_threshold 2–4096（汇流节点数） |
| `climate` | temperature -200–200、latitude_gradient 0–100、humidity -1–1、lapse_rate 0–0.1 |
| `biomes[]` | 注册的群系规则，最多 64 项，按顺序首个匹配；每个节点必须显式匹配一条规则 |
| `materials[]` | 注册的材质规则，最多 64 项，首个匹配；无匹配则用地层 |
| `strata[]` / `ores[]` | 地层最多 8，矿物最多 16；from/to 为 nominal 表面到底部的 0–1 比例，rarity .5–.999 |
| `caves` | width 0–.2、scale 4–128；全球三维噪声 |
| `water` | block 为液体方块或 air、sea_level -1024–300、freeze 布尔 |
| `preferences` | desolation、damage、burial、overlap、road_continuity、density，均0–1；候选概率、固定效果、空间安排、道路决策和地貌/体积密度 |

hive 另有 spacing（32–256）与 wall_slope（.1–4）；其几何邻居保存在规划内。群系扩展实现通过 `BiomeRule.biome(parameters)` 声明输出ID，无须使用内置参数名。地貌参数为 density（0–8）、radius_min/max（16–256）、amplitude（0–128）与 operation（raise/lower）。群系规则 `starfold:climate` 参数：biome、temperature_min/max、humidity_min/max、height_min/max、river、coast。群系规则 `starfold:terrain` 参数：biome、height_min/max（-1024–320）、slope_min/max（0–200，每物理格的高度变化）、landform_min/max（-2048–2048，全部地貌的有符号高度贡献），以及可选 region_min/max（0–1）。region 是192格低频球面噪声结合海拔、坡度、地貌贡献后的全球排名；阈值直接控制成片区域的面积。例如沙漠 scrub 使用0–.35，最后一条 barren 不限制。已发布区域配方的次要分区取25–35%。群系海拔使用实际可生成表面上限318；坡度和地貌贡献保留原规划几何。干类型可直接按坑底、坑缘、裂谷和火山坡分区，无需依赖湿度。前面的规则未覆盖所有表面时，需显式写入无限制的末条规则；没有规则匹配会令规划失败。材质规则 `starfold:surface` 参数：block、depth_min/max、temperature_min/max、slope_min、shore、biome（空字符串匹配全部）。

体积参数：density（0–8，乘preferences.density）、radius/height（2–64）、offset（-64–96）、operation（fill/carve）、slope_min/max（0–200）、cave_connection。悬挑和下凹必须找到形状内部的上坡锚点，并朝较低的地面伸出；放置回调生成并保存切向单位方向 wall_x/wall_z。浮岩是单独的配方类型，仅声明它的类型生成。洞口与天坑先搜索实际地下双噪声洞穴，再保存连接形状；只在有界范围内依序叠加fill/carve，再以该列已有water_level回填最终空气隙，保留FILL实体；不做与水面的全局连通性模拟。冰雪按最终暴露顶面处理，随后道路和结构覆盖。群系图和真实区块、表面查询、LOD使用同一最终列，空气隙不会被远景补实。

结构组合顺序保存在规划中，允许重叠；后站点覆盖前站点。bury真实下移结构，并把地下AIR回填为地面材质；回填可覆盖先前站点的同位置方块。damage保存确定的移除量，cut保存明确的类型化引用；每个站点的cut约束自己的地形适配，后站点再按顺序执行。原始模板和recipes保留，重放按最终列与最终owner恢复方块及块实体。关系引用使用kind+列表索引，结构使用稳定site.id；道路的两端村庄各有connects关系。

道路在共享网格上使用8邻域、按物理坡度代价求最短路，保存控制节点、整平高度及逐段GROUND/BRIDGE/CUT。统一球面三次曲线让拐弯切线连续；跨越检测、实际道路方块与离线叠图都读取该曲线。跨越检测沿段以至多1格、且至多最窄河宽一半的物理间距采样，读取河流、湖海、峡谷剖面与挖空体积；bridge使用木板桥面，cut段不铺路。road_continuity只在规划时决定桥或断路。选址读取最终有体积的表面与群系；`starfold:river_settlements`结构标签偏好河岸平地，`starfold:highland_ruins`偏好高处，脱离地面的浮岩面有支持惩罚。desolation、burial、damage、overlap、density分别控制候选概率、埋深概率、破损量、候选间距与地貌/体积密度。

扩展 API 为 `TerrainRegistries.LANDFORMS/VOLUMES/MATERIALS/BIOMES.register(id, codec, implementation)` 与 `registerStage(id, stage)`。内置实现用同一接口；地貌实现提供剖面与放置，规则提供匹配/材质与规划回调。求值按固定操作执行。体积实现提供规范盒[-1,1]内的形状、参数Codec和放置回调。求值使用确定的球面切向基与完整包围盒，支持接缝上的非圆形状。

粗表面采样（16/32/64格）固定读四个网格/要素摘要。每个PlanetTerrain首次构造SurfaceFunction时建立并共享摘要，重绑terrain/plan时失效；按每16格单元固定四点求积，再建立mip，并把体积与结构包围范围栅格化，保证离开中心的屋顶和岩体仍计入覆盖。一次性建立成本及内存与每次O(1)采样、规划压缩大小分别计量；粗覆盖是近似，细LOD和区块保留精确多段列。核心季节API通过近景和外景渲染上下文提供已同步的TerrainPlan。可选季节外观模块的第三方来源审查尚未完成，因此本次公开源码未包含该模块。

节点间距16，几何面边与角共享节点；height双三次插值、地貌、细节、河道后由现有结构适配合成。水系使用priority-flood湖泊与汇流图，植被仅来自BiomeScatter并乘湿度。完整母星配方示例：

```json
{
  "version": 2,
  "planner": {
    "stages": [
      "starfold:plates",
      "starfold:landforms",
      "starfold:erosion",
      "starfold:climate",
      "starfold:biomes",
      "starfold:volumes"
    ]
  },
  "land_fraction": 0.4,
  "relief": {
    "mountains": 160,
    "hills": 24,
    "roughness": 1
  },
  "landforms": [],
  "erosion": {
    "rivers": true,
    "strength": 1,
    "river_threshold": 48
  },
  "climate": {
    "temperature": 27,
    "latitude_gradient": 47,
    "humidity": 0,
    "lapse_rate": 0.0065
  },
  "biomes": [
    {
      "type": "starfold:climate",
      "parameters": {
        "biome": "minecraft:ocean",
        "temperature_min": -300,
        "temperature_max": 300,
        "humidity_min": 0,
        "humidity_max": 1,
        "height_min": -1024,
        "height_max": 127,
        "river": false,
        "coast": false
      }
    },
    {
      "type": "starfold:climate",
      "parameters": {
        "biome": "minecraft:desert",
        "temperature_min": 20,
        "temperature_max": 300,
        "humidity_min": 0,
        "humidity_max": 0.4,
        "height_min": -1024,
        "height_max": 320,
        "river": false,
        "coast": false
      }
    },
    {
      "type": "starfold:climate",
      "parameters": {
        "biome": "minecraft:forest",
        "temperature_min": -15,
        "temperature_max": 30,
        "humidity_min": 0.5,
        "humidity_max": 1,
        "height_min": -1024,
        "height_max": 320,
        "river": false,
        "coast": false
      }
    },
    {
      "type": "starfold:climate",
      "parameters": {
        "biome": "minecraft:plains",
        "temperature_min": -300,
        "temperature_max": 300,
        "humidity_min": 0,
        "humidity_max": 1,
        "height_min": -1024,
        "height_max": 320,
        "river": false,
        "coast": false
      }
    }
  ],
  "materials": [
    {
      "type": "starfold:surface",
      "parameters": {
        "block": "minecraft:gravel",
        "depth_min": 0,
        "depth_max": 3,
        "temperature_min": -300,
        "temperature_max": 300,
        "slope_min": 0,
        "shore": true,
        "biome": ""
      }
    },
    {
      "type": "starfold:surface",
      "parameters": {
        "block": "minecraft:stone",
        "depth_min": 0,
        "depth_max": 3,
        "temperature_min": -300,
        "temperature_max": 300,
        "slope_min": 3,
        "shore": false,
        "biome": ""
      }
    },
    {
      "type": "starfold:surface",
      "parameters": {
        "block": "minecraft:sand",
        "depth_min": 0,
        "depth_max": 3,
        "temperature_min": -300,
        "temperature_max": 300,
        "slope_min": 0,
        "shore": false,
        "biome": "minecraft:desert"
      }
    },
    {
      "type": "starfold:surface",
      "parameters": {
        "block": "minecraft:grass_block",
        "depth_min": 0,
        "depth_max": 0,
        "temperature_min": -300,
        "temperature_max": 300,
        "slope_min": 0,
        "shore": false,
        "biome": ""
      }
    },
    {
      "type": "starfold:surface",
      "parameters": {
        "block": "minecraft:dirt",
        "depth_min": 1,
        "depth_max": 3,
        "temperature_min": -300,
        "temperature_max": 300,
        "slope_min": 0,
        "shore": false,
        "biome": ""
      }
    }
  ],
  "strata": [
    {
      "block": "minecraft:stone"
    }
  ],
  "ores": [
    {
      "block": "minecraft:coal_ore",
      "from": 0,
      "to": 0.6,
      "rarity": 0.82
    },
    {
      "block": "minecraft:iron_ore",
      "from": 0.15,
      "to": 1,
      "rarity": 0.84
    },
    {
      "block": "minecraft:diamond_ore",
      "from": 0.8,
      "to": 1,
      "rarity": 0.94
    },
    {
      "block": "minecraft:copper_ore",
      "from": 0,
      "to": 0.65,
      "rarity": 0.82
    },
    {
      "block": "minecraft:redstone_ore",
      "from": 0.55,
      "to": 1,
      "rarity": 0.84
    }
  ],
  "caves": {
    "width": 0.085,
    "scale": 36
  },
  "water": {
    "block": "minecraft:water",
    "sea_level": 128,
    "freeze": false
  },
  "preferences": {
    "desolation": 0.3,
    "damage": 0.2,
    "burial": 0.1,
    "overlap": 0.2,
    "road_continuity": 0.8,
    "density": 1
  },
  "volumes": []
}
```

## 宇宙布局

布局描述的是预设或恒星系写明的星系**之外**的太阳和行星。

| 字段 | 类型与范围 | 默认 | 含义 |
|---|---|---|---|
| `stars` | 对象 | `{"min": 0, "max": 0}` | 有限宇宙里额外太阳的数量 |
| `stars.min` / `stars.max` | 整数，0 ≤ min ≤ max ≤ 64 | 提供 `stars` 时均必填 | 不包含母太阳 |
| `planets` | 对象 | 必填 | 每颗额外太阳有几颗行星 |
| `planets.min` / `planets.max` | 整数，1 ≤ min ≤ max ≤ 8 | 均必填 | 一颗太阳的行星要么一起出现，要么都不出现 |
| `star_gap` | 数字，见下方规则，最大 1000000 | `137900` | 网格间距；轴向相邻单元的太阳恰好相距此值，对角单元更远 |
| `ring` | 数字，至少 8192 | `12288` | 程序生成三颗及以上行星时的轨道间距；双行星共用一圈 |
| `types` | 至少一个对象的列表 | 必填 | 从中抽取的星球类型 |
| `types[].type` | 星球类型 ID，或就地写出的完整类型 | 必填 | |
| `types[].weight` | 整数，1–1000 | `1` | 被抽中的相对机会，不是数量 |
| `density` | 数字，0–1 | `0` | 大于 0 时宇宙在旅行中逐步发现，每个网格单元以此概率有一颗太阳；此时 `stars.max` 必须为 0 |

**距离规则。** `star_gap ≥ 2 × (17800 + (planets.max - 1) × ring) + 8192`。双行星、`ring` 为 12288 时至少为 68368。
手写恒星系使用自身母星的轨道半径代替 17800。此保守包络也容纳旧的分圈回退布局。太阳现放在网格中心，不再随机偏移。
程序生成双行星共用内圈及周期、相位固定相差 120°；三颗及以上仍分圈。数量、类型、地形种子及初始轨道相位仍由种子决定。

**两种模式。** `density` 为 0 时宇宙是有限的：所有额外太阳在创建世界时就定下来，从最近的网格单元开始。`density`
大于 0 时，创建世界时先检查母星系周围的八个单元，之后旅行者靠近哪里就检查哪里；母星系所在的单元不会再有额外的太阳。
两种模式下，相同的世界种子都得到相同的宇宙。

## 世界预设

世界预设是原版的格式：一个 `dimensions` 对象，其中每个维度有 `type` 和 `generator`。星球位于维度
`starfold:native_planet`，它的生成器类型是 `starfold:native_system`：

| 字段 | 类型与范围 | 默认 | 含义 |
|---|---|---|---|
| `storage_layout` | 字符串 | 必填 | 填 `six_face_square_v2` |
| `system` | 恒星系 ID，或就地写出的完整恒星系 | 无 | 要使用的手写恒星系。不能与 `planets`、`stars`、`universe` 同时出现；见[恒星系](#恒星系) |
| `planets` | 2–810000 个星球条目的列表 | 无 | 母星系的行星，按存储顺序。第一颗必须完整写出，不能只写类型 |
| `stars` | 正整数列表，总和等于 `planets` 的数量 | 一颗太阳带全部行星 | `planets` 如何分给各太阳；每颗太阳至少一颗 |
| `universe` | 布局 ID，或就地写出的完整布局 | 没有更远的宇宙 | `planets` 之外有什么。仅能与 `planets` 一起用 |

另外还有三个字段，不是给数据包作者用的：`a` 和 `b` 是恰好写两颗星球的旧写法，`celestial` 是游戏保存世界时写出的内容。

使用 `planets` 时，太阳排成一行、相距 137900 格，轨道相距 12288 格（母系双星共用半径 17800 的一圈、相差 120°），
这限制了一颗太阳能带多少行星。想自己安排太阳和
轨道，请改写恒星系。

**按类型写的星球条目**（除第一颗以外都可以这样写）：

| 字段 | 类型与范围 | 默认 | 含义 |
|---|---|---|---|
| `planet_type` | 类型 ID，或就地写出的完整类型 | 必填 | |
| `terrain_seed` | 64 位整数 | 必填 | 这颗星球自己的种子；不取自世界种子 |
| `size` | 类型范围内的整数，16 的倍数 | 由种子选定 | 这颗星球的面宽 |

**完整写出的星球条目**（第一颗必须这样写）：

完整生成器采用 `planet_format: 3`、`terrain_version: 14`。`planet_generation` 必须包含 type、surface_version=10、recipe（上述完整 terrain）、size、depth、shell、creatures、features、structures 和 environment。`planet_biomes` 为 terrain_seed 与 palette（配方涉及的群系ID列表）。旧格式一律拒绝。网络协议18，Profile为 `starfold:native_planet_v17_profile`；FORMAT=4的PlanetPlan同时保存地形和结构，并复用有界分包与摘要缓存。每颗星球即使关闭structures也持久化/同步地形规划。TerrainPlan FORMAT=3 使用定点场：高度、温度、水面和地层位移为 1/8 单位，粗糙度为 1/1024，湿度为 1/16384；规划内也采用相同量化，保存前后求值一致。旧格式拒绝，需要新世界。

## 内置类型与预设

所有 ID 都以 `starfold:` 开头。

| 类型 | 面宽 | 陆地比例 | 山脉/丘陵 | 地貌 | 水体 |
|---|---:|---:|---:|---|---|
| `rocky` | 512–1536 | 1 | 50/12 | crater | air |
| `desert` | 512–2048 | 1 | 50/12 | canyon, dunes | air |
| `ice` | 448–1024 | 1 | 50/12 | glacial_rift | water |
| `temperate` | 1024–2048 | 0.4 | 160/24 | — | water |
| `lush` | 1024–2048 | 0.6 | 24/12 | — | water |
| `arid` | 1024–2048 | 0.4 | 24/12 | — | water |
| `archipelago` | 1024–2048 | 0.12 | 24/12 | volcano | water |
| `steppe` | 1024–2048 | 0.4 | 24/12 | — | water |

体验包提供12种类型（home、archipelago、lush、desert、ice、rocky、asteroid、basalt、crystal、end、mushroom、warden）；biotech 的 `starfold_biotech:flesh` 是第13种，使用血液和球面Voronoi巢穴格壁。类型各自显式声明大气；配方和已解析模板随星球固定。

布局 `starfold:default` 按上表顺序以权重 3、2、2、2、1、1、1、1 抽取这些类型，`density` 1，`star_gap` 137900，
`ring` 12288，每颗太阳一到两颗行星。`starfold:small` 使用相同的类型和权重，是一个有二到四颗额外太阳的有限宇宙，显式间距仍为 98304。

### 体验包航行尺度

两个体验预设现在都选择已有手写恒星系格式 `starfold_worlds:experience`，无须扩展 codec。四颗太阳组成边长约 137900 格的四面体，
每系三颗行星共用半径 17800、周期 24000 tick 的轨道，相差 120°。保留存储顺序、类型、尺寸和地形种子，分组为：
炉心/苍翠/寂痕；砧星/霜原/赭沙；群澜/菌港/砾星；烬岩/晶棱/象牙。正式预设仍有出生船。
炉心由匿名完整生成器改为引用 `starfold_worlds:home` 类型。同系任意两行星的直线在 epoch 0 以及后续相位都不会穿日。

按未武装 Hauler 的 P1 加速加巡航估算，同系约 60.1 秒；体验包跨系约 188–292 秒，加力约减半。
默认轴向相邻系的行星中心航线包络为 102300–173500 格，约 180.1–299.7 秒。不是实测起飞/抵达/着陆时间；对角和跨多格航线更长。
自定义稀疏布局仍可能因空单元而使最近实际恒星更远。未改变船速。仍选择最近 24 颗太阳，太空绘制远面为 1000000 格，足够覆盖新航线。
旧非手写存档重建轨道及之后发现的新单元会受新布局影响，建议新建世界；已保存手写 celestial 保持其写明的几何。

世界预设 `starfold:native_universe`（“星海折越：随机宇宙”）把两颗母星完整写出：A 是面宽 2048、深度 652 的生态星球，B 是
面宽 1024、深度 326 的岩石星球。在它们之外使用 `starfold:default`。

## 示例 1：在随机宇宙里加入一种新星球类型

下面四个文件就是整个包。它新增类型 `demo:ash` 和布局 `demo:ash_universe`，并**替换**预设 `starfold:native_universe`：
A 星保持不变，B 星变成灰岩星球，之后每颗额外的太阳都带一颗灰岩星球。预设不能追加，这个文件是整个顶替模组里的那份。

在**创建新世界**时的数据包界面里启用这个包，并选择世界类型“星海折越：随机宇宙”。被替换的预设本来就列在该界面里，
所以这个包不需要标签。包的根目录直接放 `pack.mcmeta` 和 `data`；打成 zip 时，不要在外面再套一层文件夹。

### `pack.mcmeta`

```json
{
  "pack": {
    "pack_format": 48,
    "description": "Starfold: an ash planet type (Minecraft 1.21.1)"
  }
}
```

### `data/demo/starfold/planet_type/ash.json`

```json
{
  "shape": {
    "size": {
      "min": 512,
      "max": 1024
    }
  },
  "terrain": {
    "version": 2,
    "planner": {
      "stages": [
        "starfold:plates",
        "starfold:landforms",
        "starfold:erosion",
        "starfold:climate",
        "starfold:biomes",
        "starfold:volumes"
      ]
    },
    "land_fraction": 1,
    "relief": {
      "mountains": 50,
      "hills": 12,
      "roughness": 1
    },
    "landforms": [
      {
        "type": "starfold:crater",
        "parameters": {
          "density": 0.25,
          "radius_min": 24,
          "radius_max": 128,
          "amplitude": 24,
          "operation": "raise"
        }
      }
    ],
    "erosion": {
      "rivers": false,
      "strength": 1,
      "river_threshold": 48
    },
    "climate": {
      "temperature": 27,
      "latitude_gradient": 47,
      "humidity": -0.8,
      "lapse_rate": 0.0065
    },
    "biomes": [
      {
        "type": "starfold:climate",
        "parameters": {
          "biome": "minecraft:stony_peaks",
          "temperature_min": -300,
          "temperature_max": 300,
          "humidity_min": 0,
          "humidity_max": 1,
          "height_min": -1024,
          "height_max": 320,
          "river": false,
          "coast": false
        }
      }
    ],
    "materials": [
      {
        "type": "starfold:surface",
        "parameters": {
          "block": "minecraft:tuff",
          "depth_min": 0,
          "depth_max": 3,
          "temperature_min": -300,
          "temperature_max": 300,
          "slope_min": 0,
          "shore": false,
          "biome": ""
        }
      }
    ],
    "strata": [
      {
        "block": "minecraft:deepslate"
      }
    ],
    "ores": [],
    "caves": {
      "width": 0.085,
      "scale": 36
    },
    "water": {
      "block": "minecraft:air",
      "sea_level": 128,
      "freeze": false
    },
    "preferences": {
      "desolation": 0.3,
      "damage": 0.2,
      "burial": 0.1,
      "overlap": 0.2,
      "road_continuity": 0.8,
      "density": 1
    },
    "volumes": []
  },
  "life": {
    "biome": "minecraft:stony_peaks"
  },
  "environment": {}
}
```

### `data/demo/starfold/universe/ash_universe.json`

```json
{
  "density": 1.0,
  "planets": {
    "min": 1,
    "max": 1
  },
  "star_gap": 49152,
  "ring": 12288,
  "types": [
    {
      "type": "demo:ash",
      "weight": 1
    }
  ]
}
```

### `data/starfold/worldgen/world_preset/native_universe.json`

[完整当前格式预设](../src/main/resources/data/starfold/worldgen/world_preset/native_universe.json)

## 恒星系

恒星系文件安排太阳的位置，并给每颗行星各自的圆轨道和自转。世界预设的生成器用 `"system": "<命名空间>:<名称>"` 引用它，
同时写 `"storage_layout": "six_face_square_v2"`，此时不能再有 `planets`、`stars` 或 `universe`。星系之外的宇宙写在星系
文件里，字段是 `beyond`。

### 字段

位置是**宇宙坐标**，单位是格。它不是调试界面上显示的方块坐标，也不是相对第一颗太阳的偏移。

| 字段 | 类型与范围 | 省略时 |
|---|---|---|
| `suns` | 至少一颗太阳的列表；行星总数 1–1600 | 必填 |
| `suns[].position` | 三个数字 `[x, y, z]`，单位格；最远能放到哪里见规则 5 | 必填 |
| `suns[].radius` | 数字，32–16384 格 | 必填 |
| `suns[].planets` | 至少一颗行星的列表 | 必填 |
| `suns[].planets[].type` | 星球类型 ID，或就地写出的完整类型 | 必填；未知 ID 会让世界无法加载 |
| `…size` | 面宽（格）：在类型范围内，16 的倍数 | 由类型和星球种子选定 |
| `…seed` | 64 位整数 | 由世界种子和这颗行星在文件里的位置推导 |
| `…reach` | 数字，地表上方 512–2048 格；飞船离开星球进入太空的高度 | `2048` |
| `…name` | 1–64 个字符，不能全是空白，不能有控制字符 | 按存储顺序为 A、B、… Z、P26、P27 … |
| `…orbit` | 对象 | 必填 |
| `…orbit.radius` | 数字，1–10000000 格，并满足下面的规则 | 必填 |
| `…orbit.phase` | 数字，弧度 | `0` |
| `…spin` | 对象 | **星球不自转** |
| `…spin.period` | 数字，24000–1000000000 tick | 提供 `spin` 时必填 |
| `…spin.tilt` | 数字，-180–180 **度** | `0` |
| `…moons` | 卫星列表，字段与行星相同，但不能有 `moons` | 没有卫星 |
| `home` | 对象，从 0 开始计数 | `{"sun": 0, "planet": 0}` |
| `home.sun` / `home.planet` | 文件里的某颗太阳和它的某颗行星 | 各为 `0` |
| `beyond` | 布局 ID，或就地写出的完整布局 | **星系之外什么都没有**：只存在这里写下的行星 |

**轨道**是母天体所在水平面上的圆：周期按开普勒定律计算，单位 tick：`20 × 2π × sqrt(radius³ / 母天体 GM)`。
在第 `t` 个 tick，角度为 `phase + 2π × t / period`，天体位于`母天体位置 + [radius × sin(角度), 0, radius × cos(角度)]`。
不支持偏心轨道和倾斜轨道。**自转**绕轴
`[sin(tilt), cos(tilt), 0]` 进行，在第 0 个 tick 处于未转动的姿态。使用手写恒星系的新世界，时钟从第 0 个 tick 开始。

**种子。** 没有写 `seed` 的行星，种子由世界种子和它在文件里的序号得出；序号按太阳的顺序、再按每颗太阳的行星顺序
依次计数。因此换一个世界种子只会改变没有写 `seed` 的行星，而改 `home` 不会改变任何种子。种子随世界保存。

### 规则

1. **一颗行星就够。** 母星可以是任意太阳的任意行星。它与其他行星遵守相同的轨道和间距规则。
2. **存储顺序是固定的。** 母星是 0 号；然后是母太阳的其余行星，按书写顺序；最后是其他太阳和它们的行星，按书写顺序。
   默认名字按这些编号生成。卫星排在所有行星之后，按太阳、行星、卫星列表的书写顺序；添加卫星不会改变行星的存储编号。
3. **行星之间保持距离。** 取 `r = size × 2 / π`（省略 `size` 时按类型的最大面宽计算），同一颗太阳的两颗行星，球心
   距离在任何时刻都不能小于 `r1 + r2 + reach1 + reach2 + 256`（默认 reach 时为 `r1 + r2 + 4352`）。
   周期根据轨道半径和母天体 GM 按开普勒定律计算；同一轨道半径的行星周期相同，距离恒为
   `sqrt((R1 - R2)² + 4 × R1 × R2 × sin²(Δphase / 2))`。
4. **太阳之间保持距离。** 轨道半径必须大于 `太阳半径 + r + reach + 128`（默认 reach 时为 `太阳半径 + r + 2176`）。
   一颗太阳的势力范围是它的行星中最大的 `轨道半径 + r + reach + 128`；两颗太阳的距离不能小于两者势力范围之和，
   无论相位如何。
5. **坐标保持有界。** 太阳的位置及其轨道包络距原点不能超过 1000000000 格。
6. **最多 1600 颗行星**，包括 `beyond` 增加的。`beyond` 生成的太阳会避开每颗手写太阳的势力范围，放不下时就少生成。
   使用 `beyond` 时，它可能生成的最外圈轨道周期不能超过 1000000000 tick，按开普勒公式和母天体 GM 计算。
   最内圈还必须避开生成的太阳和最大面宽、默认 reach 的地表：
   `母星轨道半径 > 640 + 2048 × 2 / π + 2048 + 128`。`star_gap` 必须至少为
   `2 × (母星轨道半径 + (beyond.planets.max - 1) × beyond.ring) + 8192`。
7. 面宽必须满足类型的范围和深度规则，维度类型也必须装得下这个深度。

### 卫星

卫星也是一颗有地表的星球，拥有自己的类型、大小、种子、名字、轨道、自转和 reach。它的轨道半径从母行星的球心算起，
不是从太阳算起。轨道平面使用区域的坐标轴，不跟随母行星自转。卫星不能再有卫星，`home` 只能选择行星，不能选择卫星。
自动生成和探索发现的星系不会生成卫星。

卫星轨道半径必须至少为 `行星 r + 行星 reach + 卫星 r + 卫星 reach + 256`。同一行星的两颗卫星使用各自的半径、周期和
相位遵守规则 3。计算与太阳的间距、行星之间的间距和势力范围时，把行星的 `r + reach` 换成它自身的 `r + reach` 与每颗
卫星的 `轨道半径 + 卫星 r + 卫星 reach` 中的最大值。整个家族在轨道运行中都必须放得下。1600 的数量限制包括卫星。
探索发现的星系也会避开整个家族的势力范围。

没有写种子的卫星，在所有行星之后按卫星书写顺序继续使用相同的种子推导。添加卫星不改变任何现有行星的种子。
卫星与其他有地表的星球一样保存和生成。例如：

```json
{
  "suns": [
    {
      "position": [
        0,
        0,
        0
      ],
      "radius": 512,
      "planets": [
        {
          "type": "starfold:temperate",
          "size": 1024,
          "reach": 512,
          "orbit": {
            "radius": 30000
          },
          "moons": [
            {
              "type": "starfold:rocky",
              "size": 512,
              "reach": 512,
              "name": "Moon",
              "orbit": {
                "radius": 5000,
                "phase": 3.141592653589793
              }
            }
          ]
        }
      ]
    }
  ]
}
```

文件违反规则时，世界无法创建，报错信息会指出是哪个字段、或哪颗太阳的哪颗行星。此前不会生成任何地形，游戏也不会
退回到别的星系。

## 示例 2：两颗太阳、五颗行星

这个包在仓库的 `tools/datapacks/example_system/`。它的五个文件如下。它使用自己的命名空间，不替换模组的任何内容，并
通过自己的标签把世界类型列在创建界面上。

#### `pack.mcmeta`

```json
{
  "pack": {
    "pack_format": 48,
    "description": "Starfold: two hand-written suns and five planets"
  }
}
```

#### `data/handwritten/starfold/planet_type/ash.json`

```json
{
  "shape": {
    "size": {
      "min": 512,
      "max": 1024
    }
  },
  "terrain": {
    "version": 2,
    "planner": {
      "stages": [
        "starfold:plates",
        "starfold:landforms",
        "starfold:erosion",
        "starfold:climate",
        "starfold:biomes",
        "starfold:volumes"
      ]
    },
    "land_fraction": 1,
    "relief": {
      "mountains": 50,
      "hills": 12,
      "roughness": 1
    },
    "landforms": [
      {
        "type": "starfold:crater",
        "parameters": {
          "density": 0.25,
          "radius_min": 24,
          "radius_max": 128,
          "amplitude": 24,
          "operation": "raise"
        }
      }
    ],
    "erosion": {
      "rivers": false,
      "strength": 1,
      "river_threshold": 48
    },
    "climate": {
      "temperature": 27,
      "latitude_gradient": 47,
      "humidity": -0.8,
      "lapse_rate": 0.0065
    },
    "biomes": [
      {
        "type": "starfold:climate",
        "parameters": {
          "biome": "minecraft:stony_peaks",
          "temperature_min": -300,
          "temperature_max": 300,
          "humidity_min": 0,
          "humidity_max": 1,
          "height_min": -1024,
          "height_max": 320,
          "river": false,
          "coast": false
        }
      }
    ],
    "materials": [
      {
        "type": "starfold:surface",
        "parameters": {
          "block": "minecraft:tuff",
          "depth_min": 0,
          "depth_max": 3,
          "temperature_min": -300,
          "temperature_max": 300,
          "slope_min": 0,
          "shore": false,
          "biome": ""
        }
      }
    ],
    "strata": [
      {
        "block": "minecraft:tuff"
      },
      {
        "block": "minecraft:deepslate",
        "from": 0.65
      }
    ],
    "ores": [],
    "caves": {
      "width": 0.085,
      "scale": 36
    },
    "water": {
      "block": "minecraft:air",
      "sea_level": 128,
      "freeze": false
    },
    "preferences": {
      "desolation": 0.3,
      "damage": 0.2,
      "burial": 0.1,
      "overlap": 0.2,
      "road_continuity": 0.8,
      "density": 1
    },
    "volumes": []
  },
  "life": {
    "biome": "minecraft:stony_peaks"
  },
  "environment": {}
}
```

#### `data/handwritten/starfold/system/twin_lanterns.json`

```json
{
  "suns": [
    {
      "position": [
        0,
        0,
        0
      ],
      "radius": 512,
      "planets": [
        {
          "type": "starfold:temperate",
          "size": 2048,
          "seed": 20261001,
          "name": "Aster",
          "orbit": {
            "radius": 8192,
            "phase": 0
          },
          "spin": {
            "period": 48000,
            "tilt": 23.5
          }
        },
        {
          "type": "handwritten:ash",
          "size": 768,
          "name": "Cinder",
          "orbit": {
            "radius": 8192,
            "phase": 3.141592653589793
          }
        },
        {
          "type": "starfold:ice",
          "name": "Rime",
          "orbit": {
            "radius": 17000,
            "phase": 1.2
          },
          "spin": {
            "period": 72000,
            "tilt": -12
          }
        }
      ]
    },
    {
      "position": [
        60000,
        4000,
        -20000
      ],
      "radius": 420,
      "planets": [
        {
          "type": "handwritten:ash",
          "size": 512,
          "orbit": {
            "radius": 9000,
            "phase": 0.7
          }
        },
        {
          "type": "starfold:desert",
          "size": 1024,
          "name": "Dune",
          "orbit": {
            "radius": 18000,
            "phase": 2.4
          }
        }
      ]
    }
  ],
  "home": {
    "sun": 0,
    "planet": 0
  }
}
```

#### `data/handwritten/worldgen/world_preset/twin_lanterns.json`

```json
{
  "dimensions": {
    "minecraft:overworld": {
      "type": "minecraft:overworld",
      "generator": {
        "type": "minecraft:noise",
        "settings": "minecraft:overworld",
        "biome_source": {
          "type": "minecraft:multi_noise",
          "preset": "minecraft:overworld"
        }
      }
    },
    "minecraft:the_nether": {
      "type": "minecraft:the_nether",
      "generator": {
        "type": "minecraft:noise",
        "settings": "minecraft:nether",
        "biome_source": {
          "type": "minecraft:multi_noise",
          "preset": "minecraft:nether"
        }
      }
    },
    "minecraft:the_end": {
      "type": "minecraft:the_end",
      "generator": {
        "type": "minecraft:noise",
        "settings": "minecraft:end",
        "biome_source": {
          "type": "minecraft:the_end"
        }
      }
    },
    "starfold:native_planet": {
      "type": "starfold:native_deep",
      "generator": {
        "type": "starfold:native_system",
        "storage_layout": "six_face_square_v2",
        "system": "handwritten:twin_lanterns"
      }
    }
  }
}
```

#### `data/minecraft/tags/worldgen/world_preset/normal.json`

```json
{
  "replace": false,
  "values": [
    "handwritten:twin_lanterns"
  ]
}
```

### 在新世界里使用它

- **单人游戏。** 创建新世界，打开数据包界面，加入 `example_system` 文件夹（或根目录直接是 `pack.mcmeta` 和 `data` 的
  zip）并启用。选择这个包增加的世界类型；没有自带语言文件时，它显示为 `generator.handwritten.twin_lanterns`。进入后
  到达 Aster。
- **专用服务器。** 停服后，把包放进一个**还没有 `level.dat`** 的世界目录的 `datapacks/example_system/`，并在
  `server.properties` 里设置 `level-type=handwritten:twin_lanterns` 和 `level-seed`。包会自动启用。不要通过修改已有
  世界的 `level-type` 来迁移它。
- **星系之外的宇宙。** 在星系文件顶层加上 `"beyond": "starfold:default"`。示例没有写它，所以正好只有两颗太阳和五颗
  行星。

## 已保存的世界保留什么

世界保存的是创建时得到的那些星球，而不是整个数据包。

1. **星球被完整保存。** 按类型写的星球在创建世界时变成具体数值并保存下来。之后再改类型文件，不会改变这颗星球。
2. **尚未发现的部分，其规则也被保存。** 布局及其每个类型都随世界保存。改布局文件不会改变已有世界将来发现什么。
3. **手写恒星系同样如此**：太阳、轨道、自转、名字和母星。修改或删除星系文件，不会移动已有世界里的任何东西。
4. **已经发现的太阳和行星保持不变**，包括编号和位置；世界不会给它们重新编号。
5. **方块、群系和结构仍然按 ID 查找。** 如果删掉的包定义了世界用到的群系、方块或维度类型，世界可能无法加载。
   群系地物提供地表散布策略；结构集标签与群系适用性影响尚未建立结构规划的星球，每颗星球保留首次保存的规划。
6. **已经生成的区块不会被重写。**
7. **预设只在创建世界时起作用。** 它不是更新已有世界的办法。

## 代码里固定的限制

- **地形。** 噪声、洞穴和生态由代码决定，树木遵循受支持的群系模板；13类共用配方与逐点求值。海平面与液体来自water配方，允许水、熔岩、血或air。
- **结构读取固定规划。** 候选沿用 `life.structures`、`starfold:planets` 结构集标签和结构自身的群系限制。
  标签内 11 集均参与：村庄／前哨、程序神殿／小屋、完整雪屋／废墟、含地下变体的传送门、沉船、宝藏和矿井。
  原版 jigsaw 读取有体积的最终地形表面拼装，保存所有部件及 NBT 配方；按部件 bbox 和 `terrain_adaptation` 适配地形，取代圆形 pad。
  刚性部件下填上挖到地基，外围 12 格平滑衔接；`terrain_matching` 道路贴 L1；`none` 不改变高度。结构允许跨面接缝，
  避开角异常区，占地排除植被。AIR 能挖出内腔并覆盖洞穴／矿物；碰星核外壳的候选整座拒绝。不支持的 placement、
  群系不符及录制预算拒绝均记录原因。规划首次需要时在后台生成，每颗星球独立保存于 `data/planet-plans/`。
  可见状态随 Profile 同步，大计划另发每块最多 256 KiB 的分包，校验总长及 SHA-256，保留 1,000,000 B 单包上限，
  全部到齐后才发布。原版模板、战利品／方块实体配方只留在服务端；存档发布前逐部件重录核对可见结果，
  替换已引用的模板会拒绝规划。结构实体配方（女巫／猫、溺尸、箱子矿车等）确定性录制，在其所属区块重放；
  普通箱子、刷怪笼、考古方块的方块实体保留。数据包给非 pool 部件改成非默认适配时，目前明确拒绝并报告需要 L1 动态高度定位；
  原版非 pool 结构均为 `none`，不受此限。地表组合版本为 9，需要新世界。
- **随机生成的宇宙没有逐个天体的字段。** 在布局里，太阳半径在 384–640 之间抽取，位置在所属网格单元内抖动；行星沿用
  母星的轨道平面，周期随轨道半径增长。想自己设定这些，请写恒星系。
- **一个世界里最多能发现 1600 颗行星**，包含母星系。当一颗太阳的行星放不下时，这颗太阳不会被加入，游戏也不会给出
  提示。

## 在游戏里试过什么

本页的全部内容都由模组的测试检查，测试会从本页和 `tools/datapacks/` 读取这两个示例包。游戏里的情况是：

- **试过，在专用服务器上：** 用示例 2 创建新世界。玩家到达 Aster；两颗太阳和五颗行星都在文件指定的位置；每颗行星都
  着陆、挖掘并建造过。删掉包之后世界照常加载，所有行星不变；修改星系文件只影响新世界。未知类型、轨道太近、母星缺少
  伴星、`system` 与 `planets` 混用，都会让服务器无法启动。
- **没有试过：** 从单人创建界面创建任一示例的世界；母星不是第一颗行星的情况；不用命令从一颗太阳飞到另一颗；在游玩中
  发现 `beyond` 的宇宙；比内置维度类型更深的星球；其他模组的大型结构；用 `/reload` 添加、修改或移除第二个包。
