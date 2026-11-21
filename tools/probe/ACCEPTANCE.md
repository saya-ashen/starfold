# Escape Velocity 运行验收

## 运行器和现成剧本

从仓库根目录操作。运行器使用 JDK 21、Gradle 缓存和 `prepare-runtime.py` 验证的整合包依赖，实际启动客户端和独立服务端。它使用当前源码和 `starfold_probes`；ZIP 安装验收另用实际导出的整合包。每次执行创建新的 `build/` 子目录和存档，服务器只监听 `127.0.0.1`。同一端口同一时间只跑一个实例。

```bash
python3 -B tools/probe/acceptance.py startup --list
python3 -B tools/probe/acceptance.py distillation --list
python3 -B -m unittest discover -s tools/probe -p 'test_*.py'
```

只在本轮已获用户明确授权运行对应场景时添加 `--run`。例如，用现有 X11 显示运行两次登录冒烟：

```bash
python3 -B tools/probe/acceptance.py startup --run \
  --output build/acceptance/startup-01 --port 25589
```

可用 `--xvfb /path/to/Xvfb` 自动分配隔离显示，配合 `--ffmpeg /path/to/ffmpeg` 录制 H.264 MP4，默认 960 像素宽、10 fps。取材时可用 `--record-fps 60 --record-width 1280 --record-crf 18`。FFmpeg 需支持 `x11grab` 和 `libx264`。本仓库的 Nix 环境可传 `--client-prefix steam-run`，并按实际驱动设置 `LD_LIBRARY_PATH`；服务器仍用当前 JDK。录制和探针截图随运行保存在隔离目录。现有 X11 显示由调用方管理。需要更长的单段运行时间时用 `--client-timeout`（秒）。测试不依赖 Mesa 扩展开关。

| 场景 | 剧本与通过条件 |
|---|---|
| `startup` | 两次运行 `acceptance-join.txt`。两次均验证进入世界，客户端收到 ACK，服务端也记录了该次登录和 ACK。 |
| `workshop` | 在新存档运行 `stage2-industry-workshop.txt`。在固定高度的测试平台放置供料夹具，验证压机、搅拌和精密件的五轮加工，要求所有 `check` 和服务端的产物回执。 |
| `distillation` | 在另一新存档运行 `stage2-industry-boundary.txt`。供给两批各 1,000 mB 原油，验证实际蒸馏输出，存盘。然后用同一存档启动新服务端 JVM，执行 `stage2-industry-rejoin.txt`，核对六个物理罐。此场景验证加工与持久化，原油来自测试夹具。 |
| `oil` | 供给受控储层，检查抽油守恒、满罐与堵塞、暂停存盘和新服务端 JVM 的完整 NBT 读回。 |
| `diesel-flight` | 在 Hearth 面内部署命令建造的小船，预装柴油，用玩家输入起飞、下降，核对低速持续接触和实际船罐消耗。表面类型由 `PROBE TRIP` 回执记录。 |
| `drive-crafting` | 提供 `--fixture-script tools/probe/acceptance-drive-fixture.js`。机械动力合成器实际产出星际驱动，注液器用夹具供给的氘产出氘桶。 |
| `hydrogen` | 提供 `--fixture-script tools/probe/acceptance-hydrogen-fixture.js`。夹具给两台机器供电、向分离器供水，并转移分离器实际产出的氢。冷凝器输出和服务端回执须同时达到 100 和 200 mB。 |
| `host` | 提供 `--fixture-script tools/probe/acceptance-host-fixture.js`。在远离折点的天然巢穴触发 Host，施加可控伤害验证阶段切换和掉落，再在新服务端 JVM 核对冷却期与掉落留存。 |

`acceptance-fleck-interior.txt` 使用带 Extras 的开发实例。进入 Fleck 地表参考系后，先朝面中心移动，通过整船离面边的余量检查再下降。接地、舷梯和最终船体余量都是通过条件。场景录像的落区选择应避开折点。

`result.json` 记录会话顺序、检查失败和运行错误。`server-*.log`、`client-*.log`、`staging.json` 和可选的 MP4 提供原始证据。脚本结束、客户端 ACK、服务端登录和 ACK、每个命名检查、场景产物回执以及客户端与服务端正常退出都要满足。失败时保留存档和日志。审阅日志时按失败步骤定位 WARN/ERROR，保留相关栈；启动期通用告警单独归类。当前运行器尚未采集 MSPT 时间序列。

## 主线剧本与断言

[Copper Swift 连续验收设计](CONTINUOUS-SURVIVAL.md) 定义单次启动、同一存档、一次计划内重启和全程录像的阶段检查点。

独立切片验证加工、操控和持久化，阶段 2 → 4 还需串起同档产物交接。切片允许在开场声明并发放前置材料。跨阶段交接使用上一段真实产物，每段记录夹具、坐标、版本、命令供料和真实产出。

