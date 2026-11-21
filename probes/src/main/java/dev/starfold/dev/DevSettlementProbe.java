package dev.starfold.dev;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.TreeMap;

/** Read-only receipts for the isolated complete survival pack; never creates terrain or supplies. */
public final class DevSettlementProbe {
    public static void initialize() {
        NeoForge.EVENT_BUS.addListener(
                (RegisterCommandsEvent event) ->
                        event.getDispatcher()
                                .register(
                                        Commands.literal("settlementtest")
                                                .requires(source -> source.hasPermission(2))
                                                .then(
                                                        Commands.argument(
                                                                        "action",
                                                                        StringArgumentType.word())
                                                                .executes(
                                                                        context ->
                                                                                run(
                                                                                        context
                                                                                                .getSource(),
                                                                                        StringArgumentType
                                                                                                .getString(
                                                                                                        context,
                                                                                                        "action"))))));
    }

    private static int run(CommandSourceStack source, String action) throws CommandSyntaxException {
        try {
            ServerPlayer player = source.getPlayerOrException();
            String world =
                    player.getServer()
                            .getWorldPath(LevelResource.ROOT)
                            .normalize()
                            .getFileName()
                            .toString();
            require(
                    world.startsWith("regression-settlement-"),
                    "Requires isolated settlement world");
            switch (action) {
                case "inventory" -> inventory(player);
                case "opening" -> {
                    require(player.gameMode.isSurvival(), "Opening must be survival");
                    inventory(player);
                    int books = 0;
                    for (var stack : player.getInventory().items)
                        if (!stack.isEmpty()) {
                            require(
                                    stack.is(Items.WRITTEN_BOOK),
                                    "Opening contains supplied equipment " + stack);
                            books += stack.getCount();
                        }
                    require(books == 1, "Expected exactly one opening guide, got " + books);
                }
                case "recipes" -> {
                    var manager = player.getServer().getRecipeManager();
                    for (String id :
                            new String[] {
                                "starfold_mining:mining_platform",
                                "escape_velocity:blaze_burner",
                                "escape_velocity:rose_quartz",
                                "escape_velocity:scope_nav"
                            })
                        require(
                                manager.byKey(ResourceLocation.parse(id)).isPresent(),
                                "Missing live recipe " + id);
                    for (String id :
                            new String[] {"ftbultimine", "ftbquests", "kubejs", "create", "jei"})
                        require(
                                net.neoforged.fml.ModList.get().isLoaded(id),
                                "Missing live mod " + id);
                    require(
                            !net.neoforged.fml.ModList.get().isLoaded("starfold_extras"),
                            "Extras must be absent");
                    require(player.getServer().getLevel(Level.NETHER) == null, "Unexpected Nether");
                    require(player.getServer().getLevel(Level.END) == null, "Unexpected End");
                }
                case "resources" -> resources(player);
                case "ultimine" -> {
                    var config =
                            Class.forName(
                                    "dev.ftb.mods.ftbultimine.config.FTBUltimineServerConfig");
                    int maximum =
                            (Integer)
                                    config.getMethod("getMaxBlocks", ServerPlayer.class)
                                            .invoke(null, player);
                    double exhaustion =
                            (Double)
                                    config.getMethod("getExhaustionPerBlock", ServerPlayer.class)
                                            .invoke(null, player);
                    require(maximum == 32 && exhaustion > 0, "Incorrect live Ultimine costs/limit");
                    var whitelist =
                            net.minecraft.tags.TagKey.create(
                                    net.minecraft.core.registries.Registries.BLOCK,
                                    ResourceLocation.parse("ftbultimine:block_whitelist"));
                    require(
                            net.minecraft.world.level.block.Blocks.STONE
                                    .defaultBlockState()
                                    .is(whitelist),
                            "Stone not allowed");
                    require(
                            net.minecraft.world.level.block.Blocks.OAK_LOG
                                    .defaultBlockState()
                                    .is(whitelist),
                            "Logs not allowed");
                    require(
                            net.minecraft.world.level.block.Blocks.IRON_ORE
                                    .defaultBlockState()
                                    .is(whitelist),
                            "Iron ore not allowed");
                    require(
                            !net.minecraft.world.level.block.Blocks.CHEST
                                    .defaultBlockState()
                                    .is(whitelist),
                            "Chest allowed");
                    require(
                            !net.minecraft.world.level.block.Blocks.OAK_PLANKS
                                    .defaultBlockState()
                                    .is(whitelist),
                            "Planks allowed");
                    LoggerFactory.getLogger("starfold-probe")
                            .info("SETTLEMENT_ULTIMINE max={} exhaustion={}", maximum, exhaustion);
                }
                default -> throw new IllegalArgumentException("Unknown action " + action);
            }
            String receipt = "SETTLEMENT_TEST_PASS " + action;
            LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "{} player={} mode={}",
                            receipt,
                            player.getUUID(),
                            player.gameMode.getGameModeForPlayer());
            source.sendSuccess(() -> Component.literal(receipt), false);
            return 1;
        } catch (Exception error) {
            String message = "SETTLEMENT_TEST_FAIL " + action + ": " + error.getMessage();
            LoggerFactory.getLogger("starfold-probe").error(message, error);
            source.sendFailure(Component.literal(message));
            throw new SimpleCommandExceptionType(Component.literal(message)).create();
        }
    }

    private static void inventory(ServerPlayer player) {
        Map<String, Integer> items = new TreeMap<>();
        for (var stack : player.getInventory().items)
            if (!stack.isEmpty()) {
                items.merge(
                        BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                        stack.getCount(),
                        Integer::sum);
                if (stack.is(Items.WRITTEN_BOOK)) {
                    var book = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
                    require(book != null && book.pages().size() >= 6, "Incomplete guide");
                    LoggerFactory.getLogger("starfold-probe")
                            .info(
                                    "SETTLEMENT_BOOK title={} pages={} author={}",
                                    book.title().raw(),
                                    book.pages().size(),
                                    book.author());
                }
            }
        LoggerFactory.getLogger("starfold-probe")
                .info(
                        "SETTLEMENT_INVENTORY player={} pos={} items={} keepInventory={} difficulty={}",
                        player.getUUID(),
                        player.blockPosition(),
                        items,
                        player.level()
                                .getGameRules()
                                .getBoolean(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY),
                        player.level().getDifficulty());
    }

    private static void resources(ServerPlayer player) {
        var level = player.serverLevel();
        BlockPos start = player.blockPosition();
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, String> nearest = new TreeMap<>();
        Map<String, Double> distances = new TreeMap<>();
        // Full generated chunks, a bounded 129x129 square; no block or inventory changes.
        for (int dx = -64; dx <= 64; dx++)
            for (int dz = -64; dz <= 64; dz++) {
                int x = start.getX() + dx, z = start.getZ() + dz;
                var chunk = level.getChunk(x >> 4, z >> 4);
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
                for (int y = top; y >= Math.max(level.getMinBuildHeight(), top - 56); y--) {
                    var pos = new BlockPos(x, y, z);
                    var state = chunk.getBlockState(pos);
                    String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    boolean useful =
                            state.is(BlockTags.LOGS)
                                    || !state.getFluidState().isEmpty()
                                    || id.endsWith("_ore")
                                    || id.equals("minecraft:andesite")
                                    || id.equals("minecraft:short_grass")
                                    || id.equals("minecraft:sweet_berry_bush");
                    if (useful) {
                        counts.merge(id, 1, Integer::sum);
                        double distance = pos.distSqr(start);
                        if (distance < distances.getOrDefault(id, Double.MAX_VALUE)) {
                            distances.put(id, distance);
                            nearest.put(id, pos.toShortString());
                        }
                    }
                }
            }
        Map<String, Integer> animals = new TreeMap<>();
        for (var animal : level.getEntitiesOfClass(Animal.class, new AABB(start).inflate(96)))
            animals.merge(
                    BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()).toString(),
                    1,
                    Integer::sum);
        LoggerFactory.getLogger("starfold-probe")
                .info(
                        "SETTLEMENT_RESOURCES seed={} pos={} sampled={} nearest={} animals={}",
                        level.getSeed(),
                        start,
                        counts,
                        nearest,
                        animals);
        require(
                counts.keySet().stream().anyMatch(id -> id.endsWith("_log")),
                "No sampled logs within 64 blocks");
        for (String id :
                new String[] {
                    "minecraft:iron_ore",
                    "minecraft:copper_ore",
                    "minecraft:coal_ore",
                    "minecraft:andesite"
                }) require(counts.getOrDefault(id, 0) > 0, "No sampled shallow " + id);
        // Grass and animals are reported separately; these samples do not establish a complete food
        // route.
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private DevSettlementProbe() {}
}
