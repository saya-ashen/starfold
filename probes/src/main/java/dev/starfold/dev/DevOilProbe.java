package dev.starfold.dev;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import dev.starfold.mining.OilExtractorBlockEntity;
import dev.starfold.mining.OilMenu;
import dev.starfold.mining.StarfoldMining;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetStorage;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.UUID;

/** Disposable native-home fixture. initialize() is wired by the owning registration change.
 * No synthetic machine ticks, private-field reflection, or replacement reservoir.
 * Every asynchronous assertion uses world game time and a separate command after real server ticks.
 */
public final class DevOilProbe {
    private static final Logger LOG = LoggerFactory.getLogger("starfold-probe");
    private static final String KEY = "starfold_probe:oil";
    private static final String EVIDENCE = "fixture!=ordinary survival";
    private static final UUID SESSION = UUID.randomUUID();
    // Pinned OilCycle contract, deliberately independent of its package-private implementation.
    private static final int CAPACITY = 16000, ENERGY = 20000, DEPTH = 4;
    private static final int PAUSED = 7, FULL = 9, BLOCKED = 11, PROTECTED = 12;
    private static boolean initialized;

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        NeoForge.EVENT_BUS.addListener(
                (RegisterCommandsEvent event) ->
                        event.getDispatcher()
                                .register(
                                        Commands.literal("oiltest")
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
                                                                                        "power",
                                                                                        "survey",
                                                                                        "survey-check",
                                                                                        "build",
                                                                                        "build-check",
                                                                                        "pump",
                                                                                        "verify",
                                                                                        "pause",
                                                                                        "snapshot",
                                                                                        "full",
                                                                                        "one",
                                                                                        "blocked",
                                                                                        "water",
                                                                                        "be",
                                                                                        "protected",
                                                                                        "race",
                                                                                        "fuel-race",
                                                                                        "fuel-path",
                                                                                        "paused",
                                                                                        "check-after",
                                                                                        "abort",
                                                                                        "outlet",
                                                                                        "save",
                                                                                        "persist"
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
        NeoForge.EVENT_BUS.addListener(DevOilProbe::beforeBreak);
        NeoForge.EVENT_BUS.addListener(DevOilProbe::fuelCallback);
    }

    private static int run(CommandSourceStack source, String action)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        try {
            guard(source);
            ServerPlayer player = source.getPlayerOrException();
            if (action.equals("setup")) setup(player);
            else {
                OilExtractorBlockEntity be = fixture(player);
                switch (action) {
                    case "snapshot" -> {}
                    case "power" -> {
                        idle(player);
                        pause(player, be);
                        power(player, be);
                    }
                    case "survey" -> {
                        idle(player);
                        require(
                                !nbt(player, be).getBoolean("Surveyed")
                                        && !nbt(player, be).getBoolean("Well"),
                                "Survey needs fresh fixture");
                        begin(player, be, "survey", OilMenu.SURVEY);
                    }
                    case "survey-check" -> check(player, be, "survey");
                    case "build" -> {
                        idle(player);
                        require(
                                nbt(player, be).getBoolean("Surveyed")
                                        && !nbt(player, be).getBoolean("Well"),
                                "Complete survey first");
                        begin(player, be, "build", OilMenu.BUILD);
                    }
                    case "build-check" -> check(player, be, "build");
                    case "pump" -> boundary(player, be, "pump");
                    case "verify" -> check(player, be, "pump");
                    case "full",
                            "one",
                            "blocked",
                            "water",
                            "be",
                            "protected",
                            "race",
                            "fuel-race",
                            "fuel-path",
                            "paused" ->
                            boundary(player, be, action);
                    case "check-after" -> check(player, be, null);
                    case "abort" -> abort(player, be);
                    case "pause" -> {
                        idle(player);
                        pause(player, be);
                    }
                    case "outlet" -> outlet(player, be);
                    case "save" -> checkpoint(player, be);
                    case "persist" -> persistence(player, be);
                    default -> throw new IllegalArgumentException("Unknown action: " + action);
                }
            }
            snapshot(source, player, fixture(player));
            String receipt = "OIL_TEST_COMMAND_OK action=" + action + " " + EVIDENCE;
            LOG.info("{} player={}", receipt, player.getUUID());
            source.sendSuccess(() -> Component.literal(receipt), false);
            return 1;
        } catch (Exception failure) {
            String message =
                    "OIL_TEST_FAIL action="
                            + action
                            + " "
                            + EVIDENCE
                            + " reason="
                            + failure.getMessage();
            LOG.error(message, failure);
            source.sendFailure(Component.literal(message));
            throw new SimpleCommandExceptionType(Component.literal(message)).create();
        }
    }

    private static void guard(CommandSourceStack source) {
        require(source.hasPermission(2), "Requires permission 2");
        String world =
                source.getServer()
                        .getWorldPath(LevelResource.ROOT)
                        .toAbsolutePath()
                        .normalize()
                        .getFileName()
                        .toString();
        require(
                world.startsWith("regression-stage2-"),
                "Requires world basename regression-stage2-*");
        require(PlanetLevels.is(source.getLevel()), "Requires native planet level");
        require(source.getEntity() instanceof ServerPlayer, "Requires a real player");
        ServerPlayer player = (ServerPlayer) source.getEntity();
        require(
                player.serverLevel() == source.getLevel(),
                "Command source must match actual player dimension");
        home(player.serverLevel(), player.blockPosition());
    }

