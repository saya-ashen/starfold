package dev.starfold.dev;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import dev.starfold.mining.MiningBlockEntity;
import dev.starfold.mining.StarfoldMining;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetStorage;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.items.IItemHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Disposable-world server fixture, initialized by DevBoardingProbe.
 * Run setup, power, scan; wait for scanned=true; full, start; wait for cursor=100;
 * verify, save; restart the JVM and run persist. Stay near the platform with its chunks loaded.
 */
public final class DevMiningProbe {
    private static final Logger LOG = LoggerFactory.getLogger("starfold-probe");
    private static final String KEY = "starfold_probe:mining";
    private static final String SURVEYS = "starfold_mining:surveys_v1";
    private static final UUID SESSION = UUID.randomUUID();
    private static final ResourceLocation COPPER_LOOT =
            ResourceLocation.parse("minecraft:blocks/copper_ore");
    private static final long LOOT_SEED = 0x4d494e494e47L;
    private static boolean initialized;

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        NeoForge.EVENT_BUS.addListener(
                (RegisterCommandsEvent event) ->
                        event.getDispatcher()
                                .register(
                                        Commands.literal("miningtest")
                                                .requires(source -> source.hasPermission(2))
                                                .then(
                                                        Commands.argument(
                                                                        "action",
                                                                        StringArgumentType.word())
                                                                .suggests(
                                                                        (context, builder) -> {
                                                                            for (String action :
                                                                                    new String[] {
                                                                                        "setup",
                                                                                        "scan",
                                                                                        "start",
                                                                                        "pause",
                                                                                        "power",
                                                                                        "snapshot",
                                                                                        "verify",
                                                                                        "full",
                                                                                        "save",
                                                                                        "persist",
                                                                                        "bounds"
                                                                                    })
                                                                                builder.suggest(
                                                                                        action);
                                                                            return builder
                                                                                    .buildFuture();
                                                                        })
                                                                .executes(
                                                                        context ->
                                                                                run(
                                                                                        context
                                                                                                .getSource(),
                                                                                        StringArgumentType
                                                                                                .getString(
                                                                                                        context,
                                                                                                        "action"))))));
        NeoForge.EVENT_BUS.addListener(DevMiningProbe::beforeBreak);
    }

    private static int run(CommandSourceStack source, String action) throws CommandSyntaxException {
        try {
            guard(source);
            ServerPlayer player = source.getPlayerOrException();
            if (action.equals("setup")) {
                setup(player);
            } else {
                MiningBlockEntity be = fixture(player);
                require(player.getUUID().equals(be.owner()), "Fixture owner mismatch");
                switch (action) {
                    case "scan" -> {
                        require(
                                !be.enabled() && !be.scanned() && be.cursor() == 0,
                                "Scan needs a fresh paused fixture");
                        LOG.info("MINING_TEST_MUTATION scan {}", be.getBlockPos());
                        be.command(0);
                        require(be.enabled(), "Scan command was rejected");
                    }
                    case "start" -> {
                        require(
                                be.scanned() && !be.enabled(),
                                "Start needs a completed scan and paused fixture");
                        LOG.info("MINING_TEST_MUTATION start {}", be.getBlockPos());
                        be.command(1);
                        require(be.enabled(), "Start command was rejected");
                    }
                    case "pause" -> pause(be);
                    case "power" -> power(player.serverLevel(), be);
                    case "snapshot" -> {}
                    case "verify" -> verify(player, be);
                    case "full" -> full(player, be);
                    case "save" -> checkpoint(player, be);
                    case "persist" -> persistence(player, be);
                    case "bounds" -> bounds(player, be);
                    default -> throw new IllegalArgumentException("Unknown action: " + action);
                }
            }
            snapshot(source, player, fixture(player));
            String receipt = "MINING_TEST_PASS " + action;
            LOG.info("{} player={}", receipt, player.getUUID());
            source.sendSuccess(() -> Component.literal(receipt), false);
            return 1;
        } catch (Exception error) {
            String message = "MINING_TEST_FAIL " + action + ": " + error.getMessage();
            LOG.error(message, error);
            source.sendFailure(Component.literal(message));
            throw new SimpleCommandExceptionType(Component.literal(message)).create();
        }
    }

    private static void bounds(ServerPlayer player, MiningBlockEntity fixture) {
        ServerLevel level = player.serverLevel();
        BlockPos pos =
                fixture.getBlockPos()
                        .offset(8, 0, 0)
                        .atY(level.getMinBuildHeight() + MiningBlockEntity.DEPTH - 1);
        require(level.hasChunkAt(pos), "Bounds fixture chunk must already be loaded");
        require(level.getBlockEntity(pos) == null, "Bounds fixture would replace an inventory");
        BlockState original = level.getBlockState(pos);
        try {
            place(level, pos, StarfoldMining.PLATFORM.get().defaultBlockState());
            var be = (MiningBlockEntity) level.getBlockEntity(pos);
            be.placedBy(player.getUUID());
            be.energyStorage().receiveEnergy(MiningBlockEntity.MAX_ENERGY, false);
            be.command(0);
            require(
                    !be.enabled()
                            && be.scanProgress() == 0
                            && be.status() == MiningBlockEntity.BLOCKED
                            && be.energy() == MiningBlockEntity.MAX_ENERGY,
                    "An incomplete 512-layer volume must be rejected before charging for a scan");
        } finally {
            place(level, pos, original);
        }
    }

    private static void guard(CommandSourceStack source) {
        String world =
                source.getServer()
                        .getWorldPath(LevelResource.ROOT)
                        .toAbsolutePath()
                        .normalize()
                        .getFileName()
                        .toString();
        require(
                world.startsWith("regression-mining-"),
                "Requires world basename prefix regression-mining-");
        require(PlanetLevels.is(source.getLevel()), "Requires the native planet level");
    }

    private static void canonical(ServerLevel level, BlockPos pos) {
        long key = PlanetStorage.key(pos.getX() >> 4, pos.getZ() >> 4);
        require(key >= 0, "Outside canonical planet charts: " + pos);
        require(
                !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos),
                "Outside build bounds: " + pos);
    }

    private static void setup(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos base = player.blockPosition().offset(4, 0, 0);
        // Validate the entire footprint before changing any block; do not replace existing
        // inventories.
        for (int x = -2; x <= 2; x++)
            for (int z = -2; z <= 2; z++)
                for (int y = -5; y <= 3; y++) {
                    BlockPos pos = base.offset(x, y, z);
                    canonical(level, pos);
                    require(
                            level.getBlockEntity(pos) == null,
                            "Fixture would replace a block entity: " + pos);
                }
        CompoundTag record = new CompoundTag();
        record.putLong("Pos", base.asLong());
        record.putString("Dimension", level.dimension().location().toString());
        player.getPersistentData().put(KEY, record);
        LOG.info("MINING_TEST_MUTATION fixture record player={} data={}", player.getUUID(), record);
        for (int x = -2; x <= 2; x++)
            for (int z = -2; z <= 2; z++)
                for (int y = 0; y <= 3; y++)
                    place(level, base.offset(x, y, z), Blocks.AIR.defaultBlockState());
        for (int cursor = 0; cursor < 125; cursor++) {
            Block block =
                    cursor == 0
                            ? Blocks.IRON_ORE
                            : cursor == 1
                                    ? Blocks.COPPER_ORE
                                    : cursor < 100 ? Blocks.STONE : Blocks.BEDROCK;
            place(level, MiningBlockEntity.target(base, cursor), block.defaultBlockState());
        }
        place(level, base, StarfoldMining.PLATFORM.get().defaultBlockState());
        MiningBlockEntity be = fixture(player);
        LOG.info("MINING_TEST_MUTATION placedBy {} owner={}", base, player.getUUID());
        be.placedBy(player.getUUID());
        IItemHandler items = level.getCapability(Capabilities.ItemHandler.BLOCK, base, null);
        require(items != null, "Missing item capability");
        LOG.info("MINING_TEST_MUTATION fuel {} coal=8 via capability", base);
        require(
                items.insertItem(0, new ItemStack(Items.COAL, 8), false).isEmpty(),
                "Coal insertion rejected");
        require(
                items.getStackInSlot(0).is(Items.COAL) && items.getStackInSlot(0).getCount() == 8,
                "Fuel readback mismatch");
        require(player.getUUID().equals(be.owner()), "Owner readback mismatch");
    }

    private static void place(ServerLevel level, BlockPos pos, BlockState state) {
        BlockState before = level.getBlockState(pos);
        LOG.info("MINING_TEST_MUTATION block {} {} -> {}", pos, before, state);
        if (before != state)
            require(
                    level.setBlock(pos, state, Block.UPDATE_ALL),
                    "Cannot place fixture block: " + pos);
        require(level.getBlockState(pos) == state, "Fixture block readback mismatch: " + pos);
    }

    private static MiningBlockEntity fixture(ServerPlayer player) {
        CompoundTag record = player.getPersistentData().getCompound(KEY);
        require(record.contains("Pos", Tag.TAG_LONG), "Run setup first");
        require(
                record.getString("Dimension")
                        .equals(player.level().dimension().location().toString()),
                "Return to the fixture dimension");
        BlockPos pos = BlockPos.of(record.getLong("Pos"));
        canonical(player.serverLevel(), pos);
        require(player.serverLevel().hasChunkAt(pos), "Fixture chunk is unloaded");
        require(
                player.serverLevel().getBlockEntity(pos) instanceof MiningBlockEntity,
                "Fixture platform is missing");
        return (MiningBlockEntity) player.serverLevel().getBlockEntity(pos);
    }

    private static void pause(MiningBlockEntity be) {
        if (be.enabled()) {
            LOG.info("MINING_TEST_MUTATION pause {}", be.getBlockPos());
            be.command(1);
        }
        require(!be.enabled(), "Pause command was rejected");
    }

    private static void power(ServerLevel level, MiningBlockEntity be) {
        IEnergyStorage power =
                level.getCapability(Capabilities.EnergyStorage.BLOCK, be.getBlockPos(), null);
        require(
                power != null && power.canReceive() && !power.canExtract(),
                "Missing/invalid FE capability");
        int before = be.energy();
        int maximum = power.getMaxEnergyStored();
        require(
                maximum == MiningBlockEntity.MAX_ENERGY && before >= 0 && before <= maximum,
                "Invalid energy bounds");
        require(
                power.receiveEnergy(-1, false) == 0 && be.energy() == before,
                "Negative FE changed storage");
        require(
                power.receiveEnergy(Integer.MAX_VALUE, true) == maximum - before
                        && be.energy() == before,
                "Simulated FE mutated storage or failed clamp");
        LOG.info("MINING_TEST_MUTATION FE {} {} -> {}", be.getBlockPos(), before, maximum);
        require(
                power.receiveEnergy(Integer.MAX_VALUE, false) == maximum - before,
                "FE receive clamp mismatch");
        require(
                be.energy() == maximum && power.getEnergyStored() == maximum,
                "FE readback mismatch");
        require(
                power.receiveEnergy(1, false) == 0
                        && power.extractEnergy(Integer.MAX_VALUE, false) == 0
                        && be.energy() == maximum,
                "Full receive/extraction changed energy");
    }

    /** Seed the real copper loot sequence at the break event, immediately before backend loot evaluation.
     * Preview and rewind use the same real tool/entity/table; no production hooks or loot replacements.
     */
    private static void beforeBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getPlayer() instanceof FakePlayer)
                || !event.getPlayer().getGameProfile().getName().equals("StarfoldMiner")) return;
        ServerPlayer owner =
                level.getServer().getPlayerList().getPlayer(event.getPlayer().getUUID());
        if (owner == null || !owner.getPersistentData().contains(KEY)) return;
        try {
            guard(owner.createCommandSourceStack());
            MiningBlockEntity be = fixture(owner);
            if (be.getLevel() != level
                    || be.cursor() >= 100
                    || !MiningBlockEntity.target(be.getBlockPos(), be.cursor())
                            .equals(event.getPos())) return;
            LOG.info(
                    "MINING_TEST_MUTATION backend break attempt {} cursor={} state={}",
                    event.getPos(),
                    be.cursor(),
                    event.getState());
            if (be.cursor() != 1 || !event.getState().is(Blocks.COPPER_ORE)) return;
            var random = level.getRandomSequence(COPPER_LOOT);
            LOG.info(
                    "MINING_TEST_MUTATION copper loot sequence seed={} at {}",
                    LOOT_SEED,
                    event.getPos());
            random.setSeed(LOOT_SEED);
            Map<String, Integer> expected;
            try {
                expected =
                        totals(
                                Block.getDrops(
                                        event.getState(),
                                        level,
                                        event.getPos(),
                                        null,
                                        event.getPlayer(),
                                        event.getPlayer().getMainHandItem()));
            } finally {
                random.setSeed(LOOT_SEED);
            }
            require(
                    expected.size() == 1 && expected.getOrDefault("minecraft:raw_copper", 0) > 0,
                    "Unexpected copper loot: " + expected);
            owner.getPersistentData()
                    .getCompound(KEY)
                    .putInt("Copper", expected.get("minecraft:raw_copper"));
            LOG.info("MINING_TEST_MUTATION expected copper={}", expected);
        } catch (Exception error) {
            event.setCanceled(true);
            LOG.error("MINING_TEST_FAIL copper fixture loot", error);
            owner.sendSystemMessage(
                    Component.literal(
                            "MINING_TEST_FAIL copper fixture loot: " + error.getMessage()));
        }
    }

    private static Map<String, Integer> totals(Iterable<ItemStack> stacks) {
        Map<String, Integer> counts = new TreeMap<>();
        for (ItemStack stack : stacks)
            if (!stack.isEmpty())
                counts.merge(
                        BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                        stack.getCount(),
                        Integer::sum);
        return counts;
    }

    private static Map<String, Integer> outputs(MiningBlockEntity be) {
        var stacks = new java.util.ArrayList<ItemStack>();
        for (int slot = 1; slot < be.automation().getSlots(); slot++)
            stacks.add(be.automation().getStackInSlot(slot));
        return totals(stacks);
    }

    private static ListTag surveys(ServerPlayer player) {
        return player.getPersistentData().getCompound(SURVEYS).getList("sites", Tag.TAG_COMPOUND);
    }

    private static void verify(ServerPlayer player, MiningBlockEntity be) {
        require(be.scanned(), "Scan has not completed");
        require(player.getUUID().equals(be.owner()), "Owner mismatch");
        require(
                be.cursor() >= 2 && be.cursor() <= 100,
                "Cursor did not progress within the fixture: " + be.cursor());
        for (int i = 0; i < 2; i++)
            require(
                    player.level()
                            .getBlockState(MiningBlockEntity.target(be.getBlockPos(), i))
                            .isAir(),
                    "Original ore was not removed: cursor " + i);
        int copper = player.getPersistentData().getCompound(KEY).getInt("Copper");
        require(copper > 0, "Missing deterministic copper loot expectation");
        Map<String, Integer> expected = new TreeMap<>();
        expected.put("minecraft:raw_iron", 1);
        expected.put("minecraft:raw_copper", copper);
        if (be.cursor() > 2) expected.put("minecraft:cobblestone", be.cursor() - 2);
        require(
                outputs(be).equals(expected),
                "Exact drops mismatch expected=" + expected + " actual=" + outputs(be));
        require(
                be.energy() >= 0 && be.energy() <= MiningBlockEntity.MAX_ENERGY,
                "Energy outside bounds");
        for (int i = 100; i < 125; i++)
            require(
                    player.level()
                            .getBlockState(MiningBlockEntity.target(be.getBlockPos(), i))
                            .is(Blocks.BEDROCK),
                    "Bedrock was removed: cursor " + i);
        for (String ore : new String[] {"minecraft:iron_ore", "minecraft:copper_ore"})
            require(
                    be.summary().stream()
                            .anyMatch(
                                    row ->
                                            row.id().equals(ore)
                                                    && row.count() >= 1
                                                    && row.minDepth() == 1),
                    "Scan omitted fixture ore: " + ore);
        boolean site = false;
        for (Tag value : surveys(player)) {
            CompoundTag row = (CompoundTag) value;
            if (row.getString("dimension").equals(player.level().dimension().location().toString())
                    && row.getInt("x") == be.getBlockPos().getX()
                    && row.getInt("y") == be.getBlockPos().getY()
                    && row.getInt("z") == be.getBlockPos().getZ()
                    && row.getInt("scanned") > 0) site = true;
        }
        require(site, "No persisted survey for this fixture");
        if (be.cursor() == 100)
            require(
                    !be.enabled() && be.status() == MiningBlockEntity.BLOCKED,
                    "Expected a stopped BLOCKED platform at bedrock");
    }

    private static void full(ServerPlayer player, MiningBlockEntity be) {
        require(
                be.scanned() && be.cursor() < 100,
                "Full-output test needs scanned fixture before bedrock");
        pause(be);
        power(player.serverLevel(), be);
        int cursor = be.cursor();
        BlockPos target = MiningBlockEntity.target(be.getBlockPos(), cursor);
        BlockState original = player.level().getBlockState(target);
        require(!original.isAir(), "Full-output target is already air");
        CompoundTag saved = be.saveWithoutMetadata(player.registryAccess());
        var handler = new net.neoforged.neoforge.items.ItemStackHandler(19);
        handler.setStackInSlot(0, be.automation().getStackInSlot(0).copy());
        for (int slot = 1; slot < 19; slot++)
            handler.setStackInSlot(slot, new ItemStack(Items.COBBLESTONE, 64));
        CompoundTag filled = saved.copy();
        filled.put("Inventory", handler.serializeNBT(player.registryAccess()));
        LOG.info(
                "MINING_TEST_MUTATION full output NBT seed {} cursor={}", be.getBlockPos(), cursor);
        try {
            be.loadWithComponents(filled, player.registryAccess());
            be.sync();
            require(
                    be.automation()
                                    .insertItem(1, new ItemStack(Items.COBBLESTONE), false)
                                    .getCount()
                            == 1,
                    "Automation unexpectedly accepts output insertion");
            be.command(1);
            require(be.enabled(), "Full-output start rejected");
            LOG.info("MINING_TEST_MUTATION full backend ticks {} count=11", be.getBlockPos());
            for (int i = 0; i < 11; i++) be.tick();
            require(
                    be.status() == MiningBlockEntity.OUTPUT_FULL
                            && be.cursor() == cursor
                            && player.level().getBlockState(target) == original,
                    "Full outputs did not stall excavation atomically");
            require(
                    outputs(be).equals(Map.of("minecraft:cobblestone", 18 * 64)),
                    "Full output contents changed");
            require(be.energy() == MiningBlockEntity.MAX_ENERGY, "Failed excavation consumed FE");
        } finally {
            pause(be);
            CompoundTag restored = be.saveWithoutMetadata(player.registryAccess());
            restored.put("Inventory", saved.getCompound("Inventory").copy());
            LOG.info("MINING_TEST_MUTATION restore original inventory {}", be.getBlockPos());
            be.loadWithComponents(restored, player.registryAccess());
            be.sync();
        }
    }

    /** Saturating FE while paused also freezes passive fuel burning, making restart equality meaningful. */
    private static void checkpoint(ServerPlayer player, MiningBlockEntity be) {
        require(
                be.scanned() && be.cursor() >= 2 && !outputs(be).isEmpty(),
                "Persistence checkpoint needs completed scan and mined outputs");
        pause(be);
        power(player.serverLevel(), be);
        CompoundTag saved = be.saveWithoutMetadata(player.registryAccess());
        CompoundTag record = player.getPersistentData().getCompound(KEY);
        record.put("Checkpoint", saved.copy());
        record.putUUID("Session", SESSION);
        record.put("Surveys", surveys(player).copy());
        LOG.info(
                "MINING_TEST_MUTATION persistence checkpoint {} cursor={} energy={} inventory={}",
                be.getBlockPos(),
                be.cursor(),
                be.energy(),
                saved.getCompound("Inventory"));
        LOG.info("MINING_TEST_MUTATION save player and world checkpoint {}", be.getBlockPos());
        require(
                player.getServer().saveEverything(false, true, true),
                "Server checkpoint save failed");
    }

    private static void persistence(ServerPlayer player, MiningBlockEntity be) {
        CompoundTag record = player.getPersistentData().getCompound(KEY);
        require(
                record.contains("Checkpoint", Tag.TAG_COMPOUND) && record.hasUUID("Session"),
                "Run save before restarting");
        require(
                !SESSION.equals(record.getUUID("Session")),
                "Restart the game/server JVM before persist");
        CompoundTag saved = record.getCompound("Checkpoint");
        CompoundTag actual = be.saveWithoutMetadata(player.registryAccess());
        for (String field :
                new String[] {
                    "DigCursor",
                    "ScanCursor",
                    "Energy",
                    "Inventory",
                    "Owner",
                    "Anchor",
                    "AnchorDimension",
                    "Scanned",
                    "Ores",
                    "Enabled",
                    "BurnTicks",
                    "WorkTicks"
                })
            require(
                    java.util.Objects.equals(saved.get(field), actual.get(field)),
                    "Persistence mismatch: " + field);
        require(
                record.getList("Surveys", Tag.TAG_COMPOUND).equals(surveys(player)),
                "Survey persistence mismatch");
    }

    private static void snapshot(
            CommandSourceStack source, ServerPlayer player, MiningBlockEntity be) {
        String readback =
                "MINING_TEST_SNAPSHOT pos="
                        + be.getBlockPos()
                        + " owner="
                        + be.owner()
                        + " count="
                        + outputs(be).values().stream().mapToInt(Integer::intValue).sum()
                        + " status="
                        + be.status()
                        + " enabled="
                        + be.enabled()
                        + " cursor="
                        + be.cursor()
                        + " scan="
                        + be.scanProgress()
                        + "/"
                        + MiningBlockEntity.TOTAL
                        + " scanned="
                        + be.scanned()
                        + " energy="
                        + be.energy()
                        + "/"
                        + MiningBlockEntity.MAX_ENERGY
                        + " items="
                        + outputs(be)
                        + " summary="
                        + be.summary()
                        + " surveys="
                        + surveys(player);
        LOG.info("{}", readback);
        source.sendSuccess(() -> Component.literal(readback), false);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private DevMiningProbe() {}
}
