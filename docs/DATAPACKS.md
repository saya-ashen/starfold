# 数据包：手写星系、星球类型与宇宙配置

本文描述当前 Minecraft 1.21.1 / NeoForge 版本的 Native 数据包接口，不是旧双维度实验的使用说明。数据包可以新增**生成配置**，不能新增 Java 地形算法，也不会自动把一颗星球塞进已有世界。请先备份；独立第二数据包的加载、修改、移除及重载生命周期**未实测**。下文依据 codec、构造校验与离线测试，不把它们当作游戏验收。

## 文件位置与引用关系

`PlanetChunkGenerator.initialize` 注册生成器；`PlanetType.initialize` 随后注册 planet_type、universe、system 三个 NeoForge 数据包注册表：

| 内容 | 包内路径 | 引用示例 |
|---|---|---|
| 星球类型 | `data/<命名空间>/starfold/planet_type/<名称>.json` | `demo:ash` |
| 宇宙布局 | `data/<命名空间>/starfold/universe/<名称>.json` | `demo:ash_universe` |
| 手写星系 | `data/<命名空间>/starfold/system/<名称>.json` | `handwritten:twin_lanterns` |
| 世界预设 | `data/<命名空间>/worldgen/world_preset/<名称>.json` | `demo:my_world` |
| 普通世界类型列表 | `data/minecraft/tags/worldgen/world_preset/normal.json` | `{"replace":false,"values":["demo:my_world"]}` |
| 星球可用结构集标签 | `data/starfold/tags/worldgen/structure_set/planets.json` | 用 `replace:false` 添加自己的结构集 ID |

类型、布局与星系的目录中间都是 **`starfold`**，不是作者命名空间。命名空间只替换 `data/` 后的一段。注册了类型还不够：布局的 `types`、旧生成器的 `planets` 或手写星系的 `suns[].planets[].type` 必须引用它。没有“把目录里的每个类型自动加入宇宙”的规则，也没有单独的可合并 planet 列表注册表。新增布局同样不会自动替换预设选择的布局。依据：`PlanetType`、`UniverseLayout`、`SystemChunkGenerator`。

## 星球类型字段

以下范围均含端点；“必填”表示没有默认值。整数、浮点数均为 JSON 数字，布尔为 `true/false`。biome 使用注册表 ID；codec 是 `Biome.CODEC`，但生态四角色还有更严格的 ID 校验，见下表。依据：`PlanetType.CODEC` 及构造器、`PlanetGeneration`。

| 字段 | 类型、范围 | 省略时 | 作用与额外限制 |
|---|---|---|---|
| `biome` | biome holder（通常为 ID 字符串） | 必填 | 基础 biome；岩石星球使用此 biome，生态星球另用四角色分区 |
| `size` | 对象 | 必填 | 六个面的边长范围，不是直径或半径 |
| `size.min` / `size.max` | 整数，16 的倍数，min ≤ max | 均必填 | `PlanetShape` 接受 256–2048；类型生成深度校验进一步要求 **416–2048**。256–400 虽能构造 shape，不能满足最少 191 格深度 |
| `depth_ratio` | 有限浮点数，0–0.75 | `0.5` | 半径的深度比例，实际深度还会取整并夹限，见下文 |
| `algorithm` | 字符串，`rocky` / `rocky_craters` / `ecology` | `rocky_craters` | 两种岩石算法或生态算法；这不是可自由扩展的算法注册表 |
| `relief` | 有限浮点数，0.25–2 | `1` | 岩石起伏系数；`ecology` 必须等于 1 |
| `ore_bias` | 有限浮点数，-0.1–0.1 | 岩石 `0.035`；生态 `0` | 岩石矿脉阈值减量，越大越丰富；生态必须为 0 |
| `cave_width` | 有限浮点数，0.03–0.2 | 岩石 `0.085`；生态 `0.115` | 洞穴噪声交集宽度；生态必须为 0.115 |
| `shell` | 字符串，`bedrock` / `core_shell` | `core_shell` | 最底一层用基岩或模组星核壳，不是填满整个星核 |
| `palette` | 对象，见下表 | `PlanetPalette.ROCK` | 只有 `rocky_craters` 能使用不同于默认的材料表 |
| `biomes` | 四角色对象 | 不提供 | **仅且必须在 `ecology` 中提供** |
| `biomes.forest` | biome holder | 必填（提供 biomes 时） | 必须是 `minecraft:forest` |
| `biomes.plains` | biome holder | 同上 | 必须是 `minecraft:plains` |
| `biomes.desert` | biome holder | 同上 | 必须是 `minecraft:desert` |
| `biomes.ocean` | biome holder | 同上 | 必须是 `minecraft:ocean` |
| `minerals` | 对象，见下表 | 两个空列表 | `rocky_craters` / `ecology` 可定制；`rocky` 只能使用空表 |
| `climate` | 对象，见下表 | `PlanetClimate.MILD` | 非默认气候仅供 `ecology` 使用 |
| `ocean` | 浮点数，0–0.95 | 不指定比例 | 仅供 `ecology`，且 `climate.land` 必须为 0；按种子采样换算并保存抬升量，不是逐格保证的精确海洋占比 |
| `creatures` | 布尔 | 生态 true；岩石 false | 控制 biome 生物列表查询及区块初始动物生成，不是自定义生物列表 |
| `features` | 布尔 | 生态 true；岩石 false | 控制 biome 地物装饰，不关闭本模组自身地形、矿洞或生态树木 |
| `structures` | 布尔 | 生态 true；岩石 false | 控制星球上的结构起点生成与结构块放置，仍受结构集标签、biome 等约束 |

**生态角色不能换成任意自定义 biome。** `PlanetBiomes` 构造器对上述四个原版 ID 逐一执行 `Holder.is`；例如把 forest 换成 desert 或作者自己的 forest ID 会拒绝。`biome` 基础字段没有这条四角色限制。修改这些原版 ID 对应的 biome 内容与换成另一 ID 是两回事。

