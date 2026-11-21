package dev.starfold.dev;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlockEntity;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;

import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetStorage;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Optional fixture, explicitly initialized by the integrator. Never registers itself in production.
 * All supplied blocks/items are development inventory, not evidence of ordinary survival acquisition.
 * Snapshots and verify commands only observe; processing belongs exclusively to the native machines.
 */
public final class DevIndustryProbe {
    private static final Logger LOG = LoggerFactory.getLogger("starfold-probe");
    private static final String KEY = "starfold_probe:industry_v1";
    private static final String CDG = "createdieselgenerators:";
    private static final String[] ACTIONS = {
        "setup",
        "repair-empty-supplies",
        "thaw-tower-source",
        "convert",
        "press-input",
        "mix-input",
        "heat-mixer",
        "heat-tower",
        "boundary-crude-1000",
        "mark-natural-1000",
        "snapshot",
        "recipes",
        "verify-workshop",
        "verify-distillation",
        "boundary-repeat-1000",
        "liquid-checkpoint",
        "verify-liquid-persist",
        "precision-input",
        "precision-next",
        "verify-precision"
    };
    private static final String SESSION = java.util.UUID.randomUUID().toString();
    private static boolean initialized;

    private DevIndustryProbe() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        NeoForge.EVENT_BUS.addListener(
                (RegisterCommandsEvent event) ->
                        event.getDispatcher()
                                .register(
                                        Commands.literal("industrytest")
                                                .requires(source -> source.hasPermission(2))
                                                .then(
                                                        Commands.literal("observe")
                                                                .then(
                                                                        Commands.argument(
                                                                                        "pos",
                                                                                        BlockPosArgument
                                                                                                .blockPos())
                                                                                .executes(
                                                                                        context ->
                                                                                                observeCoordinate(
                                                                                                        context
                                                                                                                .getSource(),
                                                                                                        BlockPosArgument
                                                                                                                .getBlockPos(
                                                                                                                        context,
                                                                                                                        "pos")))))
                                                .then(
                                                        Commands.argument(
                                                                        "action",
                                                                        StringArgumentType.word())
                                                                .suggests(
                                                                        (context, builder) -> {
                                                                            for (String action :
                                                                                    ACTIONS)
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
    }

    private static int run(CommandSourceStack source, String action) {
        try {
            guard(source);
            ServerPlayer player = source.getPlayerOrException();
            if (action.equals("setup")) setup(player);
            else if (action.equals("recipes")) recipes(player.serverLevel());
            else {
                BlockPos base = fixture(player);
                switch (action) {
                    case "repair-empty-supplies" -> repairEmptySupplies(player, base);
                    case "thaw-tower-source" -> thawTowerSource(player, base);
                    case "convert" -> convert(player, base);
                    case "press-input" -> transfer(player, base, 0, base.offset(2, 3, 2), 1);
                    case "mix-input" -> {
                        // Simulation of both insertions before either moves; the fixture has
                        // distinct materials.
                        preflightTransfer(player.serverLevel(), base, 1, base.offset(3, 4, 3), 1);
                        preflightTransfer(player.serverLevel(), base, 2, base.offset(3, 4, 3), 1);
                        transfer(player, base, 1, base.offset(3, 4, 3), 1);
                        transfer(player, base, 2, base.offset(3, 4, 3), 1);
                    }
                    case "heat-mixer" -> heat(player.serverLevel(), base, base.offset(3, 3, 3));
                    case "heat-tower" -> {
                        require(
                                !record(player).getString("InputMode").isEmpty(),
                                "First mark-natural-1000 or explicitly use boundary-crude-1000");
                        heat(player.serverLevel(), base, base.offset(12, 0, 0));
                    }
                    case "boundary-crude-1000" -> boundary(player, base);
                    case "boundary-repeat-1000" -> repeatBoundary(player, base);
                    case "liquid-checkpoint" -> liquidCheckpoint(player, base);
                    case "verify-liquid-persist" -> verifyLiquidPersist(player, base);
                    case "precision-input" -> precisionInput(player, base);
                    case "precision-next" -> precisionNext(player, base);
                    case "verify-precision" -> verifyPrecision(player, base);
                    case "mark-natural-1000" -> markNatural(player, base);
                    case "snapshot" -> {}
                    case "verify-workshop" -> verifyWorkshop(player.serverLevel(), base);
                    case "verify-distillation" -> verifyDistillation(player, base);
                    default -> throw new IllegalArgumentException("Unknown action " + action);
                }
            }
            if (!action.equals("recipes")) snapshot(player, fixture(player));
            String receipt =
                    "INDUSTRY_COMMAND_OK action="
                            + action
                            + " evidence=controlled-development-fixture";
            LOG.info(receipt);
            source.sendSuccess(() -> Component.literal(receipt), false);
            return 1;
        } catch (Exception error) {
            LOG.error("INDUSTRY_FAIL action={}", action, error);
            source.sendFailure(
                    Component.literal("INDUSTRY_FAIL " + action + ": " + error.getMessage()));
            return 0;
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
                world.startsWith("regression-stage2-"),
                "Requires world basename regression-stage2-*");
        require(PlanetLevels.is(source.getLevel()), "Requires native planet level");
    }

    private static void bounds(ServerLevel level, BlockPos pos) {
        long key = PlanetStorage.key(pos.getX() >> 4, pos.getZ() >> 4);
        require(
                key >= 0 && key < PlanetStorage.CHUNKS_PER_PLANET,
                "Requires native home chart: " + pos);
        require(
                !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos),
                "Outside build bounds " + pos);
        require(level.hasChunkAt(pos), "Load footprint first: " + pos);
    }

    private static CompoundTag record(ServerPlayer player) {
        return player.getPersistentData().getCompound(KEY);
    }

    private static BlockPos fixture(ServerPlayer player) {
        CompoundTag record = record(player);
        require(record.contains("Base", Tag.TAG_LONG), "Run setup first");
        require(
                record.getString("Dimension")
                        .equals(player.level().dimension().location().toString()),
                "Return to fixture dimension");
        BlockPos base = BlockPos.of(record.getLong("Base"));
        for (BlockPos pos : BlockPos.betweenClosed(base.offset(-2, -1, -3), base.offset(14, 9, 6)))
            bounds(player.serverLevel(), pos);
        require(
                player.serverLevel().getBlockState(base.offset(6, 0, 2)).is(Blocks.BARREL),
                "Fixture supply barrel missing");
        return base;
    }

    private static void setup(ServerPlayer player) {
        require(
                !record(player).contains("Base"),
                "One setup per player; use another disposable world for a fresh run");
        ServerLevel level = player.serverLevel();
        BlockPos base = player.blockPosition().offset(4, 0, 0);
        Map<BlockPos, BlockState> plan = new LinkedHashMap<>();
        // Pre-resolve every registry ID/property, then validate all of the bounded volume before
        // mutation.
        for (int x = -2; x <= 14; x++)
            for (int z = -3; z <= 6; z++)
                plan.put(base.offset(x, -1, z), Blocks.STONE.defaultBlockState());
        add(plan, base, 0, 3, -2, "create:water_wheel", "facing", "south");
        add(plan, base, 0, 3, -1, "create:water_wheel", "facing", "south");
        add(plan, base, 0, 3, 0, "create:large_cogwheel", "axis", "z");
        add(plan, base, 1, 4, 0, "create:cogwheel", "axis", "z");
        add(plan, base, 1, 4, 1, "create:large_cogwheel", "axis", "z");
        add(plan, base, 2, 5, 1, "create:cogwheel", "axis", "z");
        add(plan, base, 2, 5, 2, "create:mechanical_press", "facing", "south");
        add(plan, base, 2, 3, 2, "create:depot");
        add(plan, base, 2, 5, 3, "create:gearbox", "axis", "x");
        add(plan, base, 2, 6, 3, "create:cogwheel", "axis", "y");
        add(plan, base, 3, 6, 3, "create:mechanical_mixer");
        add(plan, base, 3, 4, 3, "create:basin");
        add(plan, base, 3, 3, 3, "create:blaze_burner", "blaze", "smouldering");
        add(plan, base, 2, 5, 4, "create:shaft", "axis", "z");
        add(plan, base, 2, 5, 5, "create:deployer", "facing", "down", "axis_along_first", "false");
        add(plan, base, 2, 3, 5, "create:depot");
        // The tower starts as EMPTY Create tanks. convert invokes CDG's actual controller item
        // later.
        add(plan, base, 12, 0, 0, "create:blaze_burner", "blaze", "smouldering");
        for (int y = 1; y <= 3; y++) {
            add(plan, base, 12, y, 0, "create:fluid_tank");
            add(
                    plan,
                    base,
                    12,
                    y,
                    1,
                    "create:mechanical_pump",
                    "facing",
                    y == 1 ? "north" : "south");
        }
        add(plan, base, 12, 1, 2, "create:fluid_tank");
        add(plan, base, 12, 2, 2, "create:glass_fluid_pipe", "axis", "z");
        add(plan, base, 12, 2, 3, "create:fluid_tank");
        add(plan, base, 12, 3, 2, "create:glass_fluid_pipe", "axis", "z");
        add(plan, base, 12, 3, 3, "create:glass_fluid_pipe", "axis", "z");
        add(plan, base, 12, 3, 4, "create:fluid_tank");
        add(plan, base, 12, 4, 1, "create:cogwheel", "axis", "z");
        for (int z = 2; z <= 4; z++) add(plan, base, 12, 4, z, "create:shaft", "axis", "z");
        add(plan, base, 12, 4, 5, "create:water_wheel", "facing", "south");
        // Contained falling-water chutes: only sources are supplied, flow and RPM are native
        // simulation.
        for (int z = -2; z <= -1; z++)
            for (int y = 0; y <= 8; y++) {
                stone(plan, base.offset(-2, y, z));
                if (y != 3) stone(plan, base.offset(0, y, z));
                if (y == 0) stone(plan, base.offset(-1, y, z));
            }
        for (int y = 0; y <= 8; y++) {
            stone(plan, base.offset(-1, y, -3));
            stone(plan, base.offset(-1, y, 0));
            stone(plan, base.offset(14, y, 5));
            if (y != 4) stone(plan, base.offset(12, y, 5));
            stone(plan, base.offset(13, y, 4));
            stone(plan, base.offset(13, y, 6));
        }
        stone(plan, base.offset(13, 0, 5));
        // This home biome can freeze a lone source. Finite supplied lighting keeps the chute
        // liquid.
        plan.put(base.offset(13, 8, 5), Blocks.GLOWSTONE.defaultBlockState());
        add(plan, base, 6, 0, 2, "minecraft:barrel");
        for (String id :
                new String[] {
                    "minecraft:iron_ingot",
                    "minecraft:copper_ingot",
                    "create:zinc_ingot",
                    "minecraft:coal",
                    CDG + "distillation_controller"
                }) item(id, 1);
        for (BlockPos pos :
                BlockPos.betweenClosed(base.offset(-2, -1, -3), base.offset(14, 9, 6))) {
            bounds(level, pos);
            require(level.getBlockEntity(pos) == null, "Would replace block entity " + pos);
            require(
                    pos.getY() == base.getY() - 1 || level.getBlockState(pos).isAir(),
                    "Needs clear air above floor: " + pos);
        }
        CompoundTag record = new CompoundTag();
        record.putLong("Base", base.asLong());
        record.putString("Dimension", level.dimension().location().toString());
        player.getPersistentData().put(KEY, record);
        LOG.info(
                "INDUSTRY_MUTATION setup base={} blocks={} development-supplied-not-survival",
                base,
                plan.size());
        plan.forEach((pos, state) -> place(level, pos, state));
        BlockEntity barrel = level.getBlockEntity(base.offset(6, 0, 2));
        ItemStack label = item("minecraft:barrel", 1);
        label.set(
                DataComponents.CUSTOM_NAME,
                Component.literal("DEVELOPMENT FIXTURE SUPPLIES — not survival acquisition"));
        // Applying item components resets the container component too: label BEFORE stocking.
        barrel.applyComponentsFromItemStack(label);
        barrel.setChanged();
        stockSupplies(level, base);
        place(level, base.offset(-1, 7, -2), Blocks.WATER.defaultBlockState());
        place(level, base.offset(-1, 7, -1), Blocks.WATER.defaultBlockState());
        place(level, base.offset(13, 7, 5), Blocks.WATER.defaultBlockState());
        LOG.info(
                "INDUSTRY_LAYOUT base={} towerBottom={} externalCrude={} externalDiesel={} externalGasoline={}",
                base,
                base.offset(12, 1, 0),
                base.offset(12, 1, 2),
                base.offset(12, 2, 3),
                base.offset(12, 3, 4));
    }

    private static void stockSupplies(ServerLevel level, BlockPos base) {
        IItemHandler supplies = supplies(level, base);
        String[] ids = {
            "minecraft:iron_ingot",
            "minecraft:copper_ingot",
            "create:zinc_ingot",
            "minecraft:coal",
            CDG + "distillation_controller",
            "create:golden_sheet",
            "create:cogwheel",
            "create:large_cogwheel",
            "minecraft:iron_nugget"
        };
        int[] counts = {16, 8, 8, 8, 3, 1, 5, 5, 5};
        for (int i = 0; i < ids.length; i++) {
            ItemStack stack = item(ids[i], counts[i]);
            LOG.info(
                    "INDUSTRY_MUTATION supplied slot={} stack={} provenance=development-fixture",
                    i,
                    stack);
            require(supplies.insertItem(i, stack, false).isEmpty(), "Supply insertion rejected");
        }
    }

    private static void repairEmptySupplies(ServerPlayer player, BlockPos base) {
        require(
                !record(player).getBoolean("SuppliesRepaired")
                        && record(player).getString("InputMode").isEmpty()
                        && !record(player).getBoolean("PrecisionStarted"),
                "Repair only a virgin fixture once");
        ServerLevel level = player.serverLevel();
        require(fluidTotals(level, base).isEmpty(), "Repair before fluid input");
        for (int y = 1; y <= 3; y++)
            require(
                    level.getBlockState(base.offset(12, y, 0))
                            .is(
                                    BuiltInRegistries.BLOCK.get(
                                            ResourceLocation.parse("create:fluid_tank"))),
                    "Repair before conversion");
        for (BlockPos pos :
                new BlockPos[] {base.offset(2, 3, 2), base.offset(3, 4, 3), base.offset(2, 3, 5)}) {
            IItemHandler handler = items(level, pos);
            for (int i = 0; i < handler.getSlots(); i++)
                require(handler.getStackInSlot(i).isEmpty(), "Repair before processing");
        }
        for (int i = 0; i < 9; i++)
            require(
                    supplies(level, base).getStackInSlot(i).isEmpty(),
                    "Expected label-cleared supply slots");
        record(player).putBoolean("SuppliesRepaired", true);
        LOG.info(
                "INDUSTRY_MUTATION repairEmptySupplies once=true reason=fixture-label-cleared-container beforeAnyProcessing=true");
        stockSupplies(level, base);
    }

    private static void thawTowerSource(ServerPlayer player, BlockPos base) {
        ServerLevel level = player.serverLevel();
        require(
                !record(player).getBoolean("TowerSourceThawed"),
                "One frozen-source fixture repair");
        require(
                record(player).getString("InputMode").equals("CONTROLLED_BOUNDARY_INJECTION")
                        && record(player).getInt("Batches") == 1
                        && fluidTotals(level, base).equals(Map.of(CDG + "crude_oil", 1000)),
                "Repair only the untouched controlled inlet batch");
        BlockPos source = base.offset(13, 7, 5), lamp = source.above();
        require(
                level.getBlockState(source).is(Blocks.ICE)
                        && level.getBlockState(lamp).isAir()
                        && level.getBlockEntity(lamp) == null,
                "Expected frozen supplied chute with clear lamp position");
        LOG.info(
                "INDUSTRY_MUTATION thawTowerSource source={} before=ice suppliedLight=glowstone suppliedWater=one-source fluidBatchUnchanged=1000",
                source);
        place(level, lamp, Blocks.GLOWSTONE.defaultBlockState());
        place(level, source, Blocks.WATER.defaultBlockState());
        record(player).putBoolean("TowerSourceThawed", true);
    }

    private static void convert(ServerPlayer player, BlockPos base) {
        require(
                !player.isCreative() && !player.isSpectator(),
                "Convert in survival to account controller consumption");
        // Preserve the operator's pre-existing hand item in a reserved fixture slot before using
        // the native controller. Never clear or replace unrelated inventory to make the test pass.
        if (!player.getMainHandItem().isEmpty()) {
            IItemHandler supply = supplies(player.serverLevel(), base);
            ItemStack held = player.getMainHandItem().copy();
            int empty = -1;
            for (int i = 9; i < supply.getSlots(); i++)
                if (supply.getStackInSlot(i).isEmpty()
                        && supply.insertItem(i, held.copy(), true).isEmpty()) {
                    empty = i;
                    break;
                }
            require(empty >= 0, "No reserved fixture slot for existing main hand");
            require(
                    supply.insertItem(empty, held.copy(), false).isEmpty(),
                    "Could not preserve main hand");
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            LOG.info(
                    "INDUSTRY_MUTATION stowOperatorHand slot={} preserved={} noDiscard=true",
                    empty,
                    held);
        }
        ServerLevel level = player.serverLevel();
        for (int y = 1; y <= 3; y++)
            require(
                    level.getBlockState(base.offset(12, y, 0))
                            .is(
                                    BuiltInRegistries.BLOCK.get(
                                            ResourceLocation.parse("create:fluid_tank"))),
                    "Already converted or tower missing");
        require(fluidTotals(level, base).isEmpty(), "Convert only empty tanks");
        ItemStack controllers = supplies(level, base).getStackInSlot(4);
        require(
                controllers.is(item(CDG + "distillation_controller", 1).getItem())
                        && controllers.getCount() == 3,
                "Expected supplied 3 controllers in barrel slot 4");
        BlockPos pos = base.offset(12, 1, 0);
        LOG.info("INDUSTRY_MUTATION convert via CDG item.useOn pos={} controllers=3", pos);
        player.setItemInHand(
                InteractionHand.MAIN_HAND, supplies(level, base).extractItem(4, 3, false));
        try {
            var result =
                    player.getMainHandItem()
                            .useOn(
                                    new UseOnContext(
                                            player,
                                            InteractionHand.MAIN_HAND,
                                            new BlockHitResult(
                                                    Vec3.atCenterOf(pos),
                                                    Direction.SOUTH,
                                                    pos,
                                                    false)));
            require(
                    result.consumesAction(),
                    "CDG conversion rejected; wait for Create tank connectivity");
            require(player.getMainHandItem().isEmpty(), "Controller consumption was not exactly 3");
            for (int y = 1; y <= 3; y++) tower(level, base.offset(12, y, 0));
        } finally {
            ItemStack remainder = player.getMainHandItem();
            if (!remainder.isEmpty()) {
                ItemStack rejected = supplies(level, base).insertItem(4, remainder, false);
                player.setItemInHand(InteractionHand.MAIN_HAND, rejected);
            }
        }
    }

    private static void heat(ServerLevel level, BlockPos base, BlockPos pos) {
        require(level.getBlockEntity(pos) instanceof BlazeBurnerBlockEntity, "Burner missing");
        BlazeBurnerBlockEntity burner = (BlazeBurnerBlockEntity) level.getBlockEntity(pos);
        require(!burner.isCreative(), "Creative burner is outside probe contract");
        IItemHandler supplies = supplies(level, base);
        ItemStack coal = supplies.getStackInSlot(3).copyWithCount(1);
        require(
                coal.is(item("minecraft:coal", 1).getItem())
                        && !supplies.getStackInSlot(3).isEmpty(),
                "Supply coal exhausted");
        var simulated =
                BlazeBurnerBlock.tryInsert(
                        level.getBlockState(pos), level, pos, coal.copy(), false, false, true);
        require(simulated.getResult().consumesAction(), "Burner cannot accept normal coal yet");
        LOG.info("INDUSTRY_MUTATION heat pos={} coal=1 via native tryInsert", pos);
        ItemStack consumed = supplies.extractItem(3, 1, false);
        var result =
                BlazeBurnerBlock.tryInsert(
                        level.getBlockState(pos), level, pos, consumed, false, false, false);
        require(
                result.getResult().consumesAction() && consumed.isEmpty(),
                "Native coal insertion failed");
    }

    private static void preflightTransfer(
            ServerLevel level, BlockPos base, int slot, BlockPos target, int count) {
        ItemStack input = supplies(level, base).getStackInSlot(slot);
        require(input.getCount() >= count, "Insufficient fixture input slot " + slot);
        require(
                insert(items(level, target), input.copyWithCount(count), true).isEmpty(),
                "Machine input is full");
    }

    private static void transfer(
            ServerPlayer player, BlockPos base, int slot, BlockPos target, int count) {
        ServerLevel level = player.serverLevel();
        preflightTransfer(level, base, slot, target, count);
        LOG.info(
                "INDUSTRY_MUTATION input slot={} count={} target={} native-item-capability",
                slot,
                count,
                target);
        ItemStack input = supplies(level, base).extractItem(slot, count, false);
        ItemStack rejected = insert(items(level, target), input, false);
        if (!rejected.isEmpty()) {
            ItemStack leftover = supplies(level, base).insertItem(slot, rejected, false);
            if (!leftover.isEmpty()) player.drop(leftover, false);
            throw new IllegalStateException("Machine rejected input; unaccepted items returned");
        }
    }

    private static void boundary(ServerPlayer player, BlockPos base) {
        ServerLevel level = player.serverLevel();
        require(
                record(player).getString("InputMode").isEmpty(),
                "One batch per fixture; input mode already recorded");
        tower(level, base.offset(12, 1, 0));
        cold(level, base);
        require(
                fluidTotals(level, base).isEmpty(),
                "Boundary injection requires all six tanks empty");
        ResourceLocation fluidId = ResourceLocation.parse(CDG + "crude_oil");
        require(BuiltInRegistries.FLUID.containsKey(fluidId), "Pinned crude fluid missing");
        FluidStack input = new FluidStack(BuiltInRegistries.FLUID.get(fluidId), 1000);
        IFluidHandler handler = fluids(level, base.offset(12, 1, 2), Direction.NORTH);
        require(
                handler != null && handler.fill(input, IFluidHandler.FluidAction.SIMULATE) == 1000,
                "External crude tank cannot receive 1000mB");
        record(player).putString("InputMode", "CONTROLLED_BOUNDARY_INJECTION");
        record(player).putInt("Batches", 1);
        LOG.info(
                "INDUSTRY_MUTATION BOUNDARY_ONLY fluid={} amount=1000 target={} NOT_NATURAL_EXTRACTION",
                fluidId,
                base.offset(12, 1, 2));
        require(
                handler.fill(input, IFluidHandler.FluidAction.EXECUTE) == 1000,
                "Boundary fill mismatch");
    }

    private static void markNatural(ServerPlayer player, BlockPos base) {
        require(
                record(player).getString("InputMode").isEmpty(),
                "Input mode already recorded; injection cannot become natural evidence");
        tower(player.serverLevel(), base.offset(12, 1, 0));
        cold(player.serverLevel(), base);
        Map<String, Integer> expected = Map.of(CDG + "crude_oil", 1000);
        require(
                fluidTotals(player.serverLevel(), base).equals(expected),
                "Needs exactly 1000mB crude and no products across six tanks");
        record(player).putString("InputMode", "OPERATOR_DECLARED_NATURAL_INPUT");
        LOG.info(
                "INDUSTRY_INPUT mode=OPERATOR_DECLARED_NATURAL_INPUT amount=1000 extraction-provenance-needs-separate-extractor-receipt");
    }

    private static void cold(ServerLevel level, BlockPos base) {
        require(
                level.getBlockEntity(base.offset(12, 0, 0)) instanceof BlazeBurnerBlockEntity,
                "Tower burner missing");
        BlazeBurnerBlockEntity burner =
                (BlazeBurnerBlockEntity) level.getBlockEntity(base.offset(12, 0, 0));
        require(
                !burner.isCreative() && burner.getRemainingBurnTime() == 0,
                "Tower must be cold before input marking/injection");
    }

    private static void verifyWorkshop(ServerLevel level, BlockPos base) {
        for (BlockPos pos :
                new BlockPos[] {
                    base.offset(0, 3, -2),
                    base.offset(0, 3, -1),
                    base.offset(2, 5, 2),
                    base.offset(3, 6, 3)
                }) {
            require(
                    level.getBlockEntity(pos) instanceof KineticBlockEntity,
                    "Missing kinetic machine " + pos);
            KineticBlockEntity kinetic = (KineticBlockEntity) level.getBlockEntity(pos);
            float expected = pos.getX() == base.getX() ? 8 : 32;
            require(
                    Math.abs(kinetic.getSpeed()) == expected && !kinetic.isOverStressed(),
                    "Expected working " + expected + "RPM at " + pos);
        }
        require(
                count(items(level, base.offset(2, 3, 2)), "create:iron_sheet") >= 1,
                "No naturally pressed iron sheet on depot");
        require(
                count(items(level, base.offset(3, 4, 3)), "create:brass_ingot") >= 2,
                "No naturally mixed brass in basin");
        LOG.info(
                "INDUSTRY_VERIFY workshop=OBSERVED_OUTPUT rpm=8/32 supplied-inputs-not-survival-acquisition");
    }

    private static void verifyDistillation(ServerPlayer player, BlockPos base)
            throws ReflectiveOperationException {
        ServerLevel level = player.serverLevel();
        String mode = record(player).getString("InputMode");
        require(!mode.isEmpty(), "No batch input receipt");
        for (int y = 1; y <= 3; y++) {
            BlockEntity tank = tower(level, base.offset(12, y, 0));
            require(
                    ((Number) tank.getClass().getMethod("getHeight").invoke(tank)).intValue() == 1
                            && ((Number) tank.getClass().getMethod("getWidth").invoke(tank))
                                            .intValue()
                                    == 1,
                    "Expected three independent 1x1 CDG layers");
        }
        int batches = Math.max(1, record(player).getInt("Batches"));
        int product = 500 * batches;
        Map<String, Integer> expected = Map.of(CDG + "diesel", product, CDG + "gasoline", product);
        require(
                fluidTotals(level, base).equals(expected),
                "Wait for settled batch: expected total 0 crude/500 diesel/500 gasoline");
        require(
                onlyFluid(level, base.offset(12, 2, 3), CDG + "diesel", product),
                "Diesel must reach independent external tank");
        require(
                onlyFluid(level, base.offset(12, 3, 4), CDG + "gasoline", product),
                "Gasoline must reach independent external tank");
        LOG.info(
                "INDUSTRY_VERIFY distillation=OBSERVED_1000_TO_500_500 batches={} suppliedCrude={} diesel={} gasoline={} mode={} extractionProven=false",
                batches,
                batches * 1000,
                product,
                product,
                mode);
    }

    private static void snapshot(ServerPlayer player, BlockPos base)
            throws ReflectiveOperationException {
        ServerLevel level = player.serverLevel();
        LOG.info(
                "INDUSTRY_SNAPSHOT tick={} base={} record={} tankTotals={}",
                level.getGameTime(),
                base,
                record(player),
                fluidTotals(level, base));
        for (BlockPos pos : BlockPos.betweenClosed(base.offset(-2, 0, -3), base.offset(14, 9, 6))) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) continue;
            observeBlock(level, pos, be);
        }
        recipes(level);
    }

    // Observation is available on any loaded native survival workshop, independent of
    // fixture/player NBT.
    // BlockPosArgument.getBlockPos does not load chunks; bounds/hasChunkAt runs before any
    // BE/capability access.
    private static int observeCoordinate(CommandSourceStack source, BlockPos pos) {
        try {
            ServerLevel level = source.getLevel();
            require(PlanetLevels.is(level), "Requires native planet level");
            bounds(level, pos);
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) observeBlock(level, pos, be);
            else
                LOG.info("INDUSTRY_OBSERVE pos={} state={} be=none", pos, level.getBlockState(pos));
            String receipt = "INDUSTRY_COMMAND_OK action=observe pos=" + pos + " readOnly=true";
            LOG.info(receipt);
            source.sendSuccess(() -> Component.literal(receipt), false);
            return 1;
        } catch (Exception error) {
            LOG.error("INDUSTRY_FAIL action=observe", error);
            source.sendFailure(Component.literal("INDUSTRY_FAIL observe: " + error.getMessage()));
            return 0;
        }
    }

