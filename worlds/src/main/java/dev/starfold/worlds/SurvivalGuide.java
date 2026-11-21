package dev.starfold.worlds;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.List;

/** Pack-opted-in survival help. No client classes or optional content dependencies. */
public final class SurvivalGuide {
    private static final TagKey<Item> ENABLED =
            TagKey.create(
                    Registries.ITEM,
                    ResourceLocation.fromNamespaceAndPath("starfold", "survival_guide_enabled"));
    private static final String DATA = "starfold:survival_guide";
    private static final String RECEIVED = "received";
    private static final String NEXT_REQUEST = "next_request_tick";
    private static final long COOLDOWN_TICKS = 20 * 30;

    public static void initialize() {
        NeoForge.EVENT_BUS.addListener(
                (PlayerEvent.PlayerLoggedInEvent event) -> firstDelivery(event.getEntity()));
        // A player can log in while still on the death screen. Wait until their new
        // inventory exists; never record receipt in an inventory that respawn discards.
        NeoForge.EVENT_BUS.addListener(
                (PlayerEvent.PlayerRespawnEvent event) -> firstDelivery(event.getEntity()));
        NeoForge.EVENT_BUS.addListener(
                (PlayerEvent.Clone event) -> {
                    // NeoForge already carries PlayerPersisted across respawns. Copy only our own
                    // compound as well, avoiding aliases and preserving other mods' persisted keys.
                    CompoundTag old =
                            event.getOriginal()
                                    .getPersistentData()
                                    .getCompound(Player.PERSISTED_NBT_TAG);
                    if (old.contains(DATA, Tag.TAG_COMPOUND)) {
                        CompoundTag target = persisted(event.getEntity());
                        target.put(DATA, old.getCompound(DATA).copy());
                    }
                });
        NeoForge.EVENT_BUS.addListener(
                (RegisterCommandsEvent event) ->
                        event.getDispatcher()
                                .register(
                                        Commands.literal("starfoldguide")
                                                .executes(
                                                        context ->
                                                                replacement(
                                                                        context.getSource(), null))
                                                .then(
                                                        Commands.literal("zh_cn")
                                                                .executes(
                                                                        context ->
                                                                                replacement(
                                                                                        context
                                                                                                .getSource(),
                                                                                        "zh_cn")))
                                                .then(
                                                        Commands.literal("en_us")
                                                                .executes(
                                                                        context ->
                                                                                replacement(
                                                                                        context
                                                                                                .getSource(),
                                                                                        "en_us")))));
    }

    private static void firstDelivery(Player entity) {
        if (entity instanceof ServerPlayer player
                && player.isAlive()
                && enabled()
                && !receipt(player).getBoolean(RECEIVED)) {
            deliver(player, language(player));
        }
    }

    private static boolean enabled() {
        // Missing/empty tag is deliberately false, including Worlds alone and Extras installs.
        return Items.WRITTEN_BOOK.builtInRegistryHolder().is(ENABLED);
    }

    private static String language(ServerPlayer player) {
        return "zh_cn".equals(player.clientInformation().language()) ? "zh_cn" : "en_us";
    }

    private static CompoundTag persisted(Player player) {
        CompoundTag root = player.getPersistentData();
        if (!root.contains(Player.PERSISTED_NBT_TAG, Tag.TAG_COMPOUND)) {
            root.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
        }
        return root.getCompound(Player.PERSISTED_NBT_TAG);
    }

    private static CompoundTag receipt(Player player) {
        CompoundTag root = persisted(player);
        if (!root.contains(DATA, Tag.TAG_COMPOUND)) root.put(DATA, new CompoundTag());
        return root.getCompound(DATA);
    }

    private static long now(ServerPlayer player) {
        // One persistent server clock across dimensions, reconnects, deaths and restarts.
        return player.server.overworld().getGameTime();
    }