尺寸由 `PlanetType.size(seed)` 在 min/max 间按 16 格步长确定性选取。`PlanetShape.radius()` 为 `size × 2 / π`。类型深度为 `round(radius × depth_ratio)`，夹到 `[191, floor(radius × 0.75)]`；因此比例 0 也不是零深度，512 格岩石星球默认深度是 191 而非半径一半。底层 Y 为 `127 - depth`，底层以下不生成星球方块（`PlanetGeneration.floor`、`PlanetTerrain`）。

还要检查维度高度：内置 `dimension_type/native_deep.json` 是 `min_y=-528, height=848`（顶端 320），并非所有 codec 允许的深度都装得下。作者若设置更深的星球，需同时提供覆盖其底层的原版 dimension_type，并在预设引用；不要把通过类型解码当作完整维度验收。此类自定义高度组合**未实测**。

### 材料、矿层与气候的嵌套字段

`PlanetPalette.CODEC`：若提供 `palette`，前四项均必填，没有对象内部的逐项默认。方块字段使用已注册的方块 ID，不是带 properties 的 block-state 对象；使用方块默认状态。校验只拒绝默认状态为空气或含流体的方块，不能据此保证任意所选方块的物理行为。

| palette 字段 | 类型 | 整个 palette 省略时的值 | 作用 |
|---|---|---|---|
| `rock` | 方块 ID | `minecraft:stone` | 基础岩石 |
| `dark` | 方块 ID | `minecraft:basalt` | 暗色区域材料 |
| `light` | 方块 ID | `minecraft:andesite` | 亮色区域材料 |
| `deep` | 方块 ID | `minecraft:deepslate` | 深处材料 |
| `cover` | 可选方块 ID | 无 | 表面覆盖材料 |

`PlanetMinerals.CODEC`：`ores` 与 `strata` 各自可省略，默认为 `[]`；**空列表表示沿用算法原有矿物/地层，不表示没有矿物或地层**。`ores` 最多 16 项，`strata` 最多 8 项；方块校验同样拒绝空气和含流体默认状态。

| minerals 字段 | 类型、范围 | 默认 | 作用 |
|---|---|---|---|
| `ores` | 对象数组，0–16 项 | `[]` | 定制矿物列表；重叠时前项先匹配 |
| `ores[].block` | 方块 ID | 必填 | 矿物普通形态 |
| `ores[].deep` | 方块 ID | 无（使用 block） | 深处形态；有至少两层 strata 时在第二层起点以下使用，否则以标准层 Y=0 为界 |
| `ores[].from` | 浮点数，0–1 | `0` | 开始深度比例 |
| `ores[].to` | 浮点数，0–1 | `1` | 结束深度比例 |
| `ores[].rarity` | 浮点数，0.5–0.999 | 必填 | 越接近 1 越稀少 |
| `strata` | 对象数组，0–8 项 | `[]` | 从浅到深的地层 |
| `strata[].block` | 方块 ID | 必填 | 该层材料 |
| `strata[].from` | 浮点数，0–1 | `0` | 该层起点，延续至下一层；第一层也覆盖名义地表以上 |

比例 0 是名义地表，1 是底层。codec 先换算成 `round(127 - 比例 × 191)` 的标准层，再由 `PlanetTerrain.layer` 映射到该星球深度，不是任意精度的连续分层。矿物换算后的 top 必须 ≥ bottom；第一地层换算后必须从 127 开始，后续层起点必须严格递减，两个比例若取整为同一层也会被拒绝。建议按 from ≤ to、地层比例严格递增且留足间隔书写。

`PlanetClimate.CODEC`：`climate` 内各项均可省略。

| climate 字段 | 类型、范围 | 默认 | 作用 |
|---|---|---|---|
| `humidity` | 浮点数，-0.5–0.5 | `0` | 正值偏森林，负值偏干旱 |
| `temperature` | 浮点数，-0.5–0.5 | `0` | 正值使干燥区域更偏沙漠 |
| `latitude` | 浮点数，0–1 | `0.15` | 两极相对赤道的降温影响；0 不按纬度调整 |
| `land` | 浮点数，-40–40 | `0` | 地形相对固定海面抬升的格数；正值减少海洋 |

`PlanetType.planet` 使用 `PlanetTerrain.landFor` 在六面各 24×24 网格采样 `ocean`，换算为 land 后保存；`ocean=0` 使用 land=40。请求的比例不作为生成器里的独立海洋比例字段保存。

## 宇宙布局与世界预设字段

### 布局（universe 注册表）

依据 `UniverseLayout.CODEC`、构造器及 `Universe`：这里定义的是**预设显式星系之外**的太阳与星球。

| 字段 | 类型、范围 | 默认 | 作用 |
|---|---|---|---|
| `stars` | 对象 | `{"min":0,"max":0}` | 有限模式额外太阳数范围 |
| `stars.min` / `stars.max` | 整数，0 ≤ min ≤ max ≤ 64 | 提供 stars 时两项必填 | 不含预设的母星系太阳 |
| `planets` | 对象 | 必填 | 每颗额外太阳的行星数范围 |
| `planets.min` / `planets.max` | 整数，1 ≤ min ≤ max ≤ 8 | 均必填 | 单元发现时整组生成，不能只取其中一颗补满容量 |
| `star_gap` | 有限浮点数，见距离公式，上限 1000000 | `49152` | 宇宙单元网格间距，不是两太阳的精确距离 |
| `ring` | 有限浮点数，≥8192，另受 gap 约束 | `12288` | 同一太阳相邻行星轨道半径差 |
| `types` | 非空对象数组 | 必填 | 明确列出的候选类型；codec 没有数组长度上限 |
| `types[].type` | 类型 ID 字符串或完整内联星球类型对象 | 必填 | `UniverseLayout.savedInPlace` 读取 holder、写出完整定义 |
| `types[].weight` | 整数，1–1000 | `1` | 相对抽取权重，不是固定数量 |
| `density` | 浮点数，0–1 | `0` | 大于 0 为按接近发现模式，每单元按该概率有太阳；此时 stars.max 必须为 0 |

