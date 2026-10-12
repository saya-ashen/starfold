# 星海折越

[English](README.md)

Minecraft 1.21.1 NeoForge 模组：星球是由真实原版区块构成的封闭六面世界。走过面的边缘时不传送、不重载；多颗星球和恒星
处在同一个维度里，在它们之间飞行没有加载画面：开一艘用机械动力（Create）和机械动力：航空学（Create Aeronautics）造的飞船，或者背上推进背包。

**状态：alpha。** 目前以单人游戏为主，多人和与其他模组的兼容暂不是目标。版本之间不保证存档兼容，更新前请备份。

## 内容

- 星球的地形、方块实体、库存、碰撞和模拟都是普通的原版区块。近处地形按原版方式加载，跨面边缘也一样；视距之外由远景
  覆盖。
- 星球大小不同，深度随大小变化，有洞穴、矿物、群系和结构。
- 恒星系包含恒星、轨道、自转和昼夜，由数据包定义：见 [docs/DATAPACKS.md](docs/DATAPACKS.md)。
- 为用机械动力和机械动力：航空学建造的飞船提供太空航行：本模组加入飞船控制器、推进器、加力器、陀螺仪、导航观察镜、舷梯和停机位。不装这两个模组时，仍可以靠推进背包独自飞进太空。
- 离开地面要靠自己：造一艘船，或者做一个推进背包。体验包世界预设另外在出生点放了一艘现成的起始飞船，作为捷径。
- 每颗星球的八个顶角是把玩家挡在外面的折点。背后的世界观见 [docs/LORE_zh-CN.md](docs/LORE_zh-CN.md)。

## 运行要求

| | |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.247 或更新 |
| Sable | 2.0.5 或 2.0.6（必需） |
| 机械动力（Create）、机械动力：航空学（Create Aeronautics） | 造船和体验包预设需要 |
| Immersive Aircraft | 可选；给起始飞船加一架飞行器 |

星球渲染需要原版渲染器，不支持光影包。模组声明与 `gravity_changer` 不兼容。

## 游玩

新建世界并选择世界类型：

- `starfold_pack:experience`：体验包，带起始飞船（需要 Create 和 Create Aeronautics）。
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

- `src/main`、`src/test`：模组及其测试。代码位于 `dev.starfold`，每个子系统一个包（测试与被测代码同包）：
  - `geometry`：立方体表面、各面与接缝的纯数学，不含 Minecraft 类型。
  - `chart`：观察者从地面到轨道看到的星球投影。
  - `planet`：星球的形状、存储、星系与宇宙，以及星核和折点。
  - `worldgen`：地形、群系、矿物与区块生成器。
  - `sky`：太阳、轨道、时钟、昼夜与天气。
  - `ground`：在一个面上生活的服务端部分：接缝、区块视图、按观察者改写的数据包、光照。
  - `render`：客户端的星球远景、其他星球与着色器补丁。
  - `flight`：个人飞行、其 HUD，以及供其他模组授予的飞行许可。
  - `space`：星球之间的太空区域。
  - `ship`：与 Sable 船体有关的一切：舵、推进器、着陆、登船梯、停机位、起始飞船。
  - `waypoint`：标记点及其导航。
  - `dev`：开发探针，正常游玩时不生效。
  - `mixin`：全部 mixin。
- `docs/DATAPACKS.md`：数据包接口；测试会读取其中的示例。
- `tools/ship`：起始飞船结构和方块资源的生成器。
- `tools/probe`：开发客户端的脚本化游戏内检查，见 [tools/probe/README.md](tools/probe/README.md)。启动脚本按维护者的
  Linux 环境编写。
- `tools/rename_project.py`：根据 `project-names.json` 一次性重命名项目（ID、包名、路径）。

早期原型及其版本说明保留在 `archive/*` 分支。

## 许可

MIT，见 [LICENSE](LICENSE)。
