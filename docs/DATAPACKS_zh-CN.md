# 数据包：星球类型、宇宙布局与恒星系

[English](DATAPACKS.md)

数据包可以给星海折越增加新的星球类型，改变母星系之外的宇宙如何排布，也可以手写一整个恒星系：每颗太阳在哪里、
有哪些行星绕它运行、行星多大、如何自转。本页是这些文件的参考，适用于 Minecraft 1.21.1（NeoForge）。

数据包设定的是星球如何**生成**。它不能增加新的地形算法，也不会把新星球放进已有的世界：一个世界生成什么，在创建时
就随世界保存了（见[已保存的世界保留什么](#已保存的世界保留什么)）。更换世界使用的数据包之前请先备份。

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

所有范围都包含两端。“必填”表示没有默认值。群系和方块都用 ID 填写。

| 字段 | 类型与范围 | 省略时 | 含义与限制 |
|---|---|---|---|
| `biome` | 群系 ID | 必填 | 星球的基础群系。岩石星球全球使用它；生态星球另外使用 `biomes` 里的四个角色 |
| `size` | 对象 | 必填 | 一个面的边长范围（格）。不是直径，也不是半径 |
| `size.min` / `size.max` | 整数，16 的倍数，min ≤ max | 均必填 | **416–2048**。（形状本身接受 256 起，但小于 416 的星球装不下最小的 191 格深度） |
| `depth_ratio` | 数字，0–0.75 | `0.5` | 深度占半径的比例；结果会取整并限制范围，见下文 |
| `algorithm` | `rocky`、`rocky_craters` 或 `ecology` | `rocky_craters` | 两种岩石算法或生态算法。列表是固定的，数据包不能增加算法 |
| `relief` | 数字，0.25–2 | `1` | 岩石地形的起伏程度。`ecology` 必须为 1 |
| `ore_bias` | 数字，-0.1–0.1 | 岩石 `0.035`，生态 `0` | 越大，岩石星球的矿脉越丰富。`ecology` 必须为 0 |
| `cave_width` | 数字，0.03–0.2 | 岩石 `0.085`，生态 `0.115` | 洞穴宽度。`ecology` 必须为 0.115 |
| `shell` | `bedrock` 或 `core_shell` | `core_shell` | 最底一层的方块：基岩或模组的星核外壳 |
| `palette` | 对象，见下文 | 石头、玄武岩、安山岩、深板岩 | 只有 `rocky_craters` 可以使用非默认的材料表 |
| `biomes` | 含四个角色的对象 | 无 | **`ecology` 必须提供，其他算法不允许提供** |
| `biomes.forest` | 群系 ID | 提供 `biomes` 时必填 | 必须是 `minecraft:forest` |
| `biomes.plains` | 群系 ID | 提供 `biomes` 时必填 | 必须是 `minecraft:plains` |
| `biomes.desert` | 群系 ID | 提供 `biomes` 时必填 | 必须是 `minecraft:desert` |
| `biomes.ocean` | 群系 ID | 提供 `biomes` 时必填 | 必须是 `minecraft:ocean` |
| `minerals` | 对象，见下文 | 两个空列表 | `rocky_craters` 和 `ecology` 可以设置；`rocky` 必须留空 |
| `climate` | 对象，见下文 | 温和 | 只有 `ecology` 可以使用非默认气候 |
| `ocean` | 数字，0–0.95 | 不指定 | 海洋占地表的比例。仅限 `ecology`，且此时 `climate.land` 必须为 0。比例按星球种子采样一次并换算成陆地抬升量；它是目标值，不是逐格精确的占比 |
| `creatures` | 布尔 | 生态 `true`，岩石 `false` | 群系的动物和怪物是否出现。它不是自定义生物列表 |
| `features` | 布尔 | 生态 `true`，岩石 `false` | 是否放置群系的原版地物。模组自己的地形、洞穴和树木不受它影响 |
| `structures` | 布尔 | 生态 `true`，岩石 `false` | 星球上是否生成结构，仍受结构集标签和群系限制 |

**生态的四个角色不能换成别的群系。** 每个都必须正好是表中的原版 ID；自定义的森林、或者把沙漠填到森林的位置，都会被
拒绝。`biome` 字段没有这个限制。修改这些原版群系的内容和换成别的群系是两回事，前者是允许的。

**尺寸与深度。** 星球的面宽按它的种子在 `min` 与 `max` 之间以 16 格为步长选定。半径为 `size × 2 / π`。深度为
`round(半径 × depth_ratio)`，并限制在 `[191, floor(半径 × 0.75)]` 之内：比例为 0 也有 191 格深，512 格的岩石星球深度
是 191，而不是半径的一半。最底一层在 Y `127 - 深度`，再往下不生成任何星球方块。

维度必须够高，才装得下这个深度。内置的维度类型 `starfold:native_deep` 的范围是 Y -528 到 320，所有内置类型和本页的
示例都装得下。更深的星球需要你自己提供能覆盖其最底层的维度类型，并在预设里引用。这种组合没有在游戏里试过。

### 材料表（palette）

提供 `palette` 时，前四个字段都必填。方块用 ID 填写，使用其默认状态。空气和含流体的方块会被拒绝。

| 字段 | 类型 | 默认材料表 | 含义 |
|---|---|---|---|
| `rock` | 方块 ID | `minecraft:stone` | 主体岩石 |
| `dark` | 方块 ID | `minecraft:basalt` | 暗色区域的岩石 |
| `light` | 方块 ID | `minecraft:andesite` | 亮色区域的岩石 |
| `deep` | 方块 ID | `minecraft:deepslate` | 深处的岩石 |
| `cover` | 方块 ID，可选 | 无 | 覆盖地表的一层 |

### 矿物与地层（minerals）

`ores` 和 `strata` 都可以省略。**空列表表示“沿用算法自带的”，不表示“没有矿物”或“没有地层”。** 空气和含流体的方块会被
拒绝。

| 字段 | 类型与范围 | 默认 | 含义 |
|---|---|---|---|
| `ores` | 0–16 个对象的列表 | `[]` | 星球的矿物。两种重叠时，靠前的优先 |
| `ores[].block` | 方块 ID | 必填 | 矿物 |
| `ores[].deep` | 方块 ID | 与 `block` 相同 | 它在深处的形态：有至少两层地层时，在第二层起点以下使用；否则在大约三分之二深度以下使用 |
| `ores[].from` | 数字，0–1 | `0` | 矿物从哪里开始，以深度的比例表示 |
| `ores[].to` | 数字，0–1 | `1` | 到哪里结束 |
| `ores[].rarity` | 数字，0.5–0.999 | 必填 | 越接近 1 越稀少 |
| `strata` | 0–8 个对象的列表 | `[]` | 从地表向下的岩层 |
| `strata[].block` | 方块 ID | 必填 | 该层的岩石 |
| `strata[].from` | 数字，0–1 | `0` | 该层从哪里开始，一直延续到下一层。第一层同时覆盖名义地表以上的部分 |

比例 0 是名义地表，1 是最底层。比例会先取整到一个 191 格标尺上的整数层，再拉伸到星球的实际深度，所以并不精确。取整
之后，矿物的 `from` 不能大于 `to`。第一层地层必须从 0 开始，之后每层必须严格更深；两层取整到同一层会被拒绝。比例之间
请留出明显的间隔。

### 气候（climate）

每个字段都可以省略。

| 字段 | 类型与范围 | 默认 | 含义 |
|---|---|---|---|
| `humidity` | 数字，-0.5–0.5 | `0` | 大于 0 偏向森林，小于 0 偏向干旱 |
| `temperature` | 数字，-0.5–0.5 | `0` | 大于 0 让更多干旱地区变成沙漠 |
| `latitude` | 数字，0–1 | `0.15` | 两极比赤道冷多少；0 表示不考虑纬度 |
| `land` | 数字，-40–40 | `0` | 陆地相对海面抬升的格数；大于 0 海洋更少 |

设置了 `ocean` 时，陆地抬升量由它算出并保存；`ocean: 0` 得到最大的抬升量 40。

## 宇宙布局

布局描述的是预设或恒星系写明的星系**之外**的太阳和行星。

| 字段 | 类型与范围 | 默认 | 含义 |
|---|---|---|---|
| `stars` | 对象 | `{"min": 0, "max": 0}` | 有限宇宙里额外太阳的数量 |
| `stars.min` / `stars.max` | 整数，0 ≤ min ≤ max ≤ 64 | 提供 `stars` 时均必填 | 不包含母太阳 |
| `planets` | 对象 | 必填 | 每颗额外太阳有几颗行星 |
| `planets.min` / `planets.max` | 整数，1 ≤ min ≤ max ≤ 8 | 均必填 | 一颗太阳的行星要么一起出现，要么都不出现 |
| `star_gap` | 数字，见下方规则，最大 1000000 | `49152` | 放置太阳的网格间距；不是两颗太阳之间的精确距离 |
| `ring` | 数字，至少 8192 | `12288` | 同一颗太阳相邻行星的轨道间距 |
| `types` | 至少一个对象的列表 | 必填 | 从中抽取的星球类型 |
| `types[].type` | 星球类型 ID，或就地写出的完整类型 | 必填 | |
| `types[].weight` | 整数，1–1000 | `1` | 被抽中的相对机会，不是数量 |
| `density` | 数字，0–1 | `0` | 大于 0 时宇宙在旅行中逐步发现，每个网格单元以此概率有一颗太阳；此时 `stars.max` 必须为 0 |

**距离规则。** `star_gap ≥ 4 × (8192 + (planets.max - 1) × ring) + 16384`。`planets.max` 为 2、`ring` 为 12288 时，间距
至少要 98304：**默认间距并不适用于所有的行星数量范围。**

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
| `stars` | 正整数列表，总和等于 `planets` 的数量 | 一颗太阳带全部行星 | `planets` 如何分给各太阳；第一颗太阳至少两颗 |
| `universe` | 布局 ID，或就地写出的完整布局 | 没有更远的宇宙 | `planets` 之外有什么。仅能与 `planets` 一起用 |

另外还有三个字段，不是给数据包作者用的：`a` 和 `b` 是恰好写两颗星球的旧写法，`celestial` 是游戏保存世界时写出的内容。

使用 `planets` 时，太阳排成一行、相距 49152 格，轨道相距 12288 格，这限制了一颗太阳能带多少行星。想自己安排太阳和
轨道，请改写恒星系。

**按类型写的星球条目**（除第一颗以外都可以这样写）：

| 字段 | 类型与范围 | 默认 | 含义 |
|---|---|---|---|
| `planet_type` | 类型 ID，或就地写出的完整类型 | 必填 | |
| `terrain_seed` | 64 位整数 | 必填 | 这颗星球自己的种子；不取自世界种子 |
| `size` | 类型范围内的整数，16 的倍数 | 由种子选定 | 这颗星球的面宽 |

**完整写出的星球条目**（第一颗必须这样写）：

| 字段 | 类型与范围 | 默认 | 含义 |
|---|---|---|---|
| `settings` | 原版的超平坦世界设置 | 必填 | 提供基础群系。星球的地形并不是这些平坦层 |
| `terrain_seed` | 64 位整数 | 必填 | 星球的种子 |
| `terrain_version` | `4` 或 `5` | 必填 | 4 是有生命的（生态）星球，5 是岩石星球 |
| `planet_biomes` | 对象 | 无 | 版本 4 必填，其他版本不允许：`terrain_seed`（与上面相同）和四个角色 `forest`、`plains`、`desert`、`ocean` |
| `planet_generation` | 对象，见下文 | 按版本取默认 | 版本 5 必填 |

`settings` 是原版格式：`biome`（省略时为平原）、`layers`（必填，每项有 `height` 和 `block`）、`features` 和 `lakes`
（省略时为 false）以及 `structure_overrides`。下面的示例把它们都写了出来。它们不能代替 `planet_generation` 里的
`features` 和 `structures` 开关。

`planet_generation` 用具体数值保存与星球类型相同的那些选择，**但它的默认值与类型不同**：

| 字段 | 类型与范围 | 默认 |
|---|---|---|
| `type` | `rocky` 或 `temperate` | 必填 |
| `algorithm` | `rocky` 下为 `rocky` 或 `rocky_craters`；`temperate` 下为 `ecology` | 必填 |
| `algorithm_version` | `1` | 必填 |
| `relief` / `ore_bias` / `cave_width` | 同类型；`temperate` 取 1 / 0 / 0.115 | 均必填 |
| `size` | 整数，同类型 | `2048` |
| `depth` | 整数，191 到 `floor(半径 × 0.75)` | `191`；不按比例计算 |
| `shell` | `bedrock` 或 `core_shell` | **`bedrock`** |
| `palette` / `minerals` / `climate` | 同类型 | 各自的默认值 |
| `creatures` | 布尔 | **`true`**，与算法无关 |
| `features` / `structures` | 布尔 | **`false`**，与算法无关 |

能按类型写的星球尽量按类型写：类型的默认值才是为游玩准备的。

## 内置类型与预设

所有 ID 都以 `starfold:` 开头。

| 类型 | 面宽 | `depth_ratio` | 算法 | 特点 |
|---|---:|---:|---|---|
| `rocky` | 512–1536 | 0.5 | `rocky_craters` | 裸岩山峰；起伏 1.2，矿物偏置 0.04，洞穴宽度 0.09 |
| `desert` | 512–2048 | 0.5 | `rocky_craters` | 沙漠；0.8 / 0.03 / 0.08；沙子覆盖，下面依次是砂岩、陶瓦、石头、深板岩；五种自己的矿物 |
| `ice` | 448–1024 | **0.4** | `rocky_craters` | 雪原；1.4 / 0.02 / 0.1；浮冰、蓝冰和雪；四层地层，含青金石、铁、钻石和绿宝石 |
| `temperate` | 1024–2048 | 0.5 | `ecology` | 四个原版角色，温和气候，不指定海洋比例 |
| `lush` | 1024–2048 | 0.5 | `ecology` | humidity 0.22，temperature -0.1，latitude 0.1，ocean 0.55 |
| `arid` | 1024–2048 | 0.5 | `ecology` | humidity -0.35，temperature 0.05，latitude 0.5，ocean 0.2 |
| `archipelago` | 1024–2048 | 0.5 | `ecology` | humidity 0.15，temperature 0，latitude 0.15，ocean 0.88 |
| `steppe` | 1024–2048 | 0.5 | `ecology` | humidity -0.3，temperature 0.1，latitude 0.4，ocean 0 |

五种生态类型的基础群系都是平原。八种类型都使用星核外壳。三种岩石类型的生物、地物、结构都关闭；五种生态类型都
开启。

布局 `starfold:default` 按上表顺序以权重 3、2、2、2、1、1、1、1 抽取这些类型，`density` 0.7，`star_gap` 98304，
`ring` 12288，每颗太阳一到两颗行星。`starfold:small` 使用相同的类型和权重，是一个有二到四颗额外太阳的有限宇宙。

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

## 恒星系

恒星系文件安排太阳的位置，并给每颗行星各自的圆轨道和自转。世界预设的生成器用 `"system": "<命名空间>:<名称>"` 引用它，
同时写 `"storage_layout": "six_face_square_v2"`，此时不能再有 `planets`、`stars` 或 `universe`。星系之外的宇宙写在星系
文件里，字段是 `beyond`。

### 字段

位置是**宇宙坐标**，单位是格。它不是调试界面上显示的方块坐标，也不是相对第一颗太阳的偏移。

| 字段 | 类型与范围 | 省略时 |
|---|---|---|
| `suns` | 至少一颗太阳的列表；行星总数 2–1600 | 必填 |
| `suns[].position` | 三个数字 `[x, y, z]`，单位格；最远能放到哪里见规则 5 | 必填 |
| `suns[].radius` | 数字，32–4096 格；母太阳最大 1024 | 必填 |
| `suns[].planets` | 至少一颗行星的列表；母太阳至少两颗 | 必填 |
| `suns[].planets[].type` | 星球类型 ID，或就地写出的完整类型 | 必填；未知 ID 会让世界无法加载 |
| `…size` | 面宽（格）：在类型范围内，16 的倍数 | 由类型和星球种子选定 |
| `…seed` | 64 位整数 | 由世界种子和这颗行星在文件里的位置推导 |
| `…name` | 1–64 个字符，不能全是空白，不能有控制字符 | 按存储顺序为 A、B、… Z、P26、P27 … |
| `…orbit` | 对象 | 必填 |
| `…orbit.radius` | 数字，1–10000000 格，并满足下面的规则 | 必填 |
| `…orbit.period` | 数字，12000–1000000000 tick（每秒 20 tick） | 必填 |
| `…orbit.phase` | 数字，弧度 | `0` |
| `…spin` | 对象 | **星球不自转** |
| `…spin.period` | 数字，24000–1000000000 tick | 提供 `spin` 时必填 |
| `…spin.tilt` | 数字，-180–180 **度** | `0` |
| `home` | 对象，从 0 开始计数 | `{"sun": 0, "planet": 0}` |
| `home.sun` / `home.planet` | 文件里的某颗太阳和它的某颗行星 | 各为 `0` |
| `beyond` | 布局 ID，或就地写出的完整布局 | **星系之外什么都没有**：只存在这里写下的行星 |

**轨道**是太阳所在水平面上的圆：在第 `t` 个 tick，角度为 `phase + 2π × t / period`，行星位于
`太阳位置 + [radius × sin(角度), 0, radius × cos(角度)]`。不支持偏心轨道和倾斜轨道。**自转**绕轴
`[sin(tilt), cos(tilt), 0]` 进行，在第 0 个 tick 处于未转动的姿态。使用手写恒星系的新世界，时钟从第 0 个 tick 开始。

**种子。** 没有写 `seed` 的行星，种子由世界种子和它在文件里的序号得出；序号按太阳的顺序、再按每颗太阳的行星顺序
依次计数。因此换一个世界种子只会改变没有写 `seed` 的行星，而改 `home` 不会改变任何种子。种子随世界保存。

### 规则

1. **母星有一颗伴星。** 母星与母太阳列表里第一颗其他行星必须使用相同的轨道半径和周期，相隔半圈：相位相差 π。这个
   共用的半径必须在 **6000–8192** 之间。π 请写成 `3.141592653589793`，不要用更短的近似值。两者的大小和自转可以不同，
   其余行星不受此限制。
2. **存储顺序是固定的。** 母星是 0 号，伴星是 1 号；然后是母太阳的其余行星，按书写顺序；最后是其他太阳和它们的
   行星，按书写顺序。默认名字按这些编号生成。
3. **行星之间保持距离。** 取 `r = size × 2 / π`（省略 `size` 时按类型的最大面宽计算），同一颗太阳的两颗行星，球心
   距离在任何时刻都不能小于 `r1 + r2 + 4352`。周期不同时，这意味着两条轨道的半径至少相差这么多。周期相同时，距离
   恒为 `sqrt((R1 - R2)² + 4 × R1 × R2 × sin²(Δphase / 2))`。同一条轨道上周期不同的两颗行星一定会被拒绝。
4. **太阳之间保持距离。** 轨道半径必须大于 `太阳半径 + r + 2176`。一颗太阳的势力范围是它的行星中最大的
   `轨道半径 + r + 2176`；两颗太阳的距离不能小于两者势力范围之和，无论相位如何。
5. **一切都要到得了。** 母太阳距原点不超过 100000 格。每颗太阳到原点的距离加上它的势力范围不超过 200000 格，这样
   不会有行星落在飞行到不了的地方。
6. **最多 1600 颗行星**，包括 `beyond` 增加的。`beyond` 生成的太阳会避开每颗手写太阳的势力范围，放不下时就少生成。
   使用 `beyond` 时，它可能生成的最外圈轨道的周期不能超过 1000000000 tick：
   `母星周期 × ((母星轨道半径 + (beyond.planets.max - 1) × beyond.ring) / 母星轨道半径)^1.5`。超过时，请缩短母星
   周期，或减小环距或行星数量。
7. 面宽必须满足类型的范围和深度规则，维度类型也必须装得下这个深度。

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
5. **方块、群系和结构仍然按 ID 查找。** 如果删掉的包定义了世界用到的群系、方块或维度类型，世界可能无法加载。修改
   群系的地物或结构集标签，会改变此后生成的内容。
6. **已经生成的区块不会被重写。**
7. **预设只在创建世界时起作用。** 它不是更新已有世界的办法。

## 代码里固定的限制

- **地形。** 噪声、洞穴、生态和模组自己的树木都由代码决定；类型只是选择算法及其参数。海平面是固定的，海是水，岩石
  星球没有海。
- **原版地物会被过滤。** 所有星球都跳过湖泊和原版矿物地物；生态星球还跳过放置树木的地物，因为模组自己种树。结构的
  起点离面的边缘至少 8 个区块。
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