距离检查：`star_gap ≥ 4 × (8192 + (planets.max - 1) × ring) + 16384`，由 `UniverseLayout.minimumGap` 和 `GroundSolar.defaults` 决定。例如 max=2、ring=12288 时必须 gap≥98304；**省略 star_gap 的默认值并不适用于所有 planets 范围**。ring 没有独立固定上限，但上式与 gap≤1000000 共同限制它（max=1 时公式不限制 ring 的上端）。权重求和使用 Java int；没有总权重溢出校验，不能把“列表未限长”理解成安全的无限列表。

有限模式在首次生成时决定全部额外天体；density 模式初始检查母星周围八个单元，旅途中检查所在单元及周围一圈（`REACH=1`），母星单元 `(0,0)` 不生成额外太阳。相同世界种子与单元决定相同内容，加入顺序决定附加编号；已存在条目不重排。`density=0` 不是“按需发现但没有太阳”，而是有限模式。

### 系统生成器与单颗星球

预设是 Minecraft 的 `dimensions` 对象；每个维度写 `type`（dimension_type ID）和 `generator`。星球系统位于 `starfold:native_planet`，生成器 `type` 为 `starfold:native_system`，由 `SystemChunkGenerator.CODEC` 读取：

| 字段 | 类型、范围 | 默认 | 作用 |
|---|---|---|---|
| `storage_layout` | 字符串 | 必填 | 用 `six_face_square_v2`；旧 `six_face_x32768_v1` 仅接受两颗显式星球，绑定非空额外宇宙还会拒绝旧排布 |
| `planets` | 星球条目数组，2–810000 项 | 不提供 | 显式星球，按存储顺序；第一颗必须完整写出生成器，不能只写类型 |
| `a` / `b` | 完整单星生成器对象 | 不提供 | 旧两星写法：两项必须同时有，a 为 v4、b 为 v5；不能与 planets、stars 或 universe 混用 |
| `stars` | 正整数数组，总和等于 planets 长度 | `[planets.length]` | 显式星球按太阳分组，第一组至少两颗；还需通过 Java 固定间距检查，不是布局里的 stars 对象 |
| `universe` | 布局 ID 或完整布局对象 | 无额外宇宙 | 仅 planets 写法可用；保存时内联 |
| `system` | 星系 ID（也可内联完整定义） | 不提供 | 新手写星系入口，与 planets/a/b/stars/universe/celestial 互斥；见下方专章 |
| `celestial` | 内部保存快照 | 不提供 | 由生成器写出，不是包作者替代 system 的入口；须配套完整 planets |

所有系统星球最终必须 terrain v4 或 v5，且 `multi_planet=false`。显式分组还受 `PlanetSystem.model` 限制：相邻太阳的最外圈半径之和加 8192 不得超过固定 `STAR_GAP=49152`；轨道步长固定 `RING=12288`。因此数组数量上界不是任意分组都可用的保证。

非首颗可写类型条目（`PlanetType.PLANET`）：

| 字段 | 类型、范围 | 默认 | 作用 |
|---|---|---|---|
| `planet_type` | 类型 ID 或完整内联类型对象 | 必填 | 存在此字段就选择类型条目解码 |
| `terrain_seed` | 有符号 64 位整数 | 必填 | 单星种子，不自动取世界种子 |
| `size` | 整数，该类型范围内、16 的倍数 | 由种子选择 | 指定这一颗的面宽 |

完整条目用 `PlanetChunkGenerator.CODEC`（系统数组内部不需要生成器 `type` 分派字段）：

| 字段 | 类型、范围 | 默认 | 作用 |
|---|---|---|---|
| `settings` | 原版 `FlatLevelGeneratorSettings` 对象 | 必填 | 基础 biome/FlatLevelSource 设置；Native 地形不是这些 flat layers 的平面堆叠 |
| `terrain_seed` | 有符号 64 位整数 | 必填 | 地形种子 |
| `terrain_version` | 整数，2/3/4/5 | 必填 | 单星类支持这些版本；系统只接受 4/5 |
| `planet_biomes` | 对象 | 无 | v4 必须提供，非 v4 不允许；含必填 terrain_seed（须等于外层种子）及 forest/plains/desert/ocean，四 ID 限制同上 |
| `planet_generation` | 对象 | v2/3/4 由版本取默认 | v5 必填；完整生成策略见下表 |
| `multi_planet` | 布尔 | false | 旧跨维度路径标志；当前系统不允许 true |

`settings` 的格式由原版 codec 定义：`biome` 为 biome holder（缺省取 plains）、`layers` 为必填数组（每项 `height` 为 0–4064 整数、`block` 为方块 ID，缺省 air，总厚度不得超过 4064），`features` / `lakes` 默认 false，`structure_overrides` 为可选结构集 holder 集合（ID 列表或标签）。示例显式写出这些基础设置。它们不代替 `planet_generation` 的星球地物/结构开关。

`PlanetGeneration.CODEC` 的字段不同于类型定义，尤其不要混用默认值：

| 字段 | 类型、范围 | 默认 |
|---|---|---|
| `type` | 字符串，`rocky` 或 `temperate`（算法家族，不是注册表类型 ID） | 必填 |
| `algorithm` | rocky 家族：rocky/rocky_craters；temperate 家族：surface/mining/ecology | 必填 |
| `algorithm_version` | 整数，仅 1 | 必填 |
| `relief` / `ore_bias` / `cave_width` | 同类型表范围；temperate 固定 1/0/0.115 | 均必填 |
| `size` | 整数，shape 及深度校验同上 | 2048 |
| `depth` | 整数，191–floor(radius×0.75) | 191，不按比例计算 |
| `shell` | bedrock/core_shell | **bedrock** |
| `palette` / `minerals` / `climate` | 同前述对象 | ROCK / 空表 / MILD |
| `creatures` | 布尔 | **true**，不按算法区分 |
| `features` / `structures` | 布尔 | **false**，不按算法区分 |

`PlanetGeneration.validate` 要求 terrain v2→surface、v3→mining、v4→ecology、v5→rocky 或 rocky_craters。自定义 minerals 在此还允许 temperate/mining；palette 仍只允许 rocky_craters，自定义 climate 仍只允许 ecology。生成策略没有 depth_ratio、ocean 或类型 ID；它保存具体 size/depth/land 等数值。

## 内置八种类型与预设

