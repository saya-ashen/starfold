# 星海折越

[English](README.md)

Minecraft 1.21.1 NeoForge 模组：星球是由真实原版区块构成的封闭六面世界。走过面的边缘时不传送、不重载；多颗星球和恒星
处在同一个维度里，在它们之间飞行没有加载画面：开一艘用机械动力（Create）和机械动力：航空学（Create Aeronautics）造的飞船，或者背上推进背包。

**状态：alpha。** 目前以单人游戏为主，多人和与其他模组的兼容暂不是目标。当前预发布版本：**0.3.0-alpha.1 — Starward「向星而行」**。**不支持 0.2.0 存档，请新建世界，并保留旧世界备份。**

## 内容

- 星球的地形、方块实体、库存、碰撞和模拟都是普通的原版区块。近处地形按原版方式加载，跨面边缘也一样；视距之外由远景
  覆盖。
- 星球大小不同，深度随大小变化，有洞穴、矿物、群系和结构。
- 恒星系包含恒星、轨道、自转和昼夜，由数据包定义：见 [docs/DATAPACKS_zh-CN.md](docs/DATAPACKS_zh-CN.md)。
- 为用机械动力和机械动力：航空学建造的飞船提供太空航行：本模组加入飞船控制器、推进器、加力器、陀螺仪、导航观察镜、舷梯和停机位。不装这两个模组时，仍可以靠推进背包独自飞进太空。
- 离开地面要靠自己：造一艘船，或者做一个推进背包。安装 Worlds 和 Extras 后，体验世界预设会在出生点放置起始飞船，作为捷径。
- 每颗星球的八个顶角是把玩家挡在外面的折点。背后的世界观见 [docs/LORE_zh-CN.md](docs/LORE_zh-CN.md)。

- Atlas 提供持久化星球测绘、地面地图、三维星系／星际地图和飞船追踪。
- 飞船燃料参与质量与剩余 Δv 计算；气态巨行星采集和耐压外壳扩展了航行进度。
- 可选 Biotech 提供血肉虫巢星球、血液和宿主遭遇。

## 运行要求

| | |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.247 或更新 |
| Sable | 2.0.5 或 2.0.6（必需） |
| 机械动力（Create）、机械动力：航空学（Create Aeronautics） | 造船和使用 Extras 需要 |
| Immersive Aircraft | 可选；给起始飞船加一架飞行器 |

安装 `starfold_worlds` 获得体验星球和预设；可选安装 `starfold_extras` 获得初始飞船、停机坪和指南书（需要 Worlds、Create 和 Create Aeronautics）。
`starfold_materials` 提供轻质铝合金外壳板与重型锇合金耐压壳（完整方块、半砖）；金属原料由其他模组通过通用标签提供。数值见[暂定材料平衡](materials/BALANCE.md)。

`starfold_atlas` 提供地图和飞船追踪。`starfold_biotech` 需要 Worlds 和固定版本 GeckoLib 4.8.2，其平衡数值仍为暂定。`modpack/` 是生存整合包配置源码，尚未完成完整生存流程验收。

星球渲染支持原版渲染器和 Sodium 0.8。Iris 在未启用光影包时可用；启用 Iris 光影包，或使用 Embeddium、Canvas 时，本版本会停用星球地形投影和远景。模组声明与 `gravity_changer` 不兼容。

## 游玩

新建世界并选择世界类型：

- `starfold_worlds:experience`：体验世界；安装 `starfold_extras` 后附带起始飞船。
- `starfold:native_universe`（“星海折越：随机宇宙”）：不含体验包的随机宇宙。
- 其余 `native_*` 预设是开发时使用的小型场景，不出现在创建世界界面里；服务器可以用 `level-type` 按 id 选用。

星球相关命令在 `/starfold` 下。

## 构建

需要 JDK 21；通过 wrapper 运行 Gradle。

```
./gradlew build                  # JAR 在 build/libs，并运行全部测试
./gradlew packagedContractTest   # 对照 Minecraft 检查已构建 JAR 的每个 mixin
./gradlew runClient              # 开发客户端，游戏目录为 run/
```

