package dev.starfold.dev;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.bearing.WindmillBearingBlockEntity.RotationDirection;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.redstone.link.LinkBehaviour;
import com.simibubi.create.content.redstone.link.RedstoneLinkBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import dev.ryanhcode.sable.api.block.propeller.BlockEntityPropeller;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.starfold.planet.PlanetLevels;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Controlled physics fixture, NOT ordinary-survival or flight acceptance. No tick/force hooks. */
public final class DevTransportProbe {
    private static final Logger LOG = LoggerFactory.getLogger("starfold-probe");
    private static final String KEY = "starfold_probe:stage2_transport";
    private static final UUID SESSION = UUID.randomUUID();
    private static final String ENGINE = "simulated:red_portable_engine";
    private static final String BEARING = "aeronautics:gyroscopic_propeller_bearing";
    private static final String THROTTLE = "simulated:throttle_lever";
    private static final String ASSEMBLER = "simulated:physics_assembler";
    private static final float STOPPED_ROTOR_DEGREES_PER_TICK = 0.001f;
    private static boolean initialized;

    /** Main probe owner calls this alongside the other initialize methods. */
    public static void initialize() {
        if (initialized) return;
        initialized = true;
        NeoForge.EVENT_BUS.addListener(
                (RegisterCommandsEvent event) ->
                        event.getDispatcher()
                                .register(
                                        Commands.literal("transporttest")
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
                                                                                        "snapshot",
                                                                                        "rotor",
                                                                                        "assemble",
                                                                                        "postrotor",
                                                                                        "directions",
                                                                                        "release-start",
                                                                                        "released",
                                                                                        "rejoin",
                                                                                        "fuel",
                                                                                        "full",
                                                                                        "empty",
                                                                                        "checkpoint",
                                                                                        "compare",
                                                                                        "persist"
                                                                                    })
                                                                                builder.suggest(
                                                                                        action);
                                                                            return builder
                                                                                    .buildFuture();
                                                                        })
                                                                .executes(
                                                                        c ->
                                                                                run(
                                                                                        c
                                                                                                .getSource(),
                                                                                        StringArgumentType
                                                                                                .getString(
                                                                                                        c,
                                                                                                        "action"),
                                                                                        "")))
                                                .then(
                                                        Commands.literal("bind")
                                                                .then(
                                                                        Commands.argument(
                                                                                        "uuid",
                                                                                        StringArgumentType
                                                                                                .word())
                                                                                .executes(
                                                                                        c ->
                                                                                                run(
                                                                                                        c
                                                                                                                .getSource(),
                                                                                                        "bind",
                                                                                                        StringArgumentType
                                                                                                                .getString(
                                                                                                                        c,
                                                                                                                        "uuid")))))
                                                .then(
                                                        Commands.literal("throttle")
                                                                .then(
                                                                        Commands.argument(
                                                                                        "signal",
                                                                                        IntegerArgumentType
                                                                                                .integer(
                                                                                                        0,
                                                                                                        15))
                                                                                .executes(
                                                                                        c ->
                                                                                                run(
                                                                                                        c
                                                                                                                .getSource(),
                                                                                                        "throttle",
                                                                                                        Integer
                                                                                                                .toString(
                                                                                                                        IntegerArgumentType
                                                                                                                                .getInteger(
                                                                                                                                        c,
                                                                                                                                        "signal"))))))));
    }

    private static int run(CommandSourceStack source, String action, String argument)
            throws CommandSyntaxException {
        try {
            guard(source);
            ServerPlayer player = source.getPlayerOrException();
            if (action.equals("setup")) setup(player);
            else if (action.equals("bind")) bind(player, UUID.fromString(argument));
            else
                switch (action) {
                    case "snapshot" -> {}
                    case "rotor" -> {
                        require(!record(player).hasUUID("Body"), "Rotor setup is pre-hull only");
                        assembleRotor(player);
                    }
                    case "postrotor" -> {
                        body(player); // Recovery must address the explicitly bound hull.
                        assembleRotor(player);
                    }
                    case "directions" -> liftDirections(player, false);
                    case "release-start" -> releaseStart(player);
                    case "released" -> released(player);
                    case "rejoin" -> rejoin(player);
                    case "assemble" -> {
                        require(
                                !record(player).hasUUID("Body"),
                                "Already bound; refusing assembly/disassembly toggle");
                        require(
                                (int) nativeCall(one(parts(player), THROTTLE), "getState") == 15,
                                "Set throttle 15 first");
                        BlockEntity bearing = one(parts(player), BEARING);
                        rotorState(player, true);
                        requireRotorStopped(bearing);
                        require(
                                !record(player).getBoolean("AssemblyPending"),
                                "Assembly already requested; inspect and bind the UUID");
                        BlockEntity assembler = one(parts(player), ASSEMBLER);
                        nativeCall(assembler, "assembleOrDisassemble");
                        Object failure = nativeCall(assembler, "getLastAssemblyException");
                        require(failure == null, "Native assembly failed: " + failure);
                        record(player).putBoolean("AssemblyPending", true);
                        // Native assembly can be deferred. Never infer a new hull or bind the
                        // nearest ship.
                        LOG.info(
                                "TRANSPORT_TEST assembly_requested; snapshot lists live UUIDs; bind explicitly after native assembly");
                    }
                    case "throttle" -> {
                        BlockEntity lever = one(parts(player), THROTTLE);
                        int signal = Integer.parseInt(argument);
                        if (signal < 15) {
                            body(player);
                            liftDirections(player, false);
                            rotorState(player, true);
                        }
                        // Native setSignal takes pre-inversion input; command takes actual output.
                        boolean inverted =
                                lever.getBlockState().getValues().entrySet().stream()
                                        .anyMatch(
                                                e ->
                                                        e.getKey().getName().equals("inverted")
                                                                && Boolean.TRUE.equals(
                                                                        e.getValue()));
                        nativeCall(lever, "setSignal", int.class, inverted ? 15 - signal : signal);
                        require(
                                (int) nativeCall(lever, "getState") == signal,
                                "Native throttle readback mismatch");
                    }
                    case "fuel" -> fuel(player);
                    case "full", "empty" -> cargoFixture(player, action.equals("full"));
                    case "checkpoint" -> checkpoint(player);
                    case "compare", "persist" -> compare(player, action.equals("persist"));
                    default -> throw new IllegalArgumentException("Unknown action " + action);
                }
            snapshot(player);
            String status =
                    action.equals("assemble") ? "TRANSPORT_TEST_PENDING" : "TRANSPORT_TEST_OK";
            String message =
                    status
                            + " action="
                            + action
                            + " fixture_only=true gameplay_acceptance=UNTESTED";
            source.sendSuccess(() -> Component.literal(message), false);
            LOG.info(message);
            return 1;
        } catch (Exception error) {
            String message =
                    "TRANSPORT_TEST_FAIL action=" + action + " reason=" + error.getMessage();
            LOG.error(message, error);
            source.sendFailure(Component.literal(message));
            throw new SimpleCommandExceptionType(Component.literal(message)).create();
        }
    }

    private static void guard(CommandSourceStack source) {
        require(source.hasPermission(2), "Permission 2 required");
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
    }

    private static CompoundTag record(ServerPlayer player) {
        CompoundTag tag = player.getPersistentData().getCompound(KEY);
        require(tag.contains("Base", Tag.TAG_LONG), "Run setup first");
        require(
                tag.getString("Dimension").equals(player.level().dimension().location().toString()),
                "Return to fixture dimension");
        return tag;
    }

    /** All coordinates relative to deck centre. No clearing, terrain edits, teleport or motion writes. */
    private static Map<BlockPos, String> layout() {
        Map<BlockPos, String> blocks = new LinkedHashMap<>();
        for (int x = -2; x <= 2; x++)
            for (int z = -3; z <= 3; z++) at(blocks, x, 0, z, "minecraft:oak_slab[type=bottom]");
        for (int y = 1; y <= 3; y++) at(blocks, 0, y, 0, "minecraft:oak_planks");
        at(blocks, 0, 4, 0, "create:gearbox[axis=z]");
        at(blocks, 0, 5, 0, BEARING + "[facing=up]");
        at(blocks, 0, 6, 0, "minecraft:oak_planks");
        for (int x = -3; x <= 3; x++)
            for (int z = -3; z <= 3; z++)
                if (x != 0 || z != 0) at(blocks, x, 6, z, "create:white_sail[facing=up]");
        at(blocks, -1, 4, 0, "create:cogwheel[axis=x]");
        at(blocks, -1, 3, 0, "simulated:analog_transmission[axis=x]");
        at(blocks, -2, 3, 0, "create:gearbox[axis=y]");
        at(blocks, -3, 3, 0, "create:shaft[axis=x]");
        at(blocks, -4, 3, 0, ENGINE + "[facing=east]");
        at(blocks, -2, 3, -1, "create:shaft[axis=z]");
        at(blocks, -2, 3, -2, ENGINE + "[facing=south]");
        at(blocks, -1, 2, 1, "minecraft:oak_planks");
        at(blocks, -1, 3, 1, THROTTLE + "[face=floor,facing=north,inverted=false]");
        at(blocks, -1, 1, 1, "minecraft:oak_planks");
        at(blocks, -1, 1, 0, "minecraft:barrel");
        at(blocks, 1, 1, 0, "minecraft:barrel");
        at(blocks, 0, 1, 2, "create:brown_seat");
        at(blocks, -2, 0, 2, "minecraft:oak_planks");
        at(blocks, -2, 1, 2, ASSEMBLER + "[face=floor,facing=north]");
        at(blocks, 0, 2, -1, ENGINE + "[facing=north]");
        at(blocks, 0, 2, -2, "create:gearshift[axis=z]");
        at(blocks, 0, 2, -3, "create:clutch[axis=z]");
        at(blocks, 0, 2, -4, "aeronautics:andesite_propeller[facing=north]");
        at(blocks, -1, 2, 3, ENGINE + "[facing=east]");
        at(blocks, 0, 2, 3, "create:gearshift[axis=x]");
        at(blocks, 1, 2, 3, "create:clutch[axis=x]");
        at(blocks, 2, 2, 3, "aeronautics:andesite_propeller[facing=east]");
        // Native redstone links start unbound. Inverters keep horizontal clutches disconnected.
        for (int x = 1; x <= 4; x++) at(blocks, x, 1, -3, "minecraft:oak_planks");
        at(blocks, 4, 2, -3, "create:redstone_link[facing=up,receiver=true]");
        at(blocks, 3, 2, -3, "minecraft:repeater[facing=east]");
        at(blocks, 2, 2, -3, "minecraft:oak_planks");
        at(blocks, 1, 2, -3, "minecraft:redstone_wall_torch[facing=west]");
        at(blocks, -1, 1, -2, "minecraft:oak_planks");
        at(blocks, -1, 2, -2, "create:redstone_link[facing=up,receiver=true]");
        at(blocks, 1, 1, -1, "minecraft:oak_planks");
        at(blocks, 1, 2, -1, "create:redstone_link[facing=up,receiver=true]");
        at(blocks, 1, 2, 0, "minecraft:repeater[facing=north]");
        at(blocks, 1, 1, 1, "minecraft:oak_planks");
        at(blocks, 1, 2, 1, "minecraft:oak_planks");
        at(blocks, 1, 1, 2, "minecraft:oak_planks");
        at(blocks, 1, 2, 2, "minecraft:redstone_wall_torch[facing=south]");
        at(blocks, 0, 1, 4, "minecraft:oak_planks");
        at(blocks, 0, 2, 4, "create:redstone_link[facing=up,receiver=true]");
        return blocks;
    }

    private static void at(Map<BlockPos, String> blocks, int x, int y, int z, String state) {
        blocks.put(new BlockPos(x, y, z), state);
    }

    private static void setup(ServerPlayer player) throws Exception {
        require(
                !player.getPersistentData().contains(KEY),
                "Fixture already recorded; use a fresh regression world/player");
        ServerLevel level = player.serverLevel();
        BlockPos base = player.blockPosition().offset(8, 0, 0);
        Map<BlockPos, BlockState> states = new LinkedHashMap<>();
        // Resolve every registry/property before the first mutation (optional mods may be absent).
        for (var entry : layout().entrySet())
            states.put(
                    base.offset(entry.getKey()),
                    BlockStateParser.parseForBlock(
                                    BuiltInRegistries.BLOCK.asLookup(), entry.getValue(), false)
                            .blockState());
        for (int x = -5; x <= 5; x++)
            for (int z = -5; z <= 5; z++)
                for (int y = 0; y <= 8; y++) {
                    BlockPos pos = base.offset(x, y, z);
                    require(
                            !level.isOutsideBuildHeight(pos)
                                    && level.getWorldBorder().isWithinBounds(pos),
                            "Outside build bounds " + pos);
                    require(level.hasChunkAt(pos), "Load fixture chunks first: " + pos);
                    require(
                            level.getBlockState(pos).isAir() && level.getBlockEntity(pos) == null,
                            "Fixture clearance must be air: " + pos);
                }
        for (int x = -2; x <= 2; x++)
            for (int z = -3; z <= 3; z++)
                require(
                        !level.getBlockState(base.offset(x, -1, z))
                                .getCollisionShape(level, base.offset(x, -1, z))
                                .isEmpty(),
                        "Candidate needs flat existing ground below deck; no terrain fill provided");
        CompoundTag tag = new CompoundTag();
        tag.putLong("Base", base.asLong());
        tag.putString("Dimension", level.dimension().location().toString());
        // Record before placement so partial setup cannot silently be retried over existing blocks.
        player.getPersistentData().put(KEY, tag);
        // Supports first; updates enabled so normal redstone/kinetics initialize.
        var placements = new ArrayList<>(states.entrySet());
        placements.sort(Comparator.comparingInt(e -> e.getKey().getY()));
        for (var entry : placements) {
            require(
                    level.setBlock(entry.getKey(), entry.getValue(), Block.UPDATE_ALL),
                    "Placement failed " + entry.getKey());
        }
        nativeCall(one(parts(player), THROTTLE), "setSignal", int.class, 15);
        liftDirections(player, true); // No coal exists in the freshly placed engines.
        require(
                level.addFreshEntity(
                        new SuperGlueEntity(
                                level,
                                SuperGlueEntity.span(
                                        base.offset(-4, 0, -4), base.offset(4, 5, 4)))),
                "Hull glue rejected");
        require(
                level.addFreshEntity(
                        new SuperGlueEntity(
                                level,
                                SuperGlueEntity.span(
                                        base.offset(-3, 6, -3), base.offset(3, 6, 3)))),
                "Rotor glue rejected");
        LOG.info(
                "TRANSPORT_TEST fixture base={} bounds={}..{} fuel=none sails=48 engines=4 native_glue=true "
                        + "lift_directions=VERIFIED_BEFORE_FUEL manual_checks=wireless_binding,assembly,thrust_sign gameplay_acceptance=UNTESTED",
                base,
                base.offset(-5, 0, -5),
                base.offset(5, 8, 5));
    }

    private static ServerSubLevel body(ServerPlayer player) {
        CompoundTag tag = record(player);
        require(tag.hasUUID("Body"), "Bind the assembled hull UUID first");
        var container = SubLevelContainer.getContainer(player.serverLevel());
        require(container != null, "No Sable container");
        var hull = container.getSubLevel(tag.getUUID("Body"));
        require(
                hull instanceof ServerSubLevel,
                "Bound hull not live; load it normally (no forced wake)");
        return (ServerSubLevel) hull;
    }

    private static List<BlockEntity> hullParts(ServerSubLevel hull) {
        List<BlockEntity> result = new ArrayList<>();
        for (var holder : hull.getPlot().getLoadedChunks())
            result.addAll(holder.getChunk().getBlockEntities().values());
        result.sort(Comparator.comparingLong(be -> be.getBlockPos().asLong()));
        return result;
    }

    private static List<BlockEntity> parts(ServerPlayer player) {
        if (record(player).hasUUID("Body")) return hullParts(body(player));
        require(
                !record(player).getBoolean("AssemblyPending"),
                "Assembly pending: bind UUID before accessing parts");
        ServerLevel level = player.serverLevel();
        BlockPos base = BlockPos.of(record(player).getLong("Base"));
        List<BlockEntity> result = new ArrayList<>();
        for (BlockPos relative : layout().keySet()) {
            BlockPos pos = base.offset(relative);
            require(level.hasChunkAt(pos), "Fixture chunk is not loaded: " + pos);
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) result.add(be);
        }
        result.sort(Comparator.comparingLong(be -> be.getBlockPos().asLong()));
        return result;
    }

    private static String id(BlockEntity be) {
        return BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()).toString();
    }

    private static BlockEntity one(List<BlockEntity> parts, String id) {
        var matches = parts.stream().filter(be -> id(be).equals(id)).toList();
        require(matches.size() == 1, "Expected one " + id + ", found " + matches.size());
        return matches.getFirst();
    }

    private static List<BlockEntity> barrels(List<BlockEntity> parts) {
        var result = parts.stream().filter(be -> id(be).equals("minecraft:barrel")).toList();
        require(result.size() == 2, "Expected two loaded cargo barrels, found " + result.size());
        return result;
    }

    private static void bind(ServerPlayer player, UUID uuid) {
        require(!record(player).hasUUID("Body"), "Already bound; use the saved UUID");
        var container = SubLevelContainer.getContainer(player.serverLevel());
        require(container != null, "No Sable container");
        var found = container.getSubLevel(uuid);
        require(found instanceof ServerSubLevel, "UUID must identify a live hull");
        var hull = (ServerSubLevel) found;
        var pos = hull.logicalPose().position();
        require(
                player.position().distanceToSqr(pos.x(), pos.y(), pos.z()) <= 64 * 64,
                "Stay within 64 blocks of candidate");
        var parts = hullParts(hull);
        one(parts, BEARING);
        one(parts, THROTTLE);
        one(parts, ASSEMBLER);
        barrels(parts);
        require(
                parts.stream().filter(be -> id(be).equals(ENGINE)).count() == 4,
                "Expected four loaded fuel engines");
        record(player).putUUID("Body", uuid);
        record(player).remove("AssemblyPending");
    }

    private static void fuel(ServerPlayer player) throws Exception {
        body(player);
        require(
                (int) nativeCall(one(parts(player), THROTTLE), "getState") == 15,
                "Set throttle 15 before fuel");
        liftDirections(player, false);
        rotorState(player, true);
        var engines = parts(player).stream().filter(be -> id(be).equals(ENGINE)).toList();
        require(engines.size() == 4, "Expected four engines");
        for (BlockEntity be : engines) {
            var items =
                    player.serverLevel()
                            .getCapability(Capabilities.ItemHandler.BLOCK, be.getBlockPos(), null);
            require(
                    items != null,
                    "No native fuel capability; add ordinary coal by hand at " + be.getBlockPos());
            ItemStack remaining = new ItemStack(Items.COAL, 8);
            for (int slot = 0; slot < items.getSlots() && !remaining.isEmpty(); slot++)
                remaining = items.insertItem(slot, remaining, false);
            require(
                    remaining.isEmpty(),
                    "Coal insertion incomplete; inspect inventory before retrying");
            be.setChanged();
        }
        LOG.info(
                "TRANSPORT_TEST fixture_fuel coal=8_per_engine infinite=false source=admin_fixture");
    }

    /** Native option, not shaft RPM. West/north gearbox ports require opposite source signs. */
    private static void liftDirections(ServerPlayer player, boolean initialize) throws Exception {
        var all = parts(player);
        BlockPos base = one(all, BEARING).getBlockPos().offset(0, -5, 0);
        BlockPos[] positions = {base.offset(-4, 3, 0), base.offset(-2, 3, -2)};
        RotationDirection[] expected = {
            RotationDirection.CLOCKWISE, RotationDirection.COUNTER_CLOCKWISE
        };
        String[] facings = {"east", "south"};
        for (int i = 0; i < positions.length; i++) {
            BlockPos pos = positions[i];
            BlockEntity engine =
                    all.stream()
                            .filter(be -> be.getBlockPos().equals(pos))
                            .findFirst()
                            .orElseThrow();
            require(
                    id(engine).equals(ENGINE) && engine instanceof SmartBlockEntity,
                    "Missing lift engine at " + pos);
            require(
                    property(engine.getBlockState(), "facing").equals(facings[i]),
                    "Lift engine facing changed at " + pos);
            ScrollValueBehaviour direction =
                    ((SmartBlockEntity) engine).getBehaviour(ScrollValueBehaviour.TYPE);
            require(direction != null, "Native rotation option unavailable at " + pos);
            if (initialize) {
                require(
                        (int) nativeCall(engine, "getCurrentBurnTime") == 0,
                        "Configure direction before fuel");
                direction.setValue(
                        expected[i].ordinal()); // Native callback updates generated rotation/data.
            }
            CompoundTag nbt = engine.saveWithoutMetadata(player.registryAccess());
            boolean verified =
                    direction.getValue() == expected[i].ordinal()
                            && nbt.contains("ScrollValue", Tag.TAG_INT)
                            && nbt.getInt("ScrollValue") == expected[i].ordinal();
            LOG.info(
                    "TRANSPORT_TEST lift_direction pos={} facing={} expected={} option={} nbt_option={} verified={} "
                            + "configured_before_fuel={} generated_rpm={}",
                    pos,
                    facings[i],
                    expected[i],
                    direction.getValue(),
                    nbt.get("ScrollValue"),
                    verified,
                    initialize,
                    nativeCall(engine, "getGeneratedSpeed"));
            require(
                    verified,
                    "Lift source direction mismatch; stop and correct native option before fuel at "
                            + pos);
        }
        LOG.info(
                "TRANSPORT_TEST lift_directions=VERIFIED west=CLOCKWISE north=COUNTER_CLOCKWISE "
                        + "source_signs=opposite method=native_scroll_option_and_serialized_readback rpm_written=false");
    }

    private static void requireRotorStopped(BlockEntity bearing) throws Exception {
        float shaft = ((KineticBlockEntity) bearing).getSpeed();
        float rotation = ((Number) nativeCall(bearing, "getRotationSpeed")).floatValue();
        LOG.info(
                "TRANSPORT_TEST rotor_stop shaft_rpm={} rotation_degrees_per_tick={} tolerance_degrees_per_tick={}",
                shaft,
                rotation,
                STOPPED_ROTOR_DEGREES_PER_TICK);
        // Native lerp coast-down has no zero snap. Observe near-zero; never write rotation.
        require(
                shaft == 0 && Math.abs(rotation) <= STOPPED_ROTOR_DEGREES_PER_TICK,
                "Stop rotor shaft and wait for native coast-down within 0.001 degrees/tick");
    }

    /** Idempotent stopped native assembly; never toggles or disassembles an existing rotor. */
    private static void assembleRotor(ServerPlayer player) throws Exception {
        BlockEntity bearing = one(parts(player), BEARING);
        require(
                (int) nativeCall(one(parts(player), THROTTLE), "getState") == 15,
                "Set throttle 15 first");
        requireRotorStopped(bearing);
        if (!(boolean) nativeCall(bearing, "isRunning")) nativeCall(bearing, "assemble");
        rotorState(player, true);
    }

    private static void rotorState(ServerPlayer player, boolean gate) {
        BlockEntity be = one(parts(player), BEARING);
        require(be instanceof MechanicalBearingBlockEntity, "Unexpected native bearing type");
        var bearing = (MechanicalBearingBlockEntity) be;
        var entity = bearing.getMovedContraption();
        var contraption = entity == null ? null : entity.getContraption();
        int sails = contraption instanceof BearingContraption rotor ? rotor.getSailBlocks() : -1;
        int blocks = contraption == null ? 0 : contraption.getBlocks().size();
        long white =
                contraption == null
                        ? 0
                        : contraption.getBlocks().values().stream()
                                .filter(
                                        info ->
                                                BuiltInRegistries.BLOCK
                                                        .getKey(info.state().getBlock())
                                                        .toString()
                                                        .equals("create:white_sail"))
                                .count();
        long hubs =
                contraption == null
                        ? 0
                        : contraption.getBlocks().values().stream()
                                .filter(info -> info.state().is(Blocks.OAK_PLANKS))
                                .count();
        boolean geometry = contraption != null;
        if (contraption != null)
            for (int x = -3; x <= 3; x++)
                for (int z = -3; z <= 3; z++) {
                    var info = contraption.getBlocks().get(new BlockPos(x, 0, z));
                    geometry &=
                            info != null
                                    && (x == 0 && z == 0
                                            ? info.state().is(Blocks.OAK_PLANKS)
                                            : BuiltInRegistries.BLOCK
                                                            .getKey(info.state().getBlock())
                                                            .toString()
                                                            .equals("create:white_sail")
                                                    && property(info.state(), "facing")
                                                            .equals("up"));
                }
        boolean complete =
                geometry
                        && bearing.isRunning()
                        && entity != null
                        && entity.isAlive()
                        && !entity.isRemoved()
                        && contraption != null
                        && !contraption.disassembled
                        && sails == 48
                        && blocks == 49
                        && white == 48
                        && hubs == 1
                        && bearing.getLastAssemblyException() == null;
        LOG.info(
                "TRANSPORT_TEST rotor bound={} running={} entity={} alive={} disassembled={} native_sails={} "
                        + "blocks={} white_sails={} hubs={} complete={} native_exception={}",
                record(player).hasUUID("Body"),
                bearing.isRunning(),
                entity == null ? "none" : entity.getUUID(),
                entity != null && entity.isAlive(),
                contraption == null ? "no_contraption" : contraption.disassembled,
                sails,
                blocks,
                white,
                hubs,
                complete,
                bearing.getLastAssemblyException());
        if (gate)
            require(
                    complete,
                    "Rotor incomplete: require live native 48-sail/49-block contraption; stop and use postrotor after hull binding");
    }

    private static String property(BlockState state, String name) {
        return state.getValues().entrySet().stream()
                .filter(e -> e.getKey().getName().equals(name))
                .map(e -> e.getValue().toString())
                .findFirst()
                .orElse("MISSING");
    }

    /** Fixed native paths: receiver -> repeater/torch -> clutch, plus reverse receivers. */
    private static ListTag controls(
            ServerPlayer player, boolean requireBound, boolean requireReleased) {
        var all = parts(player);
        BlockEntity bearing = one(all, BEARING);
        BlockPos base = bearing.getBlockPos().offset(0, -5, 0);
        var level = bearing.getLevel();
        require(level != null, "Bearing level missing");
        String[] keys = {"W", "S", "D", "A"};
        BlockPos[] receivers = {
            base.offset(4, 2, -3),
            base.offset(-1, 2, -2),
            base.offset(1, 2, -1),
            base.offset(0, 2, 4)
        };
        ListTag bindings = new ListTag();
        List<LinkBehaviour> nativeChannels = new ArrayList<>();
        for (int i = 0; i < receivers.length; i++) {
            BlockEntity be = level.getBlockEntity(receivers[i]);
            require(
                    be instanceof RedstoneLinkBlockEntity,
                    "Native link missing at " + receivers[i]);
            var link = (RedstoneLinkBlockEntity) be;
            CompoundTag nbt = link.saveWithoutMetadata(player.registryAccess());
            LOG.info(
                    "TRANSPORT_TEST control key={} pos={} state={} received={} nbt={}",
                    keys[i],
                    receivers[i],
                    link.getBlockState(),
                    link.getReceivedSignal(),
                    nbt);
            CompoundTag binding = new CompoundTag();
            binding.putString("Key", keys[i]);
            binding.put("First", nbt.getCompound("FrequencyFirst").copy());
            binding.put("Last", nbt.getCompound("FrequencyLast").copy());
            if (requireBound) {
                require(
                        property(link.getBlockState(), "receiver").equals("true")
                                && property(link.getBlockState(), "facing").equals("up")
                                && nbt.contains("Transmitter")
                                && !nbt.getBoolean("Transmitter"),
                        "Expected receiving native link " + keys[i]);
                require(
                        !nbt.getCompound("FrequencyFirst").isEmpty()
                                && !nbt.getCompound("FrequencyLast").isEmpty(),
                        "Bind both native frequency slots for " + keys[i]);
                LinkBehaviour channel = link.getBehaviour(LinkBehaviour.TYPE);
                require(
                        channel != null && channel.isListening(),
                        "Native receiving behaviour missing for " + keys[i]);
                for (LinkBehaviour previous : nativeChannels)
                    require(
                            !previous.getNetworkKey().equals(channel.getNetworkKey()),
                            "Native controller channels must be distinct");
                nativeChannels.add(channel);
            }
            if (requireReleased)
                require(
                        link.getReceivedSignal() == 0
                                && nbt.contains("Receive", Tag.TAG_INT)
                                && nbt.getInt("Receive") == 0,
                        "Receiver still powered: " + keys[i]);
            bindings.add(binding);
        }
        int[][] coordinates = {
            {3, 2, -3},
            {1, 2, -3},
            {0, 2, -3},
            {0, 2, -2},
            {1, 2, 0},
            {1, 2, 2},
            {1, 2, 3},
            {0, 2, 3}
        };
        String[] ids = {
            "minecraft:repeater",
            "minecraft:redstone_wall_torch",
            "create:clutch",
            "create:gearshift",
            "minecraft:repeater",
            "minecraft:redstone_wall_torch",
            "create:clutch",
            "create:gearshift"
        };
        String[] properties = {
            "powered", "lit", "powered", "powered", "powered", "lit", "powered", "powered"
        };
        String[] released = {"false", "true", "true", "false", "false", "true", "true", "false"};
        String[] geometryProperties = {
            "facing", "facing", "axis", "axis", "facing", "facing", "axis", "axis"
        };
        String[] geometryValues = {"east", "west", "z", "z", "north", "south", "x", "x"};
        for (int i = 0; i < coordinates.length; i++) {
            int[] xyz = coordinates[i];
            BlockPos pos = base.offset(xyz[0], xyz[1], xyz[2]);
            BlockState state = level.getBlockState(pos);
            LOG.info(
                    "TRANSPORT_TEST control_path pos={} state={} expected_release_property={} expected_release_value={}",
                    pos,
                    state,
                    properties[i],
                    released[i]);
            if (requireBound || requireReleased)
                require(
                        BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(ids[i])
                                && property(state, geometryProperties[i]).equals(geometryValues[i]),
                        "Native control path geometry differs at " + pos);
            if (requireReleased)
                require(
                        property(state, properties[i]).equals(released[i]),
                        "Native release path not settled at " + pos);
        }
        return bindings;
    }

    private static void releaseStart(ServerPlayer player) {
        var hull = body(player);
        CompoundTag start = new CompoundTag();
        start.putUUID("Body", hull.getUniqueId());
        start.putUUID("Session", SESSION);
        start.putLong("Tick", player.level().getGameTime());
        start.put("Bindings", controls(player, true, false));
        record(player).put("ReleaseStart", start);
        record(player).remove("ReleaseVerified");
        LOG.info(
                "TRANSPORT_TEST release=PENDING start_tick={} minimum_server_ticks=40 input=ordinary_manual_or_controller_loss",
                start.getLong("Tick"));
    }

    private static void released(ServerPlayer player) {
        CompoundTag start = record(player).getCompound("ReleaseStart");
        require(
                start.hasUUID("Session") && SESSION.equals(start.getUUID("Session")),
                "Run release-start in this JVM first");
        require(body(player).getUniqueId().equals(start.getUUID("Body")), "Release hull differs");
        long elapsed = player.level().getGameTime() - start.getLong("Tick");
        require(
                elapsed >= 40,
                "Wait at least 40 normal server ticks after release-start; elapsed=" + elapsed);
        require(
                controls(player, true, true).equals(start.getList("Bindings", Tag.TAG_COMPOUND)),
                "Bindings changed during release window");
        record(player).put("ReleaseVerified", start.copy());
        LOG.info(
                "TRANSPORT_TEST release=VERIFIED elapsed_server_ticks={} receivers=zero clutches=disconnected gearshifts=normal "
                        + "scope=endpoint_observation continuous_input_history=UNVERIFIED",
                elapsed);
    }

    private static void rejoin(ServerPlayer player) {
        CompoundTag reference = record(player).getCompound("Reference");
        require(
                reference.hasUUID("Session") && !SESSION.equals(reference.getUUID("Session")),
                "Rejoin requires saved checkpoint from previous JVM");
        require(
                body(player).getUniqueId().equals(reference.getUUID("Body")),
                "Rejoin hull UUID differs");
        CompoundTag verified = record(player).getCompound("ReleaseVerified");
        require(
                verified.hasUUID("Session") && SESSION.equals(verified.getUUID("Session")),
                "Run release-start then released after restart (>30 server ticks)");
        require(
                body(player).getUniqueId().equals(verified.getUUID("Body")),
                "Verified release hull differs");
        require(
                player.level().getGameTime() - verified.getLong("Tick") >= 40,
                "Release window incomplete");
        require(
                verified.getList("Bindings", Tag.TAG_COMPOUND)
                        .equals(reference.getList("Bindings", Tag.TAG_COMPOUND)),
                "Release window bindings differ from checkpoint; restore bindings and repeat release-start/released");
        require(
                controls(player, true, true)
                        .equals(reference.getList("Bindings", Tag.TAG_COMPOUND)),
                "Rejoin native bindings differ from checkpoint");
        LOG.info(
                "TRANSPORT_TEST rejoin_controls=VERIFIED same_hull=true bindings_equal=true released_now=true "
                        + "new_jvm=true native_input_reacquisition=MANUAL_UNTESTED cargo_check=use_persist");
    }

    private static void cargoFixture(ServerPlayer player, boolean full) {
        for (BlockEntity be : barrels(parts(player))) {
            Container cargo = (Container) be;
            for (int slot = 0; slot < cargo.getContainerSize(); slot++)
                cargo.setItem(slot, full ? new ItemStack(Items.COPPER_INGOT, 64) : ItemStack.EMPTY);
            be.setChanged();
        }
        LOG.info(
                "TRANSPORT_TEST fixture_cargo full={} source=admin_fixture inventory_mass_effect=UNVERIFIED",
                full);
    }

    /** Full serialized stacks preserve slot, count and data components; no lossy item-count hash. */
    private static ListTag cargo(ServerPlayer player) {
        ListTag result = new ListTag();
        for (BlockEntity be : barrels(parts(player))) {
            CompoundTag barrel = new CompoundTag();
            barrel.putLong("Pos", be.getBlockPos().asLong());
            ListTag slots = new ListTag();
            Container inventory = (Container) be;
            barrel.putInt("Size", inventory.getContainerSize());
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                CompoundTag slot = new CompoundTag();
                slot.putInt("Slot", i);
                slot.put("Stack", inventory.getItem(i).saveOptional(player.registryAccess()));
                slots.add(slot);
            }
            barrel.put("Slots", slots);
            result.add(barrel);
        }
        return result;
    }

    private static void checkpoint(ServerPlayer player) {
        var hull = body(player);
        CompoundTag reference = new CompoundTag();
        reference.putUUID("Body", hull.getUniqueId());
        reference.putUUID("Session", SESSION);
        reference.put("Cargo", cargo(player));
        reference.put("Bindings", controls(player, false, false));
        reference.putDouble("Mass", hull.getMassTracker().getMass());
        reference.putLong("Tick", player.level().getGameTime());
        record(player).put("Reference", reference);
        LOG.info(
                "TRANSPORT_TEST checkpoint reference={} storage=player_persistent_data save_and_restart_required=true",
                reference);
    }

    private static void compare(ServerPlayer player, boolean restartRequired) {
        CompoundTag reference = record(player).getCompound("Reference");
        require(reference.hasUUID("Body"), "No durable checkpoint");
        boolean differentSession = !SESSION.equals(reference.getUUID("Session"));
        if (restartRequired)
            require(differentSession, "persist requires a new JVM session after save/rejoin");
        require(body(player).getUniqueId().equals(reference.getUUID("Body")), "Hull UUID differs");
        require(
                cargo(player).equals(reference.getList("Cargo", Tag.TAG_COMPOUND)),
                "Cargo slots/stacks differ from checkpoint");
        LOG.info(
                "TRANSPORT_TEST cargo_equal=true uuid_equal=true different_jvm={} reference_mass={} current_mass={} "
                        + "flight_acceptance=UNTESTED",
                differentSession,
                reference.getDouble("Mass"),
                body(player).getMassTracker().getMass());
    }

    private static void snapshot(ServerPlayer player) throws Exception {
        var tag = record(player);
        if (tag.hasUUID("Body")) {
            var hull = body(player);
            var mass = hull.getMassTracker();
            var handle = RigidBodyHandle.of(hull);
            require(handle.isValid(), "Physics handle is not valid yet");
            var pos = hull.logicalPose().position();
            LOG.info(
                    "TRANSPORT_TEST snapshot tick={} uuid={} pose={} mass={} com={} inertia={} velocity={} angular_velocity={} "
                            + "pressure={} dimension_gravity={} fixture_only=true gameplay_acceptance=UNTESTED",
                    player.level().getGameTime(),
                    hull.getUniqueId(),
                    hull.logicalPose(),
                    mass.getMass(),
                    mass.getCenterOfMass(),
                    mass.getInertiaTensor(),
                    handle.getLinearVelocity(),
                    handle.getAngularVelocity(),
                    DimensionPhysicsData.getAirPressure(player.serverLevel(), pos),
                    DimensionPhysicsData.getGravity(player.serverLevel(), pos));
        } else {
            var container = SubLevelContainer.getContainer(player.serverLevel());
            LOG.info(
                    "TRANSPORT_TEST unbound base={} live_hulls={}",
                    BlockPos.of(tag.getLong("Base")),
                    container == null
                            ? "none"
                            : container.getAllSubLevels().stream()
                                    .map(h -> h.getUniqueId().toString())
                                    .toList());
            if (tag.getBoolean("AssemblyPending")) {
                LOG.info(
                        "TRANSPORT_TEST assembly=PENDING_EXPLICIT_UUID_BIND old_ground_coordinates_not_scanned=true");
                return;
            }
        }
        rotorState(player, false);
        controls(player, false, false);
        LOG.info(
                "TRANSPORT_TEST player uuid={} vehicle={} passenger={} native_control_reacquisition=MANUAL_UNTESTED",
                player.getUUID(),
                player.getVehicle() == null ? "none" : player.getVehicle().getUUID(),
                player.isPassenger());
        for (BlockEntity be : parts(player)) {
            CompoundTag nbt = be.saveWithoutMetadata(player.registryAccess());
            LOG.info(
                    "TRANSPORT_TEST native_be pos={} id={} state={} nbt={}",
                    be.getBlockPos(),
                    id(be),
                    be.getBlockState(),
                    nbt);
            if (be instanceof KineticBlockEntity kinetic) {
                CompoundTag network = nbt.getCompound("Network");
                LOG.info(
                        "TRANSPORT_TEST kinetic pos={} id={} rpm={} theoretical_rpm={} overstressed={} "
                                + "network_present={} network_capacity_su={} network_stress_su={} nbt={}",
                        be.getBlockPos(),
                        id(be),
                        kinetic.getSpeed(),
                        kinetic.getTheoreticalSpeed(),
                        kinetic.isOverStressed(),
                        nbt.contains("Network"),
                        network.getFloat("Capacity"),
                        network.getFloat("Stress"),
                        nbt);
            }
            if (be instanceof BlockEntityPropeller propeller)
                LOG.info(
                        "TRANSPORT_TEST propeller pos={} active={} thrust={} scaled_thrust={} pressure={} airflow_scale={} direction={}",
                        be.getBlockPos(),
                        propeller.isActive(),
                        propeller.getThrust(),
                        propeller.getScaledThrust(),
                        propeller.getCurrentAirPressure(),
                        propeller.getAirflowScaling(),
                        propeller.getBlockDirection());
            if (id(be).equals(ENGINE))
                LOG.info(
                        "TRANSPORT_TEST engine pos={} generated_rpm={} burn={} total_burn={} infinite={} superheated={}",
                        be.getBlockPos(),
                        nativeCall(be, "getGeneratedSpeed"),
                        nativeCall(be, "getCurrentBurnTime"),
                        nativeCall(be, "getTotalBurnTime"),
                        nativeCall(be, "isTotalFuelInfinite"),
                        nativeCall(be, "isSuperHeated"));
            if (id(be).equals(THROTTLE))
                LOG.info(
                        "TRANSPORT_TEST throttle pos={} actual_signal={}",
                        be.getBlockPos(),
                        nativeCall(be, "getState"));
        }
        LOG.info("TRANSPORT_TEST cargo={}", cargo(player));
    }

    // Optional Aeronautics/Simulated are not compile dependencies. Exact public native methods
    // were checked with javap; failure remains explicit, with manual interaction as fallback.
    private static Object nativeCall(BlockEntity be, String method) throws Exception {
        try {
            return be.getClass().getMethod(method).invoke(be);
        } catch (InvocationTargetException error) {
            throw new IllegalStateException("Native " + method + " failed", error.getCause());
        }
    }

    private static void nativeCall(BlockEntity be, String method, Class<?> type, Object argument)
            throws Exception {
        try {
            be.getClass().getMethod(method, type).invoke(be, argument);
        } catch (InvocationTargetException error) {
            throw new IllegalStateException("Native " + method + " failed", error.getCause());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private DevTransportProbe() {}
}