    private static void observeBlock(ServerLevel level, BlockPos pos, BlockEntity be)
            throws ReflectiveOperationException {
        // saveWithFullMetadata + getter/capability inspection only; no
        // updateNetwork/getOrCreateNetwork.
        CompoundTag nbt = be.saveWithFullMetadata(level.registryAccess());
        LOG.info(
                "INDUSTRY_BE pos={} state={} class={} nbt={}",
                pos,
                be.getBlockState(),
                be.getClass().getName(),
                nbt);
        if (be instanceof KineticBlockEntity kinetic) {
            CompoundTag network = nbt.getCompound("Network");
            LOG.info(
                    "INDUSTRY_KINETIC pos={} workingRPM={} theoreticalRPM={} overStressed={} network={} source={} networkPresent={} capacitySU={} stressSU={}",
                    pos,
                    kinetic.getSpeed(),
                    kinetic.getTheoreticalSpeed(),
                    kinetic.isOverStressed(),
                    kinetic.network,
                    kinetic.source,
                    nbt.contains("Network"),
                    network.getFloat("Capacity"),
                    network.getFloat("Stress"));
        }
        if (be instanceof BlazeBurnerBlockEntity burner)
            LOG.info(
                    "INDUSTRY_HEAT pos={} heat={} burnTicks={} creative={}",
                    pos,
                    burner.getHeatLevelFromBlock(),
                    burner.getRemainingBurnTime(),
                    burner.isCreative());
        if (be.getClass()
                .getName()
                .equals(
                        "com.jesz.createdieselgenerators.content.distillation.DistillationTankBlockEntity"))
            LOG.info(
                    "INDUSTRY_CDG pos={} height={} width={} heat={}",
                    pos,
                    be.getClass().getMethod("getHeight").invoke(be),
                    be.getClass().getMethod("getWidth").invoke(be),
                    be.getClass().getMethod("getHeat").invoke(be));
        // Deployer capability lookup can initialize its fake player. Its full saved NBT above
        // contains HeldItem/Inventory/Overflow; observation must never initialize that handler.
        String blockId = BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()).toString();
        boolean safeItems = blockId.equals("create:depot") || blockId.equals("create:basin");
        boolean safeFluids =
                blockId.equals("create:basin")
                        || blockId.equals("create:fluid_tank")
                        || blockId.equals("createdieselgenerators:distillation_tank");
        if (!safeItems && !safeFluids) return;
        if (safeFluids)
            for (Direction side : Direction.values()) {
                IFluidHandler handler = fluids(level, pos, side);
                if (handler == null) continue;
                for (int i = 0; i < handler.getTanks(); i++)
                    LOG.info(
                            "INDUSTRY_FLUID pos={} side={} tank={} fluid={} capacity={}",
                            pos,
                            side,
                            i,
                            handler.getFluidInTank(i),
                            handler.getTankCapacity(i));
            }
        IItemHandler handler =
                safeItems
                        ? level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP)
                        : null;
        if (handler != null)
            for (int i = 0; i < handler.getSlots(); i++)
                if (!handler.getStackInSlot(i).isEmpty())
                    LOG.info(
                            "INDUSTRY_ITEM pos={} slot={} stack={}",
                            pos,
                            i,
                            handler.getStackInSlot(i));
    }

    private static void precisionInput(ServerPlayer player, BlockPos base) {
        require(
                !record(player).getBoolean("PrecisionStarted"),
                "One finite precision attempt per fixture");
        require(
                items(player.serverLevel(), base.offset(2, 3, 5)).getStackInSlot(0).isEmpty(),
                "Precision depot must be empty");
        transfer(player, base, 5, base.offset(2, 3, 5), 1);
        record(player).putBoolean("PrecisionStarted", true);
        record(player).putInt("PrecisionFed", 0);
        LOG.info(
                "INDUSTRY_PRECISION start suppliedGoldSheet=1 suppliedCog=5 suppliedLargeCog=5 suppliedNugget=5 noInjectedResult=true");
    }

    private static void precisionNext(ServerPlayer player, BlockPos base) {
        require(record(player).getBoolean("PrecisionStarted"), "Run precision-input first");
        int fed = record(player).getInt("PrecisionFed");
        require(fed < 15, "Finite fifteen-deployment budget exhausted");
        if (fed > 0) verifyPrecision(player, base);
        KineticBlockEntity deployer =
                (KineticBlockEntity) player.serverLevel().getBlockEntity(base.offset(2, 5, 5));
        require(
                Math.abs(deployer.getSpeed()) == 32 && !deployer.isOverStressed(),
                "Deployer requires actual 32RPM without overstress");
        transfer(player, base, 6 + fed % 3, base.offset(2, 5, 5), 1);
        record(player).putInt("PrecisionFed", fed + 1);
        LOG.info(
                "INDUSTRY_PRECISION fed={} ingredientSlot={} actualSupplyTransfer=1",
                fed + 1,
                6 + fed % 3);
    }

    private static void verifyPrecision(ServerPlayer player, BlockPos base) {
        int fed = record(player).getInt("PrecisionFed");
        require(fed > 0, "No deployment fed");
        ServerLevel level = player.serverLevel();
        IItemHandler hand = items(level, base.offset(2, 5, 5));
        for (int i = 0; i < hand.getSlots(); i++)
            require(hand.getStackInSlot(i).isEmpty(), "Wait for real ingredient consumption");
        for (int i = 0; i < 3; i++) {
            int consumed = (fed + 2 - i) / 3;
            require(
                    supplies(level, base).getStackInSlot(6 + i).getCount() == 5 - consumed,
                    "Precision supply accounting mismatch slot " + (6 + i));
        }
        ItemStack output = items(level, base.offset(2, 3, 5)).getStackInSlot(0);
        require(!output.isEmpty(), "Precision depot empty");
        var assembly = output.get(AllDataComponents.SEQUENCED_ASSEMBLY);
        if (fed < 15) {
            require(
                    assembly != null
                            && assembly.step() == fed
                            && assembly.id()
                                    .equals(
                                            ResourceLocation.parse(
                                                    "create:sequenced_assembly/precision_mechanism")),
                    "Expected actual assembly step " + fed + " observed=" + assembly);
            LOG.info(
                    "INDUSTRY_PRECISION progress step={} loops={} component={} output={}",
                    fed,
                    fed / 3,
                    assembly,
                    output);
        } else {
            var recipe =
                    (SequencedAssemblyRecipe)
                            level.getRecipeManager()
                                    .byKey(
                                            ResourceLocation.parse(
                                                    "create:sequenced_assembly/precision_mechanism"))
                                    .orElseThrow()
                                    .value();
            require(
                    assembly == null
                            && recipe.resultPool.stream()
                                    .anyMatch(
                                            result ->
                                                    ItemStack.isSameItemSameComponents(
                                                                    result.getStack(), output)
                                                            && result.getStack().getCount()
                                                                    == output.getCount()),
                    "Expected actual weighted terminal recipe output");
            LOG.info(
                    "INDUSTRY_VERIFY precision=COMPLETED_FIVE_LOOPS deployments=15 consumedGoldSheet=1 consumedCog=5 consumedLargeCog=5 consumedNugget=5 output={} mechanismSuccess={}",
                    output,
                    output.is(item("create:precision_mechanism", 1).getItem()));
        }
    }

    private static void repeatBoundary(ServerPlayer player, BlockPos base)
            throws ReflectiveOperationException {
        require(
                record(player).getString("InputMode").equals("CONTROLLED_BOUNDARY_INJECTION")
                        && record(player).getInt("Batches") == 1,
                "Repeat requires exactly one controlled settled batch");
        verifyDistillation(player, base);
        IFluidHandler handler =
                fluids(player.serverLevel(), base.offset(12, 1, 2), Direction.NORTH);
        FluidStack input =
                new FluidStack(
                        BuiltInRegistries.FLUID.get(ResourceLocation.parse(CDG + "crude_oil")),
                        1000);
        require(
                handler != null && handler.fill(input, IFluidHandler.FluidAction.SIMULATE) == 1000,
                "Repeat inlet must accept exactly 1000mB");
        record(player)
                .putString("RepeatBaseline", fluidTotals(player.serverLevel(), base).toString());
        require(
                handler.fill(input, IFluidHandler.FluidAction.EXECUTE) == 1000,
                "Repeat fill mismatch");
        record(player).putInt("Batches", 2);
        LOG.info(
                "INDUSTRY_MUTATION BOUNDARY_REPEAT baseline={} addedCrude=1000 totalSupplied=2000 NOT_NATURAL_EXTRACTION",
                record(player).getString("RepeatBaseline"));
    }

    private static CompoundTag liquidState(ServerLevel level, BlockPos base) {
        CompoundTag state = new CompoundTag();
        for (BlockPos pos :
                new BlockPos[] {
                    base.offset(12, 1, 0),
                    base.offset(12, 2, 0),
                    base.offset(12, 3, 0),
                    base.offset(12, 1, 2),
                    base.offset(12, 2, 3),
                    base.offset(12, 3, 4)
                }) {
            IFluidHandler handler = fluids(level, pos, Direction.SOUTH);
            require(handler != null, "Missing checkpoint fluid handler " + pos);
            CompoundTag tank = new CompoundTag();
            tank.putInt("Tanks", handler.getTanks());
            for (int i = 0; i < handler.getTanks(); i++) {
                tank.put(
                        "Fluid" + i,
                        handler.getFluidInTank(i).saveOptional(level.registryAccess()));
                tank.putInt("Capacity" + i, handler.getTankCapacity(i));
            }
            state.put(pos.toShortString(), tank);
        }
        return state;
    }

    private static void liquidCheckpoint(ServerPlayer player, BlockPos base)
            throws ReflectiveOperationException {
        verifyDistillation(player, base);
        CompoundTag liquids = liquidState(player.serverLevel(), base);
        record(player).put("LiquidCheckpoint", liquids);
        record(player).putString("LiquidSession", SESSION);
        LOG.info(
                "INDUSTRY_LIQUID_CHECKPOINT session={} batches={} liquids={}",
                SESSION,
                record(player).getInt("Batches"),
                liquids);
    }

    private static void verifyLiquidPersist(ServerPlayer player, BlockPos base)
            throws ReflectiveOperationException {
        require(record(player).contains("LiquidCheckpoint"), "No liquid checkpoint");
        require(!SESSION.equals(record(player).getString("LiquidSession")), "Requires a fresh JVM");
        verifyDistillation(player, base);
        require(
                record(player)
                        .getCompound("LiquidCheckpoint")
                        .equals(liquidState(player.serverLevel(), base)),
                "Liquid identity/amount/components/capacity/distribution changed after restart");
        LOG.info(
                "INDUSTRY_VERIFY liquidPersistence=EQUAL sixPhysicalTanks=true oldSession={} newSession={} batches={}",
                record(player).getString("LiquidSession"),
                SESSION,
                record(player).getInt("Batches"));
    }

    private static void recipes(ServerLevel level) {
        for (String id :
                new String[] {
                    "create:pressing/iron_ingot",
                    "create:mixing/brass_ingot",
                    "create:sequenced_assembly/precision_mechanism",
                    CDG + "distillation/crude_oil"
                }) {
            var holder = level.getRecipeManager().byKey(ResourceLocation.parse(id));
            require(holder.isPresent(), "Missing LIVE recipe " + id);
            var recipe = holder.get().value();
            LOG.info(
                    "INDUSTRY_RECIPE id={} type={} class={} ingredients={} result={}",
                    id,
                    recipe.getType(),
                    recipe.getClass().getName(),
                    recipe.getIngredients(),
                    recipe.getResultItem(level.registryAccess()));
            if (recipe instanceof SequencedAssemblyRecipe assembly)
                LOG.info(
                        "INDUSTRY_SEQUENCE id={} loops={} steps={} weightedOutputs={}",
                        id,
                        assembly.getLoops(),
                        assembly.getSequence().stream()
                                .map(step -> step.getRecipe().getIngredients())
                                .toList(),
                        assembly.resultPool.stream()
                                .map(result -> result.getStack() + " weight=" + result.getChance())
                                .toList());
            if (recipe instanceof ProcessingRecipe<?, ?> processing)
                LOG.info(
                        "INDUSTRY_PROCESSING id={} duration={} heat={} fluidsIn={} fluidsOut={} rollableItems={}",
                        id,
                        processing.getProcessingDuration(),
                        processing.getRequiredHeat(),
                        processing.getFluidIngredients(),
                        processing.getFluidResults(),
                        processing.getRollableResultsAsItemStacks());
        }
    }

    private static Map<String, Integer> fluidTotals(ServerLevel level, BlockPos base) {
        Map<String, Integer> totals = new TreeMap<>();
        // One capability per physical layer/tank. Never sum all six directional views of the same
        // tank.
        for (BlockPos pos :
                new BlockPos[] {
                    base.offset(12, 1, 0),
                    base.offset(12, 2, 0),
                    base.offset(12, 3, 0),
                    base.offset(12, 1, 2),
                    base.offset(12, 2, 3),
                    base.offset(12, 3, 4)
                }) {
            IFluidHandler handler = fluids(level, pos, Direction.SOUTH);
            if (handler == null) continue;
            // Before conversion, the vertical Create tanks share their bottom controller.
            if (pos.getZ() == base.getZ()
                    && pos.getY() > base.getY() + 1
                    && !level.getBlockEntity(pos).getClass().getName().contains("Distillation"))
                continue;
            for (int i = 0; i < handler.getTanks(); i++) {
                FluidStack stack = handler.getFluidInTank(i);
                if (!stack.isEmpty())
                    totals.merge(
                            BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString(),
                            stack.getAmount(),
                            Integer::sum);
            }
        }
        return totals;
    }

    private static boolean onlyFluid(ServerLevel level, BlockPos pos, String id, int amount) {
        IFluidHandler handler = fluids(level, pos, Direction.NORTH);
        return handler != null
                && handler.getTanks() == 1
                && handler.getFluidInTank(0).getAmount() == amount
                && BuiltInRegistries.FLUID
                        .getKey(handler.getFluidInTank(0).getFluid())
                        .toString()
                        .equals(id);
    }

    private static BlockEntity tower(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        require(
                be != null
                        && be.getClass()
                                .getName()
                                .equals(
                                        "com.jesz.createdieselgenerators.content.distillation.DistillationTankBlockEntity"),
                "Native CDG tank missing at " + pos);
        return be;
    }

    private static IFluidHandler fluids(ServerLevel level, BlockPos pos, Direction side) {
        return level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side);
    }

    private static IItemHandler supplies(ServerLevel level, BlockPos base) {
        return items(level, base.offset(6, 0, 2));
    }

    private static IItemHandler items(ServerLevel level, BlockPos pos) {
        IItemHandler handler =
                level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
        require(handler != null, "Item capability missing at " + pos);
        return handler;
    }

    private static ItemStack insert(IItemHandler handler, ItemStack stack, boolean simulate) {
        for (int i = 0; i < handler.getSlots() && !stack.isEmpty(); i++)
            stack = handler.insertItem(i, stack, simulate);
        return stack;
    }

    private static int count(IItemHandler handler, String id) {
        int count = 0;
        for (int i = 0; i < handler.getSlots(); i++)
            if (BuiltInRegistries.ITEM
                    .getKey(handler.getStackInSlot(i).getItem())
                    .toString()
                    .equals(id)) count += handler.getStackInSlot(i).getCount();
        return count;
    }

    private static ItemStack item(String id, int count) {
        ResourceLocation key = ResourceLocation.parse(id);
        require(BuiltInRegistries.ITEM.containsKey(key), "Missing fixture item " + id);
        return new ItemStack(BuiltInRegistries.ITEM.get(key), count);
    }

    private static void stone(Map<BlockPos, BlockState> plan, BlockPos pos) {
        plan.put(pos, Blocks.STONE.defaultBlockState());
    }

    private static void add(
            Map<BlockPos, BlockState> plan,
            BlockPos base,
            int x,
            int y,
            int z,
            String id,
            String... properties) {
        ResourceLocation key = ResourceLocation.parse(id);
        require(BuiltInRegistries.BLOCK.containsKey(key), "Missing pinned block " + id);
        BlockState state = BuiltInRegistries.BLOCK.get(key).defaultBlockState();
        for (int i = 0; i < properties.length; i += 2)
            state = property(state, properties[i], properties[i + 1]);
        plan.put(base.offset(x, y, z), state);
    }

    private static <T extends Comparable<T>> BlockState with(
            BlockState state, Property<T> property, String value) {
        return state.setValue(
                property,
                property.getValue(value)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Invalid state value " + value)));
    }

    private static BlockState property(BlockState state, String name, String value) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty(name);
        require(property != null, "Missing property " + name + " on " + state);
        return with(state, property, value);
    }

    private static void place(ServerLevel level, BlockPos pos, BlockState state) {
        if (level.getBlockState(pos) != state)
            require(level.setBlock(pos, state, Block.UPDATE_ALL), "Placement failed " + pos);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