来源：`src/main/resources/data/starfold/starfold/planet_type/*.json`。ID 均带 `starfold:` 前缀。八个配置不是八种独立 Java 算法。

| 类型 | 面宽范围 | depth_ratio | 算法 | 主要区别 |
|---|---:|---:|---|---|
| rocky | 512–1536 | 0.5 | rocky_craters | stony_peaks；relief/ore_bias/cave_width=1.2/0.04/0.09 |
| desert | 512–2048 | 0.5 | rocky_craters | desert；0.8/0.03/0.08；沙/砂岩覆盖，砂岩→陶瓦→石头→深板岩地层，五种定制矿物 |
| ice | 448–1024 | **0.4** | rocky_craters | snowy_plains；1.4/0.02/0.1；浮冰/蓝冰/雪，四层与青金石/铁/钻石/绿宝石矿物 |
| temperate | 1024–2048 | 0.5 | ecology | 四原版 biome 角色；默认气候，不指定 ocean |
| lush | 1024–2048 | 0.5 | ecology | humidity=0.22、temperature=-0.1、latitude=0.1、ocean=0.55 |
| arid | 1024–2048 | 0.5 | ecology | humidity=-0.35、temperature=0.05、latitude=0.5、ocean=0.2 |
| archipelago | 1024–2048 | 0.5 | ecology | humidity=0.15、temperature=0、latitude=0.15、ocean=0.88 |
| steppe | 1024–2048 | 0.5 | ecology | humidity=-0.3、temperature=0.1、latitude=0.4、ocean=0 |

五种 ecology 的基础 biome 都是 plains，四角色都是 forest/plains/desert/ocean；三项生成系数默认 1/0/0.115。八种类型都默认 core_shell；三种岩石的 creatures/features/structures 默认全 false，五种生态默认全 true。

`universe/default.json` 按上表顺序使用权重 **3/2/2/2/1/1/1/1**，density=0.7、star_gap=98304、ring=12288、每太阳 1–2 行星。`small.json` 类型与权重相同，但不设 density，改为 2–4 颗额外太阳。

`worldgen/world_preset/native_universe.json` 的 A/B 是完整生成器，不从上述类型表抽取：A seed=20261001、v4、size 默认 2048、depth=652；B seed=20261002、v5 rocky_craters、size=1024、depth=326。之后的宇宙引用 `starfold:default`，其生成使用世界种子（`Universe.bind/generate`）。

## 完整最小示例：灰岩类型与新世界

需要当前模组。下面四个文件就是整个包；不需要复制模组资源。它新增 `demo:ash` 与 `demo:ash_universe`，并**完整覆盖**现有 `starfold:native_universe` 预设：保留 A，B 改用新类型，额外太阳也只抽新类型。不是给原预设数组做追加。

在**创建新世界**的数据包选择中启用此包，并选择 `starfold:native_universe` 世界类型；不要选普通原版预设后期待自动替换。覆盖预设已在模组的 normal 标签中，无须另加标签。包根目录直接包含 pack.mcmeta 和 data；若压成 zip，不要在外面再包一层目录。此示例的独立包游戏加载与 UI 创建**未实测**。

### `pack.mcmeta`

```json
{
  "pack": {
    "pack_format": 48,
    "description": "Starfold 灰岩星球示例（Minecraft 1.21.1）"
  }
}
```

### `data/demo/starfold/planet_type/ash.json`

```json
{
  "biome": "minecraft:stony_peaks",
  "size": {"min": 512, "max": 1024},
  "algorithm": "rocky_craters",
  "relief": 0.7,
  "palette": {
    "rock": "minecraft:tuff",
    "dark": "minecraft:basalt",
    "light": "minecraft:andesite",
    "deep": "minecraft:deepslate"
  }
}
```

### `data/demo/starfold/universe/ash_universe.json`

```json
{
  "density": 1.0,
  "planets": {"min": 1, "max": 1},
  "star_gap": 49152,
  "ring": 12288,
  "types": [{"type": "demo:ash", "weight": 1}]
}
```

### `data/starfold/worldgen/world_preset/native_universe.json`

```json
{
  "dimensions": {
    "minecraft:overworld": {
      "type": "minecraft:overworld",
      "generator": {
        "type": "minecraft:noise",
        "settings": "minecraft:overworld",
        "biome_source": {"type": "minecraft:multi_noise", "preset": "minecraft:overworld"}
      }
    },
    "minecraft:the_nether": {
      "type": "minecraft:the_nether",
      "generator": {
        "type": "minecraft:noise",
        "settings": "minecraft:nether",
        "biome_source": {"type": "minecraft:multi_noise", "preset": "minecraft:nether"}
      }
    },
    "minecraft:the_end": {
      "type": "minecraft:the_end",
      "generator": {
        "type": "minecraft:noise",
        "settings": "minecraft:end",
        "biome_source": {"type": "minecraft:the_end"}
      }
    },
    "starfold:native_planet": {
      "type": "starfold:native_deep",
      "generator": {
        "type": "starfold:native_system",
        "storage_layout": "six_face_square_v2",
        "planets": [
          {
            "settings": {
              "biome": "minecraft:plains",
              "features": false,
              "lakes": false,
              "layers": [{"height": 1, "block": "minecraft:bedrock"}],
              "structure_overrides": []
            },
            "terrain_seed": 20261001,
            "terrain_version": 4,
            "planet_generation": {
              "type": "temperate",
              "algorithm": "ecology",
              "algorithm_version": 1,
              "relief": 1,
              "ore_bias": 0,
              "cave_width": 0.115,
              "depth": 652,
              "shell": "core_shell",
              "features": true,
              "structures": true
            },
            "planet_biomes": {
              "terrain_seed": 20261001,
              "forest": "minecraft:forest",
              "plains": "minecraft:plains",
              "desert": "minecraft:desert",
              "ocean": "minecraft:ocean"
            }
          },
          {"planet_type": "demo:ash", "terrain_seed": 20261003, "size": 768}
        ],
        "universe": "demo:ash_universe"
      }
    }
  }
}
```

## 已有世界：保存了什么，仍然读取什么

不能把“星球参数随世界保存”说成“整个数据包已快照”。具体边界如下：