    private static void home(ServerLevel level, BlockPos pos) {
        require(PlanetLevels.is(level), "Requires actual native planet level");
        long key = PlanetStorage.key(pos.getX() >> 4, pos.getZ() >> 4);
        require(key >= 0 && key < PlanetStorage.CHUNKS_PER_PLANET, "Outside home charts: " + pos);
        var address = PlanetStorage.address(pos);
        require(
                address.owner().planet() == 0 && address.owner().block().equals(pos),
                "Requires canonical native home: " + pos);
        require(
                !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos),
                "Outside build bounds: " + pos);
        require(level.hasChunkAt(pos), "Load fixture chunk first: " + pos);
    }

    private static CompoundTag record(ServerPlayer player) {
        return player.getPersistentData().getCompound(KEY);
    }

    private static CompoundTag nbt(ServerPlayer player, OilExtractorBlockEntity be) {
        return be.saveWithoutMetadata(player.registryAccess());
    }

    private static void idle(ServerPlayer player) {
        require(
                !record(player).contains("Pending"),
                "Run check-after (or abort) before another mutation");
    }

    private static void testResult(ServerPlayer player, String kind, boolean passed) {
        CompoundTag results = record(player).getCompound("Passed");
        results.putBoolean(kind, passed);
        record(player).put("Passed", results);
    }

    private static OilExtractorBlockEntity fixture(ServerPlayer player) {
        CompoundTag record = record(player);
        require(record.contains("Pos", Tag.TAG_LONG), "Run setup first");
        require(
                record.getString("Dimension")
                        .equals(player.level().dimension().location().toString()),
                "Return to fixture dimension");
        BlockPos pos = BlockPos.of(record.getLong("Pos"));
        for (int i = 0; i <= DEPTH; i++) home(player.serverLevel(), pos.below(i));
        require(
                player.serverLevel().getBlockEntity(pos) instanceof OilExtractorBlockEntity,
                "Fixture oil extractor missing");
        OilExtractorBlockEntity be =
                (OilExtractorBlockEntity) player.serverLevel().getBlockEntity(pos);
        require(be.ownedBy(player), "Fixture owner mismatch");
        return be;
    }

    private static void setup(ServerPlayer player) throws ReflectiveOperationException {
        require(
                !record(player).contains("Pos"),
                "One setup per player; use another disposable world to reset");
        ServerLevel level = player.serverLevel();
        BlockPos base = player.blockPosition().offset(4, 0, 0);
        // Solid enclosure prevents the water boundary fixture spreading into neighboring terrain.
        for (BlockPos pos :
                BlockPos.betweenClosed(base.offset(-1, -DEPTH, -1), base.offset(1, 0, 1))) {
            home(level, pos);
            require(
                    level.getBlockEntity(pos) == null,
                    "Would replace an existing block entity: " + pos);
        }
        RealOil reservoir = realOil(level, base);
        int natural = reservoir.read();
        CompoundTag record = new CompoundTag();
        record.putLong("Pos", base.asLong());
        record.putString("Dimension", level.dimension().location().toString());
        record.putInt("NaturalCDG", natural);
        record.putInt("ArtificialCDG", 5000);
        LOG.info(
                "OIL_TEST_MUTATION setup {} player={} pos={} naturalCDG={} ARTIFICIAL reservoir=5000mB depth={}",
                EVIDENCE,
                player.getUUID(),
                base,
                natural,
                DEPTH);
        player.getPersistentData().put(KEY, record);
        seed(player, base, 5000, "setup artificial reservoir");
        for (BlockPos pos :
                BlockPos.betweenClosed(base.offset(-1, -DEPTH, -1), base.offset(1, -1, 1)))
            place(level, pos, Blocks.STONE.defaultBlockState());
        place(level, base.below(DEPTH), Blocks.BEDROCK.defaultBlockState());
        place(level, base, StarfoldMining.OIL.get().defaultBlockState());
        OilExtractorBlockEntity be = (OilExtractorBlockEntity) level.getBlockEntity(base);
        require(be != null, "Placed oil block has no machine");
        LOG.info("OIL_TEST_MUTATION placedBy pos={} owner={} {}", base, player.getUUID(), EVIDENCE);
        be.placedBy(player.getUUID());
        var items = level.getCapability(Capabilities.ItemHandler.BLOCK, base, null);
        require(items != null, "Missing fuel capability");
        LOG.info("OIL_TEST_MUTATION fixture fuel coal=8 pos={} {}", base, EVIDENCE);
        require(
                items.insertItem(0, new ItemStack(Items.COAL, 8), false).isEmpty(),
                "Coal insertion rejected");
        require(
                items.getStackInSlot(0).is(Items.COAL) && items.getStackInSlot(0).getCount() == 8,
                "Fuel readback mismatch");
        power(player, be);
        assertRoute(player, be);
    }

    private static void place(ServerLevel level, BlockPos pos, BlockState state) {
        home(level, pos);
        BlockState before = level.getBlockState(pos);
        LOG.info(
                "OIL_TEST_MUTATION block pos={} before={} after={} {}",
                pos,
                before,
                state,
                EVIDENCE);
        if (before != state)
            require(
                    level.setBlock(pos, state, Block.UPDATE_ALL),
                    "Cannot place fixture block: " + pos);
        require(level.getBlockState(pos) == state, "Block readback mismatch: " + pos);
    }

    private static void command(ServerPlayer player, OilExtractorBlockEntity be, int action) {
        LOG.info(
                "OIL_TEST_MUTATION be.command action={} pos={} {}",
                action,
                be.getBlockPos(),
                EVIDENCE);
        be.command(player, action);
    }

    private static void pause(ServerPlayer player, OilExtractorBlockEntity be) {
        command(player, be, OilMenu.PAUSE);
        CompoundTag saved = nbt(player, be);
        require(saved.getInt("Mode") == 0 && saved.getInt("Status") == PAUSED, "Pause rejected");
    }

    private static void power(ServerPlayer player, OilExtractorBlockEntity be) {
        var power =
                player.serverLevel()
                        .getCapability(Capabilities.EnergyStorage.BLOCK, be.getBlockPos(), null);
        require(
                power != null && power.canReceive() && !power.canExtract(),
                "Missing FE input capability");
        int before = power.getEnergyStored();
        require(power.getMaxEnergyStored() == ENERGY, "OilCycle FE capacity changed");
        require(
                power.receiveEnergy(-1, false) == 0
                        && power.receiveEnergy(Integer.MAX_VALUE, true) == ENERGY - before
                        && power.getEnergyStored() == before,
                "FE simulation/clamping changed storage");
        LOG.info(
                "OIL_TEST_MUTATION FE pos={} before={} after={} {}",
                be.getBlockPos(),
                before,
                ENERGY,
                EVIDENCE);
        require(
                power.receiveEnergy(Integer.MAX_VALUE, false) == ENERGY - before
                        && power.getEnergyStored() == ENERGY,
                "FE readback mismatch");
        require(
                power.extractEnergy(1, false) == 0 && power.getEnergyStored() == ENERGY,
                "FE output unexpectedly accepted");
    }

    private static void assertRoute(ServerPlayer player, OilExtractorBlockEntity be) {
        assertRoute(player, be, 1);
    }

    private static void assertRoute(ServerPlayer player, OilExtractorBlockEntity be, int first) {
        for (int i = first; i <= DEPTH; i++) {
            BlockPos pos = be.getBlockPos().below(i);
            home(player.serverLevel(), pos);
            require(
                    player.level().getBlockState(pos).is(i == DEPTH ? Blocks.BEDROCK : Blocks.STONE)
                            && player.level().getBlockEntity(pos) == null,
                    "Virtual well changed terrain at " + pos);
        }
    }

    /** Pending stores the actual before image before the ordinary command starts work. */
    private static void begin(
            ServerPlayer player, OilExtractorBlockEntity be, String kind, int action)
            throws ReflectiveOperationException {
        testResult(player, kind, false);
        CompoundTag pending =
                record(player).contains("Pending")
                        ? record(player).getCompound("Pending")
                        : new CompoundTag();
        pending.putString("Kind", kind);
        pending.putLong("Tick", player.level().getGameTime());
        pending.putUUID("Session", SESSION);
        pending.put("Before", nbt(player, be));
        pending.putInt("CDG", realOil(player.serverLevel(), be.getBlockPos()).read());
        record(player).put("Pending", pending);
        LOG.info(
                "OIL_TEST_BEFORE kind={} tick={} realCDG={} fullNBT={} {}",
                kind,
                pending.getLong("Tick"),
                pending.getInt("CDG"),
                pending.getCompound("Before"),
                EVIDENCE);
        command(player, be, action);
        require(
                nbt(player, be).getInt("Mode")
                        == switch (action) {
                            case OilMenu.SURVEY -> 1;
                            case OilMenu.BUILD -> 2;
                            case OilMenu.PUMP -> 3;
                            default -> 0;
                        },
                "Machine command rejected");
    }

    private static void boundary(ServerPlayer player, OilExtractorBlockEntity be, String kind)
            throws ReflectiveOperationException {
        idle(player);
        testResult(player, kind, false);
        CompoundTag saved = nbt(player, be);
        require(
                saved.getBoolean("Well")
                        && saved.getBoolean("Surveyed")
                        && saved.getInt("Depth") == DEPTH,
                "Build the real shallow well first");
        assertRoute(player, be);
        pause(player, be);
        power(player, be);
        CompoundTag original = nbt(player, be);
        int originalCDG = realOil(player.serverLevel(), be.getBlockPos()).read();
        require(originalCDG > 0, "Finite reservoir is exhausted; use a fresh fixture");
        require(originalCDG != Integer.MAX_VALUE, "Fixture must have finite CDG reservoir");
        if (kind.equals("race"))
            require(
                    originalCDG > 1,
                    "Claim callback must actually deplete a balance greater than 1mB");
        if (kind.equals("fuel-race") || kind.equals("fuel-path"))
            require(
                    originalCDG >= 100
                            && be.fluidOutlet().getTankCapacity(0) - tankAmount(player, original)
                                    >= 100,
                    "Fuel callback fixture needs at least 100mB available and 100mB tank space for a 200FE request");
        // Arm rollback before the first injected mutation, including a failed CDG/NBT readback.
        CompoundTag pending = new CompoundTag();
        pending.putString("Kind", kind);
        pending.put("Before", original.copy());
        if (!kind.equals("pump")) {
            pending.put("Original", original);
            pending.putInt("OriginalCDG", originalCDG);
        }
        record(player).put("Pending", pending);
        try {
            if (kind.equals("fuel-race") || kind.equals("fuel-path")) {
                var items =
                        player.serverLevel()
                                .getCapability(
                                        Capabilities.ItemHandler.BLOCK, be.getBlockPos(), null);
                require(
                        items != null
                                && items.getStackInSlot(0).is(Items.COAL)
                                && items.getStackInSlot(0).getCount() > 0,
                        "Fuel callback fixture requires existing coal");
                CompoundTag unpowered = original.copy();
                unpowered.putInt("Energy", 10);
                unpowered.putInt("BurnTicks", 0);
                loadBoundary(player, be, unpowered, "fuel-race ARTIFICIAL nearly empty FE buffer");
            } else if (kind.equals("full")) {
                CompoundTag filled = original.copy();
                var tank =
                        new net.neoforged.neoforge.fluids.capability.templates.FluidTank(CAPACITY);
                tank.setFluid(new FluidStack(oil(), CAPACITY));
                filled.put("Tank", tank.writeToNBT(player.registryAccess(), new CompoundTag()));
                loadBoundary(player, be, filled, "full ARTIFICIAL tank");
            } else if (kind.equals("one"))
                seed(player, be.getBlockPos(), 1, "boundary one ARTIFICIAL reservoir");
            else if (kind.equals("blocked") || kind.equals("water") || kind.equals("be"))
                place(
                        player.serverLevel(),
                        be.getBlockPos().below(),
                        switch (kind) {
                            case "blocked" -> Blocks.OBSIDIAN.defaultBlockState();
                            case "water" -> Blocks.WATER.defaultBlockState();
                            default -> Blocks.CHEST.defaultBlockState();
                        });
            if (kind.equals("blocked") || kind.equals("water") || kind.equals("be")) {
                BlockPos obstruction = be.getBlockPos().below();
                pending.putString(
                        "BarrierState", player.level().getBlockState(obstruction).toString());
                var barrier = player.level().getBlockEntity(obstruction);
                if (kind.equals("be")) require(barrier != null, "Fixture chest BE was not created");
                if (barrier != null)
                    pending.put(
                            "BarrierNBT", barrier.saveWithFullMetadata(player.registryAccess()));
            }
            begin(player, be, kind, kind.equals("paused") ? OilMenu.PAUSE : OilMenu.PUMP);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            try {
                cleanup(player, be, pending);
            } catch (ReflectiveOperationException | RuntimeException restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            throw failure;
        }
    }

    private static void loadBoundary(
            ServerPlayer player, OilExtractorBlockEntity be, CompoundTag saved, String reason) {
        guard(player.createCommandSourceStack());
        home(player.serverLevel(), be.getBlockPos());
        LOG.info(
                "OIL_TEST_MUTATION explicit fixture NBT boundary reason={} pos={} before={} after={} {}",
                reason,
                be.getBlockPos(),
                nbt(player, be),
                saved,
                EVIDENCE);
        be.loadWithComponents(saved.copy(), player.registryAccess());
        be.setChanged();
        require(nbt(player, be).equals(saved), "Boundary NBT readback mismatch");
    }

    private static void check(ServerPlayer player, OilExtractorBlockEntity be, String expected)
            throws ReflectiveOperationException {
        CompoundTag pending = record(player).getCompound("Pending");
        require(pending.contains("Before"), "No pending test");
        String kind = pending.getString("Kind");
        require(expected == null || expected.equals(kind), "Pending action is " + kind);
        require(
                pending.hasUUID("Session") && SESSION.equals(pending.getUUID("Session")),
                "Pending test crossed JVMs; abort and repeat");
        long elapsed = player.level().getGameTime() - pending.getLong("Tick");
        int minimum = kind.equals("survey") ? 100 : kind.equals("build") ? DEPTH * 10 : 40;
        require(
                elapsed >= minimum,
                "Wait for real ticks: elapsed=" + elapsed + " minimum=" + minimum);
        CompoundTag before = pending.getCompound("Before");
        CompoundTag after = nbt(player, be);
        int remaining = realOil(player.serverLevel(), be.getBlockPos()).read();
        int delta = be.fluidOutlet().getFluidInTank(0).getAmount() - tankAmount(player, before);
        int consumed = before.getInt("Energy") - after.getInt("Energy");
        LOG.info(
                "OIL_TEST_AFTER kind={} elapsedRealTicks={} realCDGBefore={} realCDGAfter={} oilDelta={} feDelta={} fullNBT={} {}",
                kind,
                elapsed,
                pending.getInt("CDG"),
                remaining,
                delta,
                consumed,
                after,
                EVIDENCE);
        // Too-early calls above leave the test armed. Completed checks always freeze and clean up,
        // including failed assertions, so event cancellation and injected blocks cannot linger.
        try {
            require(
                    remaining >= 0
                            && after.getInt("Energy") >= 0
                            && after.getInt("Energy") <= ENERGY,
                    "Negative/out-of-range accounting");
            if (!kind.equals("fuel-race") && !kind.equals("fuel-path"))
                require(
                        java.util.Objects.equals(before.get("Inventory"), after.get("Inventory"))
                                && before.getInt("BurnTicks") == after.getInt("BurnTicks"),
                        "Unexpected fuel consumption with sufficient FE");
            if (kind.equals("fuel-race") || kind.equals("fuel-path")) {
                var expectedItems = new net.neoforged.neoforge.items.ItemStackHandler(2);
                expectedItems.deserializeNBT(
                        player.registryAccess(), before.getCompound("Inventory"));
                require(
                        expectedItems.getStackInSlot(0).is(Items.COAL)
                                && expectedItems.extractItem(0, 1, false).getCount() == 1,
                        "Fuel callback fixture must start with an ordinary coal item");
                require(
                        expectedItems
                                .serializeNBT(player.registryAccess())
                                .equals(after.getCompound("Inventory")),
                        "Fuel callback must consume exactly one coal and preserve the complete remainder slot");
            }
            if (kind.equals("survey")) {
                require(
                        after.getBoolean("Surveyed")
                                && after.getInt("Survey") == 100
                                && after.getInt("LastKnown") == remaining
                                && after.getInt("Mode") == 0
                                && after.getInt("Status") == 2,
                        "Survey did not finish through real ticks");
                require(
                        consumed == (100 - before.getInt("Survey")) * 10
                                && delta == 0
                                && remaining == pending.getInt("CDG"),
                        "Survey accounting mismatch");
            } else if (kind.equals("build")) {
                require(
                        after.getBoolean("Well")
                                && after.getInt("Depth") == DEPTH
                                && after.getInt("Mode") == 0
                                && after.getInt("Status") == 5,
                        "Virtual well did not reach shallow bedrock");
                require(
                        consumed == (DEPTH - before.getInt("Depth")) * 80
                                && delta == 0
                                && remaining == pending.getInt("CDG"),
                        "Build accounting mismatch");
            } else if (kind.equals("fuel-race")) {
                require(
                        pending.getInt("ExternalWrites") == 1,
                        "Fuel callback did not run exactly once");
                require(
                        delta == 1 && remaining == 0 && after.getInt("Energy") == 198,
                        "Fuel callback depletion must yield only 1mB, costing 2FE from the replenished 200FE buffer");
                require(
                        after.getInt("BurnTicks") == pending.getInt("FuelDuration") - 19,
                        "Expected exactly 19 ordinary fuel ticks for 190FE replenishment");
                require(
                        after.getInt("Status") == 3 && after.getInt("Mode") == 0,
                        "Fuel-depleted reservoir must stop DRY");
            } else if (kind.equals("fuel-path")) {
                require(
                        pending.getInt("ExternalWrites") == 1,
                        "Fuel callback did not change the well exactly once");
                require(
                        delta == 0
                                && remaining == pending.getInt("CDG")
                                && after.getInt("Energy") == 200,
                        "Fuel callback obstruction must preserve oil/CDG and retain generated FE");
                require(
                        after.getInt("BurnTicks") == pending.getInt("FuelDuration") - 19,
                        "Expected exactly 19 ordinary fuel ticks before detecting the changed well");
                require(
                        after.getInt("Status") == BLOCKED && after.getInt("Mode") == 0,
                        "Fuel callback obstruction must stop BLOCKED before committing oil");
            } else if (kind.equals("race")) {
                require(
                        pending.getInt("ExternalWrites") == 1,
                        "Reservoir callback did not run exactly once");
                require(
                        delta == 1 && remaining == 0 && consumed == 2,
                        "Claim callback depletion must leave only 1mB/2FE for the extractor");
                require(
                        after.getInt("Status") == 3 && after.getInt("Mode") == 0,
                        "Callback-depleted reservoir must stop DRY");
            } else if (kind.equals("pump") || kind.equals("one")) {
                require(
                        delta > 0
                                && pending.getInt("CDG") - remaining == delta
                                && consumed == delta * 2,
                        "Oil conservation/FE debit mismatch");
                if (kind.equals("one"))
                    require(
                            delta == 1
                                    && remaining == 0
                                    && after.getInt("Status") == 3
                                    && after.getInt("Mode") == 0,
                            "1mB reservoir must debit 1mB/2FE, persist zero, then stop DRY");
            } else {
                require(
                        delta == 0 && remaining == pending.getInt("CDG") && consumed == 0,
                        "Rejected work changed oil/FE");
                int status =
                        switch (kind) {
                            case "full" -> FULL;
                            case "protected" -> PROTECTED;
                            case "paused" -> PAUSED;
                            default -> BLOCKED;
                        };
                require(
                        after.getInt("Status") == status,
                        "Expected status=" + status + " actual=" + after.getInt("Status"));
                if (kind.equals("protected"))
                    require(
                            pending.getInt("Cancellations") > 0,
                            "Protection hook was never reached");
                if (kind.equals("paused"))
                    require(before.equals(after), "Paused full NBT changed over real ticks");
                if (kind.equals("full"))
                    require(tankAmount(player, after) == CAPACITY, "Full tank changed");
            }
            if (pending.contains("BarrierState")) {
                BlockPos obstruction = be.getBlockPos().below();
                require(
                        pending.getString("BarrierState")
                                .equals(player.level().getBlockState(obstruction).toString()),
                        "Obstruction block state changed");
                var barrier = player.level().getBlockEntity(obstruction);
                if (pending.contains("BarrierNBT"))
                    require(
                            barrier != null
                                    && pending.getCompound("BarrierNBT")
                                            .equals(
                                                    barrier.saveWithFullMetadata(
                                                            player.registryAccess())),
                            "Obstruction BE type/full NBT changed");
                else require(barrier == null, "Unexpected obstruction BE");
                assertRoute(player, be, 2);
            } else assertRoute(player, be);
        } finally {
            cleanup(player, be, pending);
        }
        testResult(player, kind, true);
        LOG.info("OIL_TEST_ASSERT_PASS kind={} elapsedRealTicks={} {}", kind, elapsed, EVIDENCE);
    }

    private static int tankAmount(ServerPlayer player, CompoundTag saved) {
        return new net.neoforged.neoforge.fluids.capability.templates.FluidTank(CAPACITY)
                .readFromNBT(player.registryAccess(), saved.getCompound("Tank"))
                .getFluidAmount();
    }

    private static void abort(ServerPlayer player, OilExtractorBlockEntity be)
            throws ReflectiveOperationException {
        CompoundTag pending = record(player).getCompound("Pending");
        require(pending.contains("Before"), "No pending test");
        LOG.info(
                "OIL_TEST_MUTATION abort kind={} no assertion accepted {}",
                pending.getString("Kind"),
                EVIDENCE);
        cleanup(player, be, pending);
    }

    private static void cleanup(
            ServerPlayer player, OilExtractorBlockEntity be, CompoundTag pending)
            throws ReflectiveOperationException {
        pause(player, be);
        String kind = pending.getString("Kind");
        if (kind.equals("blocked")
                || kind.equals("water")
                || kind.equals("be")
                || kind.equals("fuel-path"))
            place(player.serverLevel(), be.getBlockPos().below(), Blocks.STONE.defaultBlockState());
        if (pending.contains("Original")) {
            if (kind.equals("one") || kind.equals("race") || kind.equals("fuel-race"))
                seed(
                        player,
                        be.getBlockPos(),
                        pending.getInt("OriginalCDG"),
                        "boundary " + kind + " RESTORE artificial reservoir");
            loadBoundary(
                    player,
                    be,
                    pending.getCompound("Original"),
                    "restore pre-boundary paused machine");
        }
        record(player).remove("Pending");
        assertRoute(player, be);
    }

    /** Only an explicitly armed disposable fixture may cancel a claim or change its test reservoir. */
    private static void beforeBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getPlayer() instanceof FakePlayer)
                || !event.getPlayer().getGameProfile().getName().equals("StarfoldOil")) return;
        ServerPlayer owner =
                level.getServer().getPlayerList().getPlayer(event.getPlayer().getUUID());
        if (owner == null) return;
        CompoundTag armed = record(owner).getCompound("Pending");
        String kind = armed.getString("Kind");
        if ((!kind.equals("protected") && !kind.equals("race"))
                || !armed.hasUUID("Session")
                || !SESSION.equals(armed.getUUID("Session"))) return;
        try {
            guard(owner.createCommandSourceStack());
            OilExtractorBlockEntity be = fixture(owner);
            if (be.getLevel() != level || !event.getPos().equals(be.getBlockPos().below())) return;
            CompoundTag pending = record(owner).getCompound("Pending");
            if (kind.equals("race")) {
                if (pending.getInt("ExternalWrites") == 0) {
                    // Model another authorized operation during the synchronous claim callback.
                    seed(owner, be.getBlockPos(), 1, "race callback ARTIFICIAL depletion to 1mB");
                    pending.putInt("ExternalWrites", 1);
                }
            } else {
                pending.putInt("Cancellations", pending.getInt("Cancellations") + 1);
                LOG.info(
                        "OIL_TEST_MUTATION cancel real BreakEvent pos={} owner={} {}",
                        event.getPos(),
                        owner.getUUID(),
                        EVIDENCE);
                event.setCanceled(true);
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            LOG.error("OIL_TEST_FAIL protection fixture guard {}", EVIDENCE, failure);
        }
    }

    private static void fuelCallback(
            net.neoforged.neoforge.event.furnace.FurnaceFuelBurnTimeEvent event) {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) return;
        for (ServerPlayer owner : server.getPlayerList().getPlayers()) {
            CompoundTag pending = record(owner).getCompound("Pending");
            if ((!pending.getString("Kind").equals("fuel-race")
                            && !pending.getString("Kind").equals("fuel-path"))
                    || pending.getInt("ExternalWrites") != 0
                    || !pending.hasUUID("Session")
                    || !SESSION.equals(pending.getUUID("Session"))) continue;
            try {
                guard(owner.createCommandSourceStack());
                var be = fixture(owner);
                var items =
                        owner.serverLevel()
                                .getCapability(
                                        Capabilities.ItemHandler.BLOCK, be.getBlockPos(), null);
                if (items == null || items.getStackInSlot(0) != event.getItemStack()) continue;
                require(event.getBurnTime() >= 19, "Fixture fuel duration too short");
                if (pending.getString("Kind").equals("fuel-path")) {
                    BlockPos obstruction = be.getBlockPos().below();
                    place(owner.serverLevel(), obstruction, Blocks.WATER.defaultBlockState());
                    pending.putString(
                            "BarrierState", owner.level().getBlockState(obstruction).toString());
                } else
                    seed(
                            owner,
                            be.getBlockPos(),
                            1,
                            "fuel-race callback ARTIFICIAL depletion to 1mB");
                pending.putInt("ExternalWrites", 1);
                pending.putInt("FuelDuration", event.getBurnTime());
            } catch (ReflectiveOperationException | RuntimeException failure) {
                LOG.error("OIL_TEST_FAIL fuel callback fixture guard {}", EVIDENCE, failure);
            }
        }
    }

    /** Bucket helper and the same drain capability pipes use; no installed pipe network is claimed. */
    private static void outlet(ServerPlayer player, OilExtractorBlockEntity be)
            throws ReflectiveOperationException {
        idle(player);
        testResult(player, "outlet", false);
        pause(player, be);
        require(
                be.fluidOutlet().getFluidInTank(0).getAmount() >= 1037,
                "Pump at least 1037mB before outlet");
        CompoundTag before = nbt(player, be);
        int reservoir = realOil(player.serverLevel(), be.getBlockPos()).read();
        for (int side = -1; side < Direction.values().length; side++) {
            Direction direction = side < 0 ? null : Direction.values()[side];
            IFluidHandler handler =
                    player.serverLevel()
                            .getCapability(
                                    Capabilities.FluidHandler.BLOCK, be.getBlockPos(), direction);
            require(handler != null, "Missing fluid capability side=" + direction);
            LOG.info(
                    "OIL_TEST_MUTATION attempt external fill side={} crude+water SIMULATE/EXECUTE {}",
                    direction,
                    EVIDENCE);
            for (Fluid fluid : new Fluid[] {oil(), Fluids.WATER})
                for (FluidAction action : FluidAction.values())
                    require(
                            handler.fill(new FluidStack(fluid, 1000), action) == 0,
                            "External fill accepted side=" + direction);
            require(
                    handler.drain(37, FluidAction.SIMULATE).getAmount() == 37,
                    "Simulated pipe drain failed");
            require(
                    before.equals(nbt(player, be)),
                    "Simulation/rejected external fill changed machine NBT");
        }
        var bucketPreview =
                FluidUtil.tryFillContainer(
                        new ItemStack(Items.BUCKET), be.fluidOutlet(), 1000, player, false);
        require(
                bucketPreview.isSuccess() && before.equals(nbt(player, be)),
                "Bucket simulation failed/mutated source");
        IFluidHandler pipe =
                player.serverLevel()
                        .getCapability(
                                Capabilities.FluidHandler.BLOCK, be.getBlockPos(), Direction.DOWN);
        require(pipe != null, "Missing lower pipe outlet");
        LOG.info(
                "OIL_TEST_MUTATION capability pipe-interface drain=37; fixture empty bucket fill=1000 {}",
                EVIDENCE);
        FluidStack drained = pipe.drain(37, FluidAction.EXECUTE);
        require(
                drained.getAmount() == 37 && drained.getFluid() == oil(),
                "Pipe-interface output mismatch");
        var bucket =
                FluidUtil.tryFillContainer(
                        new ItemStack(Items.BUCKET), be.fluidOutlet(), 1000, player, true);
        require(bucket.isSuccess(), "Actual bucket fill failed");
        FluidStack contained =
                FluidUtil.getFluidContained(bucket.getResult()).orElse(FluidStack.EMPTY);
        require(
                contained.getAmount() == 1000 && contained.getFluid() == oil(),
                "Actual bucket contents mismatch");
        // Retain the actual returned bucket as evidence; 37mB is explicitly discarded by this
        // fixture sink.
        record(player).put("OutputBucket", bucket.getResult().save(player.registryAccess()));
        record(player).putInt("Exported", record(player).getInt("Exported") + 1037);
        CompoundTag after = nbt(player, be);
        require(
                tankAmount(player, before) - tankAmount(player, after) == 1037
                        && before.getInt("Energy") == after.getInt("Energy")
                        && realOil(player.serverLevel(), be.getBlockPos()).read() == reservoir,
                "Output accounting changed FE/CDG or lost oil");
        CompoundTag expected = before.copy();
        expected.put("Tank", after.getCompound("Tank").copy());
        require(expected.equals(after), "Output changed non-tank NBT");
        testResult(player, "outlet", true);
        LOG.info(
                "OIL_TEST_ASSERT_PASS outlet bucket={} pipeInterfaceDiscarded=37 realCDG={} {}",
                bucket.getResult(),
                reservoir,
                EVIDENCE);
    }

    private static void checkpoint(ServerPlayer player, OilExtractorBlockEntity be)
            throws ReflectiveOperationException {
        idle(player);
        for (String kind :
                new String[] {
                    "survey",
                    "build",
                    "pump",
                    "outlet",
                    "full",
                    "one",
                    "blocked",
                    "water",
                    "be",
                    "protected",
                    "paused"
                })
            require(
                    record(player).getCompound("Passed").getBoolean(kind),
                    "Required case has not passed: " + kind);
        require(
                be.fluidOutlet().getFluidInTank(0).getAmount() > 0,
                "Checkpoint needs retained real pumped oil");
        pause(player, be);
        power(player, be);
        CompoundTag saved = nbt(player, be);
        int remaining = realOil(player.serverLevel(), be.getBlockPos()).read();
        int exported = record(player).getInt("Exported");
        require(
                remaining + tankAmount(player, saved) + exported
                        == record(player).getInt("ArtificialCDG"),
                "Whole fixture conservation failed: real CDG + tank + audited exports != artificial initial reservoir");
        LOG.info(
                "OIL_TEST_ASSERT_PASS total initialArtificial=5000 realCDG={} tank={} auditedExports={} {}",
                remaining,
                tankAmount(player, saved),
                exported,
                EVIDENCE);
        record(player).put("Checkpoint", saved.copy());
        record(player).putInt("CheckpointCDG", remaining);
        record(player).putUUID("CheckpointSession", SESSION);
        LOG.info(
                "OIL_TEST_MUTATION save paused checkpoint fullNBT={} realCDG={} session={} {}",
                saved,
                record(player).getInt("CheckpointCDG"),
                SESSION,
                EVIDENCE);
        require(
                player.getServer().saveEverything(false, true, true),
                "Server checkpoint save failed");
    }

    private static void persistence(ServerPlayer player, OilExtractorBlockEntity be)
            throws ReflectiveOperationException {
        idle(player);
        CompoundTag record = record(player);
        require(
                record.contains("Checkpoint") && record.hasUUID("CheckpointSession"),
                "Run save before restart");
        require(
                !SESSION.equals(record.getUUID("CheckpointSession")),
                "persist requires a new JVM, not a world reload");
        CompoundTag actual = nbt(player, be);
        require(
                actual.getInt("Mode") == 0 && actual.getInt("Status") == PAUSED,
                "Checkpoint did not remain paused");
        require(
                record.getCompound("Checkpoint").equals(actual),
                "Full machine NBT differs from checkpoint: expected="
                        + record.getCompound("Checkpoint")
                        + " actual="
                        + actual);
        int remaining = realOil(player.serverLevel(), be.getBlockPos()).read();
        require(
                remaining == record.getInt("CheckpointCDG"),
                "Real CDG SavedData differs after JVM restart");
        assertRoute(player, be);
        LOG.info(
                "OIL_TEST_ASSERT_PASS persist fullNBT=equal realCDG={} oldSession={} newSession={} {}",
                remaining,
                record.getUUID("CheckpointSession"),
                SESSION,
                EVIDENCE);
    }

    private static void snapshot(
            CommandSourceStack source, ServerPlayer player, OilExtractorBlockEntity be)
            throws ReflectiveOperationException {
        CompoundTag saved = nbt(player, be);
        String text =
                "OIL_TEST_SNAPSHOT "
                        + EVIDENCE
                        + " pos="
                        + be.getBlockPos()
                        + " tick="
                        + player.level().getGameTime()
                        + " FE="
                        + be.energyStorage().getEnergyStored()
                        + " tank="
                        + be.fluidOutlet().getFluidInTank(0)
                        + " actualCDG="
                        + realOil(player.serverLevel(), be.getBlockPos()).read()
                        + " naturalCDGBeforeFixture="
                        + record(player).getInt("NaturalCDG")
                        + " ARTIFICIAL initialCDG=5000"
                        + " pending="
                        + record(player).getCompound("Pending").getString("Kind")
                        + " fullNBT="
                        + saved;
        LOG.info("{}", text);
        source.sendSuccess(() -> Component.literal(text), false);
    }

    private static Fluid oil() {
        Fluid oil =
                BuiltInRegistries.FLUID.get(
                        ResourceLocation.parse("createdieselgenerators:crude_oil"));
        require(oil != Fluids.EMPTY, "Real CDG crude oil is unavailable");
        return oil;
    }

    /** Reflection is confined to the pinned real CDG API, never machine internals. */
    private record RealOil(Object data, Method reader, Method writer, ChunkPos chunk) {
        int read() throws ReflectiveOperationException {
            return (Integer) reader.invoke(data, chunk);
        }

        void seed(int amount) throws ReflectiveOperationException {
            require(amount >= 0, "Negative artificial reservoir");
            writer.invoke(
                    data, chunk, amount); // CDG setChunkAmount marks the real SavedData dirty.
            require(read() == amount, "Real CDG seed readback mismatch");
        }
    }

    private static RealOil realOil(ServerLevel level, BlockPos pos)
            throws ReflectiveOperationException {
        home(level, pos);
        oil();
        Class<?> type = Class.forName("com.jesz.createdieselgenerators.world.OilChunksSavedData");
        Method read = type.getMethod("getChunkOilAmount", ChunkPos.class);
        require(read.getReturnType() == int.class, "Pinned CDG read API changed");
        Object data = type.getMethod("load", ServerLevel.class).invoke(null, level);
        require(data instanceof SavedData, "CDG did not return real SavedData");
        return new RealOil(
                data,
                read,
                type.getMethod("setChunkAmount", ChunkPos.class, int.class),
                new ChunkPos(PlanetStorage.address(pos).owner().block()));
    }

    private static void seed(ServerPlayer player, BlockPos pos, int amount, String reason)
            throws ReflectiveOperationException {
        guard(player.createCommandSourceStack());
        require(
                reason.equals("setup artificial reservoir")
                        || reason.startsWith("boundary one ")
                        || reason.equals("race callback ARTIFICIAL depletion to 1mB")
                        || reason.equals("boundary race RESTORE artificial reservoir")
                        || reason.equals("fuel-race callback ARTIFICIAL depletion to 1mB")
                        || reason.equals("boundary fuel-race RESTORE artificial reservoir"),
                "CDG writes only at explicit fixture boundaries");
        RealOil actual = realOil(player.serverLevel(), pos);
        LOG.info(
                "OIL_TEST_MUTATION REAL CDG setChunkAmount reason={} chunk={} before={} after={} ARTIFICIAL {}",
                reason,
                actual.chunk(),
                actual.read(),
                amount,
                EVIDENCE);
        actual.seed(amount);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private DevOilProbe() {}
}