| 阶段 | 场景 | 通过条件与下一段输入 |
|---|---|---|
| 1 | 新档生存、指南与首个任务 | 默认生存预设发放指南，打开首章完成一个可领取任务并核对背包奖励。把玩家和基地位置写入记录。 |
| 2 | 工坊与燃料 | 复用已有两个切片，另补自然抽油→蒸馏→船罐的交接。以实际罐读数证明连续两批、输入消耗和装船；保留精密件和油料给下一段。 |
| 3 | 组装并试飞 | 开场可提供船体建材，用阶段 2 的精密件和油料完成首船。记录船 UUID、舵、罐和货舱。断言接管、提升、降落、离舵、重新登船，记录燃油差。 |
| 4 | 首次跨星往返 | 实际飞到 Fleck 并接地。通过玩家采矿得到至少 8 份原锇并入舱。保存关停后用 Atlas 找回原船并接管，飞回 Hearth 卸货，核对 UUID 和货物数量，再用卸下的原锇开启 Mekanism 产出首批基础电路。`trip.txt` 可参考航行操作，起始船和途中到达证据各自只作诊断。 |
| 5 | 液氢补给 | 让电解分离器和旋转冷凝器实际运行，核对两批液氢、重启后液位及推进器供油。分开记录发动机、燃料与运行区域。 |
| 6 | Amber 和 Flesh | 采得实际氘产物；自然触发 Host，确认战斗阶段和实际神经节掉落，重启后核对遭遇状态。产物交给下一段装配。 |
| 7 | 星际远征 | 用阶段 6 产物组装星际引擎，飞往外星系、着陆建据点、保存重开，再用原船带货返航。核对任务状态、船 UUID 和两端货物。 |

阶段 2 → 4 的 Copper Swift 实测从包含天然油井和分馏成品的生存档副本开始。命令发放精密机构并搭建船体，测试脚本用 Create 流体接口将柴油和汽油各 1,000 mB 转入船罐，玩家通过舵完成组装。`build/acceptance/survival-loop/` 保留逐段存档、脚本、录像和日志。同一船 UUID `0d26c2f9-6c1c-4a40-9e27-8537973253a8` 经普通升力升空，存档读回 908 mB 柴油和 1,000 mB 汽油。中途航向修正后进入 Fleck 地表参考系，仍持有舵权。`fleck-08` 在整船离面边大于 104 格时降到约 72 格净空。`fleck-09` 勘测到 `(65730,153,16593)` 附近一块 13×13 天然干地，地表高差 2 格，中心距折点约 399 格。`fleck-10` 转向至目标航向时产生明显平移，后续余量断言超时，脚本在接地前停止。后续在远离折点的天然坡地直接下降，舷梯实际展开约 6 格。修复 `SLOPE` 舷梯误报后，同址重测确认规划结果与实际展开状态一致。`fleck-exit-02` 验证约 1.08 格净空时两次按 R 确认离舵，`fleck-exit-03` 验证玩家抵达天然地面并离开原船追踪。`fleck-osmium-01` 使用生存挖掘拿到 8 份天然原锇，`fleck-ore-out-01` 将原锇带回地表。铁镐与井梯由命令提供。`fleck-load-08` 通过原版货桶界面装入全部 8 份，`fleck-offship-04` 再次核对货桶、确认玩家在地面且脱离船体追踪后保存。重启后 Atlas 选中原 UUID。坡上停泊期间船体持续位移并收梯，`fleck-recover-03` 绕行至山脊后记录了受阻路点，`fleck-recover-04` 从该副本再启动，绕过高差后跳上原船，经右键重新接管舵，货桶仍有 8 份原锇。`fleck-return-launch-01` 带货离开 Fleck，`hearth-return-04` 缩短推进后滑行进入炉心地表参考系，货桶仍有 8 份原锇，存档剩 404 mB 汽油。`hearth-descent-01` 在整船离面边至少 96 格时降至低空。`hearth-unload-02` 实测干地接触、离舵和原版货桶卸货，原锇全部进入玩家背包，货桶清空。该着陆点的勘测距折点约 288 格。接触时船体接近静止，舷梯未展开；玩家记录到约 4.8 点生命值，平稳着陆与 Fleck 严格接地仍待复核。`hearth-circuits-05` 在该着陆存档副本中以测试提供的炉、灌注机、电力和红石完成 2 个基础控制电路，6 份原锇留在玩家背包。完整阶段 2 → 4 同存档链已覆盖 Fleck 采集、装货、离船保存、重启找船、重新接管、返航、地表卸货和基础电路生产。

## 补通用交互和离线检查

- Blockpilot 留作独立兼容性试验。固定对应 NeoForge 和 Minecraft 版本，在同一隔离包的副本中完成一个真实机器操作：打开容器、确认菜单和槽位、放入输入，然后在服务端核对实际产物。FTB Quests、Atlas 和 Helm 用各自的正常界面和按键路径。通过这一试验后再决定是否用 MCP 补交互。当前运行器无需第三方桥接 Mod。
- 当前任务配置可用 `python3 -B tools/modpack/check-modpack.py` 检查。下一步需要从运行实例导出实际加载的配方、标签和任务条件，再按可达集合迭代扫描。加工步骤要保留流体、能量、备选材料和任务门槛。可达性疑点由运行剧本确认。
- 视觉人工复核集中在低空操控、HUD 与教程、Atlas 找船、跨参考系画面和复杂界面布局。录像可以定点回看；运行结论使用状态断言。