1. **显式星球参数固定。** `PlanetType.Entry.get` 首次使用时把类型+种子变成生成器；`PLANET.encode` 总是写完整生成器，不保留 `planet_type` 名称作为来源。保存之后不再根据后来改动的类型 JSON 重算这颗星球。
2. **未发现区域的规则也内联保存。** `SystemChunkGenerator.CODEC` 通过 `UniverseLayout.HOLDER` 保存 universe；`savedInPlace` 将布局和每个加权类型完整写出。重新载入保存的系统时使用内联定义，而不是重新按原 layout/type ID 获取新值。改 default.json 不等于改变这个世界未来单元的抽取规则。
3. **实际宇宙另存。** `Universe.save/load` 在 `starfold_native_universe_v1` SavedData 中保存 format=1、太阳坐标/半径、行星的所属太阳/轨道半径/周期/相位/完整生成器、已探索单元（包括无太阳单元）。`bind` 已有数据优先；只在没有数据时用世界种子生成。存在但无法读取的文件拒绝静默重建。追加保留编号和存储地址。
4. **预设不是已有世界的更新脚本。** `PlanetWorldPreset.registryFor` 在维度 baking 时保留明确选择/保存的 Native 系统，不用数据包默认维度替换它。这里只描述正常保存与加载路径，不承诺手改存档的迁移。
5. **注册表内容不是完整快照。** biome holder、方块 ID 仍是引用；`PlanetDecoration` 读取本次加载的 biome feature 列表和结构注册表，`SystemChunkGenerator` 使用本次加载的结构集标签与 biome 生物列表。替换这些定义可能影响后续生成；缺失引用可能导致加载失败。删除类型/布局定义本身不再是这些已内联参数的直接依赖，但整个包能否安全移除还取决于包中其它引用与加载校验，不能保证任意移除均成功。
6. **不因改包重写已有区块。** 上述代码没有按新类型刷新旧区块的操作；已有方块仍由世界区块保存。未来生成并非在任意 biome/features/structures 修改后都保持不变。注册表重载不是逐 tick 重新读 JSON；`/reload` 或重启后的完整第二包生命周期**未实测**，不作为本文保证。

## 仍在 Java 中的规则与容量限制

- **程序布局 universe 不提供逐天体字段；手写请用 system 注册表。** `UniverseLayout` 不提供 sun XYZ、太阳半径、逐行星轨道倾角/偏心率/周期/相位字段；太阳半径随机整数 384–640，网格内 x/z 抖动幅度为 gap 的 ±0.25、y 为 ±0.35。有限模式从 -4…4 的 x/z 网格（除母星单元）按距离选近处。行星使用母星轨道轴/径向参考、母星 B 自转参数、随机相位，周期为母星周期乘轨道半径比的 1.5 次方。
- **旧 planets 写法保留原样。** 没有 system/celestial 的生成器仍由 `PlanetSystem.model(int[])` 固定太阳沿 x 排列、间距 49152、环间距 12288；前两颗沿用特殊 A/B 模型，后续相位为编号×2.1。`GroundEphemeris.defaults`、`GroundSolar.defaults` 的旧母星中心 `(0,0,8192)`、轨道半径 8192、周期 24000 tick、自转与母太阳半径 512 全部不变。新 system 写法独立保存手写值，但仍受前两颗配对限制（见专章）。
- **地形算法仍由代码实现。** `PlanetTerrain`、`PlanetRock`、`PlanetUnderground`、`PlanetEcology` 决定噪声、矿洞、生态与树木；类型只能选择允许算法并传入已有参数。不能注入新 density function 图、海平面或海水流体选择。海面固定 128、水为原版 water，岩石家族无海。
- **原版装饰有过滤。** `PlanetDecoration` 跳过 LAKES 与 UNDERGROUND_ORES；生态星球还跳过包含 `Feature.TREE` 的 placed feature，保留本模组自己的树。`SystemChunkGenerator` 只在离面边缘至少 8 区块的起点尝试结构，这不是任意大型外部结构包围盒不越界的证明。大型第三方结构、八种类型所有尺寸的生物/地物/结构及生态平衡**未实测**。
- **地址槽不是发现上限。** `PlanetStorage.SIDE=900`，`MAX_PLANETS=900×900=810000`，地址编号 0…809999；不是 900 颗。`column/row` 对 810000 抛异常，`index` 对超出方阵的行列返回 -1。地址可计算也不表示世界已存在该星球，实际访问还校验系统/shape 数量。
- **程序发现上限是总计 1600 颗，包含显式母星系。** `Universe.CAPACITY=1600`；`generate` 的 endless 分支与运行时 `grow` 给 `explore` 传 `CAPACITY-home.count()`，再扣除已经发现的 bodies。默认两颗显式星球时最多再发现 1598 颗，最后合法全局编号 1599；下一颗 1600 不会被发现。这是整模型同步包预算的代码上限，不是内存、性能或 810000 颗可玩性的保证。
- **耗尽时没有玩家提示。** `discover` 若有太阳但整组行星放不下，返回 false，不添加太阳/行星、不标记该单元已探索、不置脏；不会部分加入。无太阳单元仍可标记为已探索。`explore` 返回是否添加过天体；`grow` 没添加则直接 false，没有区分“满了/没太阳/已探索”的聊天、HUD 或状态。剩一格遇到两行星单元也会拒绝，但其它单行星单元仍可填满。
- **1600 不是所有底层 API 的统一校验。** `discover/explore` 使用调用者提供的 room/capacity；`Universe` 构造、读取、有限生成及 `SystemChunkGenerator.bind` 不统一强制 1600（bind 检查的是 810000 地址上限）。手写超大显式系统或向这些 API 传更大预算不等于受支持的运行时扩容。容量回归也明确区分两者。

## 离线证据与未实测边界