Java 源码用 google-java-format 1.28.0 的 AOSP 风格格式化
（`--aosp --skip-reflowing-long-strings --skip-javadoc-formatting`），手动运行，不接入构建。

开发客户端用的可选模组放在 `run/mods`，该目录不纳入版本管理。检查这些模组物品 ID 的测试在目录不存在时跳过这项检查。

## 仓库结构

- `src/main`、`src/test`：核心 `starfold` 模组及其测试。代码位于 `dev.starfold`，每个子系统一个包（测试与被测代码同包）：
  - `geometry`：立方体表面、各面与接缝的纯数学，不含 Minecraft 类型。
  - `network`：共享的数据包注册与按运行端分离的接收器。
  - `chart`：星球形状、天体轨道与太阳的计算，以及观察者从地面到轨道看到的星球投影。
  - `planet`：星球存储与身份、星核与折点几何，以及纯计算的飞行动力学内核。
  - `worldgen`：地形算法：高度、岩石、洞穴、矿物、群系与装饰。
  - `universe`：数据包定义的内容（星球类型、恒星系、宇宙布局）、生成出的宇宙，以及把星系随世界保存的区块生成器。
  - `sky`：保存的天体运动、时钟、昼夜与天气。
  - `ground`：在一个面上生活的服务端部分：接缝、区块视图、观察者与飞行数据包、光照及生命周期钩子。
  - `render`：客户端的星球远景、地形显示过渡与着色器补丁。
  - `flight`：个人飞行、其 HUD、供其他模组授予的飞行许可，以及折点对玩家的效果。
  - `space`：星球之间的太空区域。
  - `ship`：Sable 船体的舵、推进器、燃料、着陆、登船梯、停机位、气压损坏与船体存档记录。
  - `waypoint`：标记点及其导航。
  - `api`：供客户端模块使用的测绘与星系地图接口。
  - `storage`：共享的区块地址键。
  - `mixin`：核心 mixin。
- `settings.gradle` 中各子项目的代码与资源位于 `<模块>/src/main`，测试（如有）位于 `<模块>/src/test`：

  | 模块 | 职责与源码路径 |
  |---|---|
  | `atlas` | 星球测绘及星图、星系图、地面地图：`atlas/src/main/java/dev/starfold/atlas` |
  | `devtools` | 按需启用的客户端 CPU/GPU 性能分析：`devtools/src/main/java/dev/starfold/devtools` |
  | `probes` | 开发探针、被动测量及探针 mixin：`probes/src/main/java/dev/starfold/{dev,probe,probes}`；仅供开发 |
  | `worlds` | 体验星球、群系与世界预设：`worlds/src/main/java/dev/starfold/worlds` 及 `worlds/src/main/resources` |
  | `extras` | 可选起始飞船、停机坪与指南书：`extras/src/main/java/dev/starfold/extras`；飞船结构位于 `extras/src/main/resources/data/starfold_extras/structure` |
  | `materials` | 铝原料、外壳板与耐压壳组件：`materials/src/main/java/dev/starfold/materials` 及 `materials/src/main/resources` |
  | `biotech` | 血肉虫巢地形、有机材料与宿主遭遇：`biotech/src/main/java/dev/starfold/biotech` 及 `biotech/src/main/resources` |

- `docs/DATAPACKS_zh-CN.md`：给数据包作者的参考，另有英文版 `docs/DATAPACKS.md`；测试会读取其中的示例。
- `docs/DEVELOPMENT.md`：架构、验证命令与当前验收边界。
- `docs/ROADMAP_zh-CN.md`：计划做什么、想到了什么程度，另有英文版 `docs/ROADMAP.md`。
- `tools/ship`：起始飞船结构和方块资源的生成器。
- `tools/probe`：开发客户端的脚本化游戏内检查，见 [tools/probe/README.md](tools/probe/README.md)。启动脚本按维护者的
  Linux 环境编写。
- `tools/rename_project.py`：根据 `project-names.json` 一次性重命名项目（ID、包名、路径）。


## 许可

MIT，见 [LICENSE](LICENSE)。