    private static int replacement(CommandSourceStack source, String requestedLanguage)
            throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        String language = requestedLanguage == null ? language(player) : requestedLanguage;
        if (!enabled()) {
            tell(player, language, "此整合包未启用生存指南。", "This pack has not enabled the survival guide.");
            return 0;
        }
        if (!player.isAlive()) {
            tell(player, language, "请先重生，再补领指南。", "Please respawn before requesting a guide.");
            return 0;
        }
        CompoundTag data = receipt(player);
        long tick = now(player);
        if (tick < data.getLong(NEXT_REQUEST)) {
            tell(
                    player,
                    language,
                    "请稍后再补领（冷却 30 秒游戏时间）。",
                    "Please wait before requesting another guide (30 seconds of game time).");
            return 0;
        }
        // Throttle failed full-inventory requests too. Failure never marks first receipt.
        data.putLong(NEXT_REQUEST, tick + COOLDOWN_TICKS);
        return deliver(player, language) ? 1 : 0;
    }

    private static boolean deliver(ServerPlayer player, String language) {
        int slot = player.getInventory().getFreeSlot();
        if (slot < 0) {
            tell(
                    player,
                    language,
                    "生存指南：请空出一个背包栏位，用 /starfoldguide 补领；首次发放也会在下次登录重试。",
                    "Guide: free one inventory slot and use /starfoldguide. First delivery also retries next login.");
            return false;
        }
        // Insert into an empty main-inventory slot directly. Inventory.add can silently
        // consume overflow in creative mode; never drop a first guide into the world.
        player.getInventory().setItem(slot, book(language));
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        CompoundTag data = receipt(player);
        data.putBoolean(RECEIVED, true);
        data.putLong(NEXT_REQUEST, now(player) + COOLDOWN_TICKS);
        tell(
                player,
                language,
                "已收到第一阶段生存指南。遗失后可用 /starfoldguide 免费补领。",
                "Stage 1 survival guide received. Use /starfoldguide for a free replacement.");
        return true;
    }

    private static void tell(ServerPlayer player, String language, String zh, String en) {
        player.sendSystemMessage(Component.literal("zh_cn".equals(language) ? zh : en));
    }

    private static ItemStack book(String language) {
        boolean chinese = "zh_cn".equals(language);
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(
                DataComponents.WRITTEN_BOOK_CONTENT,
                new WrittenBookContent(
                        Filterable.passThrough(
                                chinese ? "生存指南 · 第一阶段" : "Survival Guide · Stage 1"),
                        "Starfold",
                        0,
                        (chinese ? ZH : EN)
                                .stream()
                                        .map(
                                                page ->
                                                        Filterable.<Component>passThrough(
                                                                Component.literal(page)))
                                        .toList(),
                        true));
        return book;
    }

    // Literal pages are fixed at delivery; /starfoldguide can request the other language.
    // Short pages deliberately leave room for vanilla's 114-pixel book text area.
    private static final List<String> ZH =
            List.of(
                    "1 · 从零开始\n第一阶段：在母星站稳脚跟。默认沿用原版生存设置，没有墓碑模组，也没有免费出生飞船。死亡可能遗失物品；先安家，再探矿。",
                    "2 · 六面星球\n星球六个面连续相接，可以沿表面越过边缘继续前进，重力方向随所在面变化。沿途留意地形、落差与回家路线；不要把面边缘当作世界尽头。",
                    "3 · 第一晚\n先收集木头、石头、食物和羊毛。造工具、工作台、熔炉与床；在稳定地面放床并睡一次，留出重生空间。天黑前建好庇护所并照亮周围。",
                    "4 · 稳定补给\n保留食物与安全水源，逐步建立农田。用煤做火把、烧矿；找不到煤时可烧原木制木炭。外出带食物、火把、方块和备用工具，家里留一份。",
                    "5 · 母星矿物\n先找铁、铜、煤和浅层安山岩。金和红石偏深层，别只在地表寻找。带合适的镐，沿安全阶梯下挖，避免直上直下。母星不产锇；第一阶段不需要它。",
                    "6 · 地图与回家\nAtlas 默认按 M 打开；若改过键，在选项→控制中查看 Starfold Atlas。给家、安全入口和矿点设私人航点。航点用于定位、导航，不提供传送。",
                    "7 · 任务与配方\n打开背包，点击侧边栏的 FTB Quests 任务书图标查看任务。JEI 中悬停物品：默认 R 看配方，U 看用途；可在控制设置中改键。制作前核对当前配方。",
                    "8 · 连锁挖掘\n选项→控制→按键绑定中找到 FTB Ultimine，设置激活与形状切换键。拿合适工具，按住激活键查看轮廓，再挖选中的方块；先确认范围内没有家。",
                    "9 · 连挖有代价\n本包 Ultimine 最多一次 32 格，要求工具且工具必须适用，会消耗耐久和饥饿值。带足食物，注意工具耐久；连挖不是免费采矿，也不能替代照明和安全通道。",
                    "10 · 制作钻机\n固定采矿平台需要：4 铁锭、2 铜锭、1 铁镐、1 熔炉、1 拉杆。不需要 Create 或锇。先在 JEI 查采矿平台，按配方摆放。",
                    "11 · 九宫格摆法\n上排：铁锭／铁镐／铁锭\n中排：铜锭／熔炉／铜锭\n下排：铁锭／拉杆／铁锭\n\n把平台放在母星稳定地面，避开自己的房屋、箱子和道路。",
                    "12 · 先扫描\n右键平台打开界面，放入燃料并开始扫描。范围是平台下方 5×5，深度最多 512 格。越界、核心或其他障碍可能中止工作；先看状态，不要盲目等候。",
                    "13 · 扫描燃料\n完整扫描约需 25,600 FE。先备 2 个煤或木炭可覆盖扫描预算；燃料会逐步转成能量。实际挖掘还要额外供能，扫描用煤不是整次开采的总成本。",
                    "14 · 开采与输出\n看完扫描结果再开始挖掘。定期取走输出；输出栏满时平台等待，不会先破坏方块再丢弃产物。清空栏位并补充燃料，检查状态后继续。",
                    "15 · 固定作业\n平台是母星上的固定设备，不能装上飞船当移动矿机。保持所有者在线、工作区块加载；不支持离线或远程开采。离开前暂停，返回后检查燃料、输出和阻挡。",
                    "16 · 私人探矿记录\n已完成扫描的矿点可在 Atlas 查看，记录属于扫描者。它是一次扫描的快照，不保证矿仍未被挖走。私人航点帮助返回；地图与探矿记录都不提供传送。",
                    "17 · 下一阶段\n床、补给、照明、铁工具与稳定采矿齐备后，再推进 Create：安山合金、基础动力、压片等。先建设母星车间；不要为第一阶段去寻找外星锇。按任务和 JEI 继续。",
                    "18 · 免费补领\n遗失指南无需 OP：\n/starfoldguide\n按当前客户端语言补领。\n/starfoldguide zh_cn\n/starfoldguide en_us\n可指定语言。留一个空栏位，每次请求冷却 30 秒游戏时间。");

    private static final List<String> EN =
            List.of(
                    "1 · Start from zero\nStage 1: settle on your home planet. Default survival settings follow vanilla. No gravestone mod or free starter ship. Death can cost your items; make a safe home before exploring.",
                    "2 · Six connected faces\nAll six planet faces join continuously. Walk across an edge to continue along the surface; gravity follows your face. Watch drops and terrain, and keep a route home.",
                    "3 · Before night\nGather wood, stone, food and wool. Make tools, a crafting table, furnace and bed. Sleep in a bed on stable ground with respawn space. Build shelter and light the area before night.",
                    "4 · Supplies\nKeep food and safe water nearby; start a farm. Coal fuels smelting and torches. Smelt logs into charcoal if coal is scarce. Carry food, torches, blocks and spare tools; leave supplies at home.",
                    "5 · Home ores\nStart with iron, copper, coal and shallow andesite. Gold and redstone occur deeper. Use suitable picks and a safe stairway; avoid digging straight up or down. Home has no osmium; Stage 1 needs none.",
                    "6 · Find your way\nAtlas opens with M by default. Check Options > Controls under Starfold Atlas if rebound. Mark home, safe entrances and ore sites with private waypoints. Waypoints guide you; they do not teleport.",
                    "7 · Quests and recipes\nOpen inventory; click the FTB Quests book in the sidebar. In JEI, hover an item: R shows recipes, U shows uses by default. Bindings can change in Controls. Check recipes before crafting.",
                    "8 · Ultimine controls\nIn Options > Controls > Key Binds, find FTB Ultimine. Set its activation and shape keys. Hold activation with a suitable tool, inspect the outline, then mine. Keep your home out of the selection.",
                    "9 · Ultimine costs\nThis pack limits each use to 32 blocks. A suitable tool is required. Mining costs durability and hunger: bring food and watch tool wear. It still needs a safe route and good lighting.",
                    "10 · Mining platform\nCraft with 4 iron ingots, 2 copper ingots, 1 iron pickaxe, 1 furnace and 1 lever. No Create machinery or osmium needed. Look up the mining platform in JEI before crafting.",
                    "11 · Crafting grid\nTop: iron / iron pick / iron\nMiddle: copper / furnace / copper\nBottom: iron / lever / iron\n\nPlace on stable home ground, away from your buildings, chests and paths.",
                    "12 · Scan first\nRight-click the platform, add fuel and start a scan. It surveys a 5x5 column below it, up to 512 blocks deep. Bounds, the core or obstructions can halt work. Read its status before waiting.",
                    "13 · Scan fuel\nA full scan costs about 25,600 FE. Prepare 2 coal or charcoal for the scan budget; fuel converts to energy over time. Excavation needs extra energy. Scan fuel is not the whole mining cost.",
                    "14 · Output space\nReview the scan, then start mining. Collect output regularly. If output slots are full, the platform waits with the target block intact, preserving its drops. Free space, refuel and check status.",
                    "15 · Stationary work\nThe platform works on home ground, not on a ship. Keep its owner online and work chunks loaded. No offline or remote mining. Pause before leaving; check fuel, output and obstacles on return.",
                    "16 · Private surveys\nCompleted scans appear in Atlas for their owner. A survey is a snapshot: ore may have been mined since. Private waypoints help you return. Neither waypoints nor survey records teleport you.",
                    "17 · Next: Create\nOnce your bed, food, lighting, iron tools and mining are stable, build a Create workshop: andesite alloy, basic rotation and pressing. Follow quests and JEI. No offworld osmium is needed for Stage 1.",
                    "18 · Free replacements\nNo OP needed:\n/starfoldguide\nuses your client language.\n/starfoldguide zh_cn\n/starfoldguide en_us\nselects a language. Keep one slot free. Requests have a 30-second game-time cooldown.");

    private SurvivalGuide() {}
}