- `PlanetTypeTest`：内置 JSON、参数拒绝、大小与深度、材料/矿层、四原版 biome 限制、海洋/气候、开关、类型条目完整保存。新增 `theDocumentedPackNamesItsOwnTypeAndLayoutAndMakesANewSystem` 直接读取本文四段 JSON，在 demo 命名空间注册类型与布局，检查 B 及额外行星采用新材料。测试手工构造注册表，不是独立第二包的游戏加载。
- `UniverseTest`：确定性、距离、布局内联、SavedData、追加稳定、探索单元保存与容量。新增 `theLastDiscoverablePlanetFitsAndTheNextCellWaitsDespiteSpareStorage` 验证 1599 接受、1600 拒绝，整组不能部分加入，拒绝不改保存状态且重载后单元仍未探索，并验证更大的地址槽独立存在。
- `PlanetShapeTest`、`PlanetDepthTest`：形状/存储与深度边界，不替代所有尺寸的游戏验收。

此前说明文档的离线基线：全量 test **1887** 项、nativeTest **544** 项、packagedContractTest **2** 项。新增手写星系的本轮运行结果见下方专章；Native 是全量套件的重叠子集，不把三组相加当作独立测试数。

第二包完整生命周期、自定义 biome 缺失后的游戏失败路径、第三方结构及各类型实际生态效果均保留为**未实测**。

## 手写星系注册表 system：一个包创建自己的星系

包可以定义太阳位置、大小、每颗行星的圆轨道与自转，而不改 Java、不覆盖内置预设。实现入口是 `SystemDefinition`，路径为 `data/<namespace>/starfold/system/<name>.json`。世界预设中的 Native 生成器写 `"system":"<namespace>:<name>"`，并使用 `"storage_layout":"six_face_square_v2"`。不能同时写 `planets`、`a`、`b`、`stars`、`universe` 或内部 `celestial`。额外程序宇宙放在星系定义的 `beyond`，不是生成器的 `universe`。

### 字段、单位与默认值

所有坐标是**绝对宇宙坐标**，不是 Level 方块存储坐标，不相对第一颗太阳自动平移。母太阳的位置就是 `model.center()`；选中的母星固定映射到存储编号 0，所以已有自动抵达/重生逻辑仍然指向母星。

| 字段 | 类型、范围 | 省略时 |
|---|---|---|
| `suns` | 非空太阳数组；总行星数 2–1600 | 必填 |
| `suns[].position` | 三个有限数 `[x,y,z]`，单位格；向量长度 ≤1e9，另受下方可达性限制 | 必填 |
| `suns[].radius` | 有限数，32–4096 格；母太阳 ≤1024 | 必填 |
| `suns[].planets` | 非空行星数组；母太阳至少两颗 | 必填 |
| `suns[].planets[].type` | planet_type ID，或完整内联类型对象 | 必填；未知 ID 拒绝加载 |
| `…size` | 面宽格数，该类型范围内、16 的倍数，且满足深度规则 | 按类型与单星种子选取；不是半径 |
| `…seed` | 有符号 64 位整数 | 从世界种子和源 JSON 中展平后的行星索引确定性派生 |
| `…name` | 1–64 个 UTF-16 字符，不能全空白或含控制字符 | 按最终存储编号 A、B、…、Z、P26、P27… |
| `…orbit` | 圆轨道对象 | 必填 |
| `…orbit.radius` | 有限数，1–1e7 格，另受配对、安全距离与可达性限制 | 必填 |
| `…orbit.period` | 有限数，12000–1e9 tick；20 tick/秒 | 必填 |
| `…orbit.phase` | 有限数，弧度 | 0 |
| `…spin` | 自转对象 | **无自转**；不是 GroundSolar 默认倾角自转 |
| `…spin.period` | 有限数，24000–1e9 tick | 提供 spin 时必填 |
| `…spin.tilt` | 有限数，-180–180 **度** | 0 |
| `home` | 对象，零基索引 | `{"sun":0,"planet":0}` |
| `home.sun` / `home.planet` | 指向源 JSON 中存在的太阳/该太阳的行星 | 各为 0 |
| `beyond` | universe ID 或完整内联布局 | **没有程序宇宙**，仅存在写下的星球 |

圆轨道在该太阳的 XZ 平面，轴为 +Y，径向零点为 +Z：`angle=phase+2π*tick/period`，位置为 `sun.position + [radius*sin(angle),0,radius*cos(angle)]`。不支持偏心轨道/轨道倾角。自转轴为 `[sin(tilt),cos(tilt),0]`，初始姿态为单位旋转、相位 0、epoch 0。不同的自转轴与周期不是轨道倾角。新手写星系时钟从 **tick 0** 开始，不套用旧预设的日出快进；旧预设时钟行为不变。

源索引按 JSON 太阳顺序、每太阳的行星顺序从 0 展平；省略 seed 时以 `worldSeed + 0x9E3779B97F4A7C15*(index+1)` 为输入，依次应用 SplitMix64 的 xor-shift 30/27/31 与乘数 `0xBF58476D1CE4E5B9`、`0x94D049BB133111EB`（64 位回绕）。换世界种子只影响未显式给 seed 的星球；改 home 不改变这些派生种子。实际数值随后完整保存，不会每次登录重新抽取。

### 作者必须遵守的限制

