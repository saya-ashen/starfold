# 星海折越

[English](README.md)

Minecraft 1.21.1 NeoForge 模组：星球是由真实原版区块构成的封闭六面世界。走过面的边缘时不传送、不重载；多颗星球和恒星
处在同一个维度里，用 [Sable](https://modrinth.com/mod/sable) 建造的飞船在它们之间飞行。

**状态：alpha。** 目前以单人游戏为主，多人和与其他模组的兼容暂不是目标。版本之间不保证存档兼容，更新前请备份。

## 内容

- 星球的地形、方块实体、库存、碰撞和模拟都是普通的原版区块。近处地形按原版方式加载，跨面边缘也一样；视距之外由远景
  覆盖。
- 星球大小不同，深度随大小变化，有洞穴、矿物、群系和结构。
- 恒星系包含恒星、轨道、自转和昼夜，由数据包定义：见 [docs/DATAPACKS.md](docs/DATAPACKS.md)。
- 乘 Sable 飞船做太空航行：舵、推进器、导航观察镜、舷梯和飞行器停机坪方块。
- 自己造船离开地面，这是主要的玩法。体验包世界预设另外在出生点放了一艘现成的起始飞船，作为捷径。
- 每颗星球的八个顶角是把玩家挡在外面的折点。背后的世界观见 [docs/LORE_zh-CN.md](docs/LORE_zh-CN.md)。

## 运行要求

| | |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.247 或更新 |
| Sable | 2.0.5（必需） |
| Create、Create Aeronautics | 可选；体验包预设需要这两个 |
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
./gradlew nativeTest             # 较快的星球回归测试
./gradlew packagedContractTest   # 对照 Minecraft 检查已构建 JAR 的每个 mixin
./gradlew runClient              # 开发客户端，游戏目录为 run/
```

开发客户端用的可选模组放在 `run/mods`，该目录不纳入版本管理。检查这些模组物品 ID 的测试在目录不存在时跳过这项检查。

## 仓库结构

- `src/main`、`src/test`：模组及其测试。
- `docs/DATAPACKS.md`：数据包接口；测试会读取其中的示例。
- `tools/ship`：起始飞船结构和方块资源的生成器。
- `tools/probe`：开发客户端的脚本化游戏内检查，见 [tools/probe/README.md](tools/probe/README.md)。启动脚本按维护者的
  Linux 环境编写。
- `tools/rename_project.py`：根据 `project-names.json` 一次性重命名项目（ID、包名、路径）。

早期原型及其版本说明保留在 `archive/*` 分支。

## 许可

MIT，见 [LICENSE](LICENSE)。
