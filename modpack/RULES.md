# 现行整合包规则

本文件按当前受版本管理的资源、配方和任务整理。数值仍属暂定；旧实测仅见 README 的历史说明，不代表当前生存流程或燃料平衡验收。修改规则须同步资源与离线合同，不能用旧归档覆盖当前资源。

- Minecraft 1.21.1 / NeoForge 21.1.247；外部模组版本由 `mods/*.pw.toml` 固定。Aeronautics 包含 Offroad/Simulated，Sable 单独固定；不包含 BOMD。GeckoLib 固定 4.8.2（文件 7023453）。
- 本地发行组成是 core、Worlds、Atlas、Materials、Biotech 五个 JAR。Extras 不加入这个从零开始的生存包；probes/devtools 为开发工具。未发布的本地 JAR 不能伪造 CurseForge 元数据，也不能混进 overrides。
- `starfold_worlds:experience` 为新世界预设，无出生舰/平台、下界、末地。单太阳体验星系由 Worlds 提供，Biotech 在 Amber 最外侧追加 Flesh（髓原）并扩展宇宙类型列表；KubeJS 不覆盖这些星系/宇宙定义。
- 12 个 Worlds 类型的 KubeJS 覆盖只修改矿表。母星及其余宜居类型没有锇或钻石；岩质/小行星有锇和深层钻石，晶体星也有更稀少的深层钻石。钻石深度 0.65–1，稀有度分别 0.99 / 0.995。沙漠唯一放置 Materials 铝土矿，Materials 自身不注册生成。
- Create 支撑初始机械工业和导航镜；导航镜在首次起飞之前，不需要锇。无下界的烈焰人燃烧室和玫瑰石英配方避免启动死锁。外星锇开启 Mekanism，精密镜仍需高级电路与萤石。
- 柴油/汽油、氢、氘的冲量依次为 940、1880、23500，质量均为 47 / 1000 mB。只有氘可用于星际推进；离开母星系后的推力同时要求同船星际引擎和氘，不阻止惯性滑行。具体燃料定义在 `kubejs/data/escape_velocity/starfold/thruster_fuel/`。
- 氘来自 Amber 气压场中的采集口，参考压力 0.5 时产率 10 mB/tick；重水分离配方禁用，旋转转换只转换已有氘。普通方块耐压阈值 0.5，锇耐压壳 0.85；按各方块判定，不是整舰护盾。
- 宿主遭遇掉落神经节。星际引擎使用 Create 3×4 机械合成：四块锇耐压壳、四块精英电路、一桶氘、一个宿主神经节。精确 recipe 以 `kubejs/server_scripts/progression.js` 为准。
- 九章共 86 个任务，双语键一致，原 quest/task/reward ID 不变。第七章旧 ID `1000000000070500`–`1000000000070700` 保持退役；新 Flesh 记录使用后续 ID。引擎任务显式依赖击杀宿主记录。
- Flesh 着陆、进入巢坑、装满 8000 mB 储罐和离开母星系是明确的手动记录，不冒充地点/液位遥测。宿主用 kill 任务，现有飞行 advancement 用既有事件验证。

## 维护与验收

从仓库根执行 `python3 tools/modpack/check-modpack.py` 检查资源、pins、配方、任务、索引和发行清单；`--with-jars` 才读取已缓存外部 JAR 进行哈希、标签、配方与依赖闭包检查。两种模式均不下载、不运行 Minecraft 或外部 JAR。

`python3 tools/modpack/generate-quests.py --check` 在临时目录复现并逐字节比较任务文件；`--output <directory>` 才生成到指定位置。`inventory.py` 默认只使用缓存并写 `build/modpack/ids.json`，下载必须显式 `--fetch`。生成审计、下载和导出保留在 build，脚本保留在 tools，规则不得依赖个人归档路径。

Packwiz 排除本文件、README、ID 清单、Python 工具和本地 JAR。所有跟踪的 quests/lang、recipe 与生成资源在本轮恢复中保持原样。离线通过不证明游戏兼容、油田可用性、宿主生成或生存往返完成。