1. **保留基础 A/B 模型的配对限制。** 母星与母太阳行星列表中第一颗非母星必须同轨道半径、同周期、相位差 π（模 2π）；共用半径必须在 **6000–8192**。例如相位 0 与 `3.141592653589793`，不要使用过短近似。其余每颗行星可独立写半径/周期/相位，两颗配对星的自转与大小也独立。母星可不是列表中的第一个；母太阳也可不是第一个。未修改 GroundEphemeris 或冻结的 GroundSolar 来绕过此限制。
2. **存储顺序固定。** 母太阳先，母星编号 0、配对星编号 1，再放母太阳剩余行星（原顺序），最后其余太阳与行星（原顺序）。`home` 通过这个顺序物化，不是运行时不断重新定位的选项。手写名字随 Shapes v2 元数据发给客户端；旧默认命名不变。
3. **安全间隔有余量。** 以 `r=size*2/π` 为行星半径；省略 size 时校验使用类型的最大面宽，因此任何世界种子都安全。两行星全时刻中心距离至少为 `r1+r2+2*2048+256`；2048 是 `Space.OUT`，256 是额外余量。不同周期用轨道半径差作最小距离；相同周期用固定相位差的弦距 `sqrt((R1-R2)^2+4*R1*R2*sin(Δphase/2)^2)`，所以同环但不同周期一定拒绝，同环同周期也不能靠得太近。
4. 每颗轨道半径必须大于 `太阳半径 + 行星半径 + 2048 + 128`。每太阳的安全包络是该组最大的 `轨道半径 + 行星半径 + 2048 + 128`；两太阳距离不能小于两包络之和。跨太阳使用保守球形包络，即使作者认为某些相位不会相撞，也不放宽。
5. 母太阳距绝对原点 ≤100000 格，母太阳半径 ≤1024（旧 Sun/Model 的限制）。所有手写太阳还必须满足 `太阳位置向量长度 + 安全包络 ≤200000`，避免造出超出个人飞行存档坐标上限、无法抵达的星球。字段的底层 1e7/1e9 上限不代表能越过此限制。
6. 最多 **1600** 颗手写行星；底层存储虽有 810000 槽，整模型同步预算先限制它。beyond 的追加也不能超过总计 1600；已有编号、存储地址与手写轨道不重排。新增程序太阳的完整可能轨道包络会避开所有手写太阳的安全包络；被占据的单元记为已探索但不生成太阳，有限模式跳过冲突位置，必要时少生成。程序宇宙本身仍遵循前文旧布局规则，不承诺其远处内容都在个人飞行范围内。它以母星半径/周期按 Kepler 比例派生周期，因此还要求 `母星周期 × ((母星半径+(beyond.planets.max-1)×beyond.ring)/母星半径)^1.5 ≤1e9 tick`，过大时缩短母星周期或减小 beyond 环距/行星数。
7. size 仍须通过类型范围和深度检查；维度类型还须装得下所选深度。例子全部使用内置 native_deep 能容纳的配置。未知类型、非有限数、非法周期、间距、数量等在定义解码或所选世界初始化时拒绝，错误指出字段/太阳与行星索引。注册表 holder 未绑定前不提前读取类型；拿到世界种子前不生成临时地形。

### 保存边界与旧世界

`SystemChunkGenerator.createState` 拿到世界种子后一次物化。`level.dat` 的生成器保存完整 `planets`、`celestial`（初始太阳/轨道/自转/名称/分组，母星已映射到编号 0）以及可选的内联 `universe`。`system` 和类型 ID 不再用于重建它。创建界面可能在拿到世界种子之前复制 codec：此时编码的是**内联完整星系与类型定义**，绝不以 0 猜测世界种子，也不会留下必须回读原 system ID 的保存值。

时钟的 SavedData 沿用现有格式保存当前模型、tick 与运行中的自转开关；它是运行状态，初始生成配置来自已保存的 celestial，不再来自数据包。完整同步沿用 Sky 中的现有模型 NBT，名称另随 Shapes v2。新旧客户端应使用相同模组构建。

保存后，修改/移除包中的 system/type/layout 定义不会移动已有太阳和星球，不能修改旧地表；内联 beyond 也不会重新读取改后的布局。**这不是整个包的快照**：biome、方块、结构等注册表 ID 仍按前文依赖处理。删包若顺带删除了存档引用的自定义 biome/方块/维度类型，仍可能不能加载。已有 `planets` 预设、特别是 `native_universe` 的模型逐字段/逐帧保持旧值；不升级旧世界。

### 完整示例包：两颗太阳、五颗行星

实物目录：`tools/datapacks/example_system/`，逐字节副本在 `src/test/resources/datapacks/example_system/`。以下五个文件完整组成包，使用自有命名空间，不覆盖旧预设。模组 normal 标签只有 native_universe 和体验包预设，其余内置预设不出现在世界类型列表里；**没有擅自新增内置普通世界类型**。只有安装示例包才会通过包自己的 normal 标签显示这个选项。

#### `pack.mcmeta`

```json
{
  "pack": {
    "pack_format": 48,
    "description": "Starfold: two hand-written suns and five planets (未实测)"
  }
}
```

#### `data/handwritten/starfold/planet_type/ash.json`

```json
{
  "biome": "minecraft:stony_peaks",
  "size": {"min": 512, "max": 1024},
  "algorithm": "rocky_craters",
  "relief": 0.7,
  "palette": {
    "rock": "minecraft:tuff",
    "dark": "minecraft:basalt",
    "light": "minecraft:andesite",
    "deep": "minecraft:deepslate"
  }
}
```

#### `data/handwritten/starfold/system/twin_lanterns.json`

```json
{
  "suns": [
    {
      "position": [0, 0, 0],
      "radius": 512,
      "planets": [
        {"type": "starfold:temperate", "size": 2048, "seed": 20261001, "name": "Aster", "orbit": {"radius": 8192, "period": 24000, "phase": 0}, "spin": {"period": 48000, "tilt": 23.5}},
        {"type": "handwritten:ash", "size": 768, "name": "Cinder", "orbit": {"radius": 8192, "period": 24000, "phase": 3.141592653589793}},
        {"type": "starfold:ice", "name": "Rime", "orbit": {"radius": 17000, "period": 90000, "phase": 1.2}, "spin": {"period": 72000, "tilt": -12}}
      ]
    },
    {
      "position": [60000, 4000, -20000],
      "radius": 420,
      "planets": [
        {"type": "handwritten:ash", "size": 512, "orbit": {"radius": 9000, "period": 36000, "phase": 0.7}},
        {"type": "starfold:desert", "size": 1024, "name": "Dune", "orbit": {"radius": 18000, "period": 110000, "phase": 2.4}}
      ]
    }
  ],
  "home": {"sun": 0, "planet": 0}
}
```

#### `data/handwritten/worldgen/world_preset/twin_lanterns.json`

```json
{
  "dimensions": {
    "minecraft:overworld": {"type": "minecraft:overworld", "generator": {"type": "minecraft:noise", "settings": "minecraft:overworld", "biome_source": {"type": "minecraft:multi_noise", "preset": "minecraft:overworld"}}},
    "minecraft:the_nether": {"type": "minecraft:the_nether", "generator": {"type": "minecraft:noise", "settings": "minecraft:nether", "biome_source": {"type": "minecraft:multi_noise", "preset": "minecraft:nether"}}},
    "minecraft:the_end": {"type": "minecraft:the_end", "generator": {"type": "minecraft:noise", "settings": "minecraft:end", "biome_source": {"type": "minecraft:the_end"}}},
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
{"replace": false, "values": ["handwritten:twin_lanterns"]}
```

### 让新世界使用它（专服已实测；单人界面未实测）

- **单人：** 创建新世界 → 数据包，放入 `example_system` 文件夹（或根目录直接含 pack.mcmeta/data 的 zip），启用它；在世界类型选择包新增的 `handwritten:twin_lanterns`，不要选择原版普通或旧 native_universe。未附带翻译资源包时可能显示翻译键。选择种子后创建；应自动到 Aster。普通列表来自示例包 normal 标签，旧实验仍须 Alt。
- **专用服务器：** 停服，为**新且尚无 level.dat** 的 `level-name` 目录预置 `datapacks/example_system/`；在 server.properties 写 `level-type=handwritten:twin_lanterns`，设置 `level-seed`，并保证数据包未被禁用。用相同模组构建启动，客户端也相同。不要通过更改旧服务器的 level-type 去迁移已有世界；不要删除旧世界来套例子。专服实测会自动启用预置的 `file/example_system`，不必额外修改 `initial-enabled-packs`。
- 想有程序宇宙：在星系 JSON 顶层添加 `"beyond":"starfold:default"`；不要同时在生成器写 universe。原例故意省略，以便直接验收两太阳五行星，不混入随机天体。

### 专服实机验收（2026-10-08）

证据与每轮限制见 `build/space/sysgame.md`，日志/截图为 `build/space/sysgame-<n>-*`。独立包未加入模组资源或硬编码预设。

- 新世界自动抵达 Aster；两太阳半径 512/420，五行星为 Aster、Cinder、Rime、D、Dune。首个测试世界面宽/深度依次为 2048/652、768/244、464/191、512/191、1024/326；Rime 未指定大小，换世界种子可不同。
- 五颗行星的同步宇宙位置在记录 tick 与独立 JSON 公式比较，误差小于 1e-10 格；五颗均落地、实际破坏方块、放置钻石块和箱子并由服务器回读。Dune 床可睡眠，死亡后回到该床。采用命令访问，不作为跨太阳连续飞行的验收。
- 删包后世界加载，五颗固定 tick=1234 的名称、尺寸、深度、位置、自转不变，五处建筑仍在；Cinder 未访问的另一面生成了 tuff 地表。把 Dune 轨道半径 18000 改为 20000 后旧世界不动，新世界使用 20000。
- 未知类型、近轨道、非法母星配对、system/planets 混写均拒绝启动，不回退默认星系。未知类型采用 Minecraft 的 `planet_type` 注册表 `Unbound values` 错误，包含缺失 ID。近轨道曾在创建维度时才失败；现于 `WorldDimensions.bake` 的已绑定、已选定维度中校验，专服不再先写出半成品 level.dat。
- 个人轨道视角曾因旧远裁剪面裁掉第二太阳；现在与独立太空区域共用 1000000 格远平面，近地裁剪范围不变。修正后两太阳均有无 HUD 截图（run 14），模型半径仍是包内值，没有放大远处太阳。
- 旧 native_universe 新世界自动到 A，render-smoke 通过；devworld-create2 的 cargo-fresh-g3 原脚本通过，坐着的驾驶员跨接缝及太空往返后仍在同一座椅与船上。

仍未验收：单人创建界面、非零 home 变体、跨太阳连续飞行、beyond 实机发现/重载。共享旧测试世界加载遗留的 throttle_lever 方块实体时仍有无效状态错误，未在本轮修复；完整日志见 run 12。

### 原实现的离线证据与验收清单（历史记录）

`AuthoredSystemTest` 覆盖实际包文件与文档、延迟 holder、世界种子绑定、JSON/NBT codec、移除 system/type 注册表后载入完整保存、Sky/Shapes 同步、tick 0/四分之一周期/一个周期的每颗星球位置、名称/母星重排、自转、几何和字段拒绝、程序宇宙预留空间及旧模型逐字段/逐帧回归。离线结果不是游戏加载验收。

本轮 `./gradlew check nativeTest --no-daemon` 通过：全量 **1937** 项、nativeTest **594** 项、packagedContractTest **2** 项，均为 0 失败、0 错误、0 跳过；新增 `AuthoredSystemTest` **43** 项。Native 是全量的重叠子集。日志在 `build/system-check.log`。未启动游戏。

**未实测，后续必须逐项验收：**
1. 单人与专服用独立示例包新建世界，注册表/预设无加载错误，normal 列表新增只来自该包；自动抵达 Aster，非 0 的 home 变体抵达所选母星。
2. 地面和太空都显示两太阳正确位置与半径；五颗行星名称、大小、各自轨道周期/相位与 Aster/Rime 自转正确，无自转星确实不转；客户端重连后 Shapes/Sky 一致。绝对 universe 坐标不要和 F3 的地表存储坐标混淆。
3. 实际航行访问两太阳五行星，起飞、远景、目标切换、进入大气、着陆、接缝、重生与保存重进正常；尤其确认跨太阳照明选择与母星坐标不在旧 `(0,0,8192)` 时没有隐藏假设。
4. 备份后改 system/type/layout，重启已保存世界，比较相同模拟 tick 下太阳/行星位置、种子、尺寸、自转与名称以及原建筑；再移除仅含本例原版引用的包并重启，同样不应重建星系。新建另一世界应采用改后的包。生物/方块/维度定义引用的删除不在此保证内。
5. beyond 变体探索与重载：旧手写星球不移动、不重编号，新增随机太阳不进入手写包络，保存的未来生成规则不随改包而变化；总容量到达时仍按现有停止追加规则，不新增耗尽 UX。
6. 非法类型、尺寸、周期、NaN/Infinity、近距离轨道与重叠太阳应拒绝新世界初始化并指出原因，不先生成临时地形；加载旧 native_universe 的天体模型、时钟和已有区块保持原样。
