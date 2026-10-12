package dev.starfold.ship;

import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.starfold.chart.GroundEphemeris;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/** What a ship needs where there is no air: a thruster that pushes by itself, and a gyro that keeps the deck level,
 *  damps horizontal drift. Lift nozzles, not the gyro, carry the ship's weight.
 *  A booster hastens every thruster on the body. Thrusters accept controller commands or redstone; gyros need
 *  redstone. None pushes anything off a Sable body.
 *  Crafted from vanilla materials; no fuel. */
public final class Thrusters {
    /** Full nozzle force; ten lifts support the material-mass Hauler at approximately 55% throttle. */
    public static final double THRUST = 3350, BOOST = 4;

    /** A gyro turns the deck level at up to TURN radians a second, and takes about a twentieth of a second to bite. It
     *  wears away turning about the up at STEER a second, so that a ship holds its heading and a turning thruster sets a
     *  rate of turn. YAW is its corrective target, not a final cap on later actor impulses. Each gyro contributes
     *  damping; two on the starter hull oppose the turning pair. Where there is no air nothing else stops a spin. */
    public static final double TURN = 1.5, YAW = 1, STEER = 1, BITE = 20;

    /** Horizontal drift damping per second under a powered gyro. Vertical damping is real lift control. */
    public static final double HOLD = .5;

    private static final java.util.Map<ServerSubLevel, Long> STEADIED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Whether a powered gyro has been at work on this body within the last ticks. */
    public static boolean steadied(ServerSubLevel body, long tick) {
        Long at = STEADIED.get(body);
        return at != null && tick - at <= 2;
    }

    private static final java.util.Map<ServerSubLevel, Long> BOOSTED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    // Actors tick in hash order, so a thruster may run before the booster on the same body.
    public static boolean boosted(ServerSubLevel body, long tick) {
        Long at = BOOSTED.get(body);
        return at != null && tick - at <= 2 || HelmServer.boosted(body);
    }

    private static final java.util.Map<ServerSubLevel, Long> LIFTING =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    // The hold can run before this body's actors; keep their upward push through the same short
    // tick window.
    static boolean lifting(ServerSubLevel body, long tick) {
        Long at = LIFTING.get(body);
        return at != null && tick - at <= 2;
    }

    private static final java.util.Map<ServerSubLevel, Push> PUSHING =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Sum powered unit pushes in Level axes for this tick, with the same actor-order grace as lifting. */
    public record Push(long tick, dev.starfold.geometry.BentStripGeometry.Point direction) {
        public Push add(long now, dev.starfold.geometry.BentStripGeometry.Point push) {
            return new Push(now, now == tick ? direction.add(push) : push);
        }

        public boolean forward(long now, dev.starfold.geometry.BentStripGeometry.Point nose) {
            return now >= tick && now - tick <= 2 && direction.dot(nose) > .5;
        }
    }

    public static dev.starfold.geometry.BentStripGeometry.Point push(
            ServerSubLevel body, long tick) {
        var push = PUSHING.get(body);
        return push != null && tick >= push.tick() && tick - push.tick() <= 2
                ? push.direction()
                : GroundEphemeris.ZERO;
    }

    public static boolean forward(
            ServerSubLevel body, long tick, dev.starfold.geometry.BentStripGeometry.Point nose) {
        var push = PUSHING.get(body);
        return push != null && push.forward(tick, nose);
    }

    static double impulse(int power, double step, boolean boosted) {
        return THRUST * power / 15 * step * (boosted ? BOOST : 1);
    }

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("starfold");
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("starfold");
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, "starfold");
    private static Block helm;
    private static BlockEntityType<Helm> helmType;
    private static Block scope, scopeNav, scopePrecise;
    private static BlockEntityType<Scope> scopeType;
    private static Block thruster, gyro, booster;
    private static BlockEntityType<Thruster> thrusterType;
    private static BlockEntityType<Gyro> gyroType;
    private static BlockEntityType<Booster> boosterType;

    public static void initialize(IEventBus modBus) {
        // None passes redstone on: a lever on one of them lights that one, not whatever stands
        // beside it.
        var properties =
                BlockBehaviour.Properties.of()
                        .mapColor(MapColor.METAL)
                        .strength(2)
                        .requiresCorrectToolForDrops()
                        .isRedstoneConductor((state, level, pos) -> false)
                        .lightLevel(
                                state ->
                                        state.getBlock() instanceof ThrusterBlock
                                                ? (state.getValue(BlockStateProperties.POWER) == 0
                                                        ? 0
                                                        : state.getValue(BlockStateProperties.POWER)
                                                                        < 8
                                                                ? 8
                                                                : 15)
                                                : state.getBlock() instanceof BoosterBlock
                                                                && state.getValue(
                                                                        BlockStateProperties
                                                                                .POWERED)
                                                        ? 12
                                                        : 0);
        BLOCKS.register("thruster", () -> thruster = new ThrusterBlock(properties));
        BLOCKS.register("gyro", () -> gyro = new GyroBlock(properties));
        BLOCKS.register("booster", () -> booster = new BoosterBlock(properties));
        ITEMS.register("thruster", () -> new BlockItem(thruster, new Item.Properties()));
        ITEMS.register("gyro", () -> new BlockItem(gyro, new Item.Properties()));
        ITEMS.register("booster", () -> new BlockItem(booster, new Item.Properties()));
        ENTITIES.register(
                "thruster",
                () ->
                        thrusterType =
                                BlockEntityType.Builder.of(Thruster::new, thruster).build(null));
        ENTITIES.register(
                "gyro", () -> gyroType = BlockEntityType.Builder.of(Gyro::new, gyro).build(null));
        ENTITIES.register(
                "booster",
                () -> boosterType = BlockEntityType.Builder.of(Booster::new, booster).build(null));
        BLOCKS.register("scope", () -> scope = new ScopeBlock(properties, 1));
        BLOCKS.register("scope_nav", () -> scopeNav = new ScopeBlock(properties, 2));
        BLOCKS.register("scope_precise", () -> scopePrecise = new ScopeBlock(properties, 3));
        ITEMS.register("scope", () -> new BlockItem(scope, new Item.Properties()));
        ITEMS.register("scope_nav", () -> new BlockItem(scopeNav, new Item.Properties()));
        ITEMS.register("scope_precise", () -> new BlockItem(scopePrecise, new Item.Properties()));
        ENTITIES.register(
                "scope",
                () ->
                        scopeType =
                                BlockEntityType.Builder.of(
                                                Scope::new, scope, scopeNav, scopePrecise)
                                        .build(null));
        BLOCKS.register("helm", () -> helm = new HelmBlock(properties));
        ITEMS.register("helm", () -> new BlockItem(helm, new Item.Properties()));
        ENTITIES.register(
                "helm", () -> helmType = BlockEntityType.Builder.of(Helm::new, helm).build(null));
        HelmServer.initialize();
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        ENTITIES.register(modBus);
        modBus.addListener(
                (BuildCreativeModeTabContentsEvent event) -> {
                    if (event.getTabKey().equals(CreativeModeTabs.REDSTONE_BLOCKS)) {
                        event.accept(thruster);
                        event.accept(gyro);
                        event.accept(booster);
                        event.accept(scope);
                        event.accept(scopeNav);
                        event.accept(scopePrecise);
                        event.accept(helm);
                    }
                });
    }

    static final class HelmBlock extends Block implements EntityBlock {
        HelmBlock(Properties properties) {
            super(properties);
            registerDefaultState(
                    stateDefinition
                            .any()
                            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        }

        @Override
        protected void createBlockStateDefinition(
                StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(BlockStateProperties.HORIZONTAL_FACING);
        }

        // FACING is the arrow/bow, away from the placer; the console screen is on the opposite
        // side.
        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            return defaultBlockState()
                    .setValue(
                            BlockStateProperties.HORIZONTAL_FACING,
                            context.getHorizontalDirection());
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new Helm(pos, state);
        }

        @Override
        protected net.minecraft.world.InteractionResult useWithoutItem(
                BlockState state,
                Level level,
                BlockPos pos,
                net.minecraft.world.entity.player.Player player,
                net.minecraft.world.phys.BlockHitResult hit) {
            if (player instanceof net.minecraft.server.level.ServerPlayer server)
                HelmServer.use(server, pos);
            return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide);
        }
    }

    static final class Helm extends BlockEntity {
        Helm(BlockPos pos, BlockState state) {
            super(helmType, pos, state);
        }
    }

    public static final class ScopeBlock extends Block implements EntityBlock {
        final int tier;

        ScopeBlock(Properties properties, int tier) {
            super(properties);
            this.tier = tier;
            registerDefaultState(
                    stateDefinition.any().setValue(BlockStateProperties.FACING, Direction.NORTH));
        }

        @Override
        protected void createBlockStateDefinition(
                StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(BlockStateProperties.FACING);
        }

        // Look into the hull when placing: the lens points away from the placer, not back into
        // their face.
        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            return defaultBlockState()
                    .setValue(BlockStateProperties.FACING, context.getNearestLookingDirection());
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new Scope(pos, state);
        }
    }

    public static final class Scope extends BlockEntity {
        Scope(BlockPos pos, BlockState state) {
            super(scopeType, pos, state);
        }

        int tier() {
            return getBlockState().getBlock() instanceof ScopeBlock block ? block.tier : 0;
        }
    }

    public static int scopeTier(dev.ryanhcode.sable.sublevel.SubLevel body) {
        int tier = 0;
        for (var holder : body.getPlot().getLoadedChunks())
            for (var entity : holder.getChunk().getBlockEntities().values())
                if (entity instanceof Scope scope && !scope.isRemoved())
                    tier = Math.max(tier, scope.tier());
        return tier;
    }

    /** The nozzle is on the side the block faces; the ship is pushed the other way, harder for a stronger signal. */
    public static final class ThrusterBlock extends Block implements EntityBlock {
        ThrusterBlock(Properties properties) {
            super(properties);
            registerDefaultState(
                    stateDefinition
                            .any()
                            .setValue(BlockStateProperties.FACING, Direction.NORTH)
                            .setValue(BlockStateProperties.POWER, 0));
        }

        @Override
        protected void createBlockStateDefinition(
                StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(BlockStateProperties.FACING, BlockStateProperties.POWER);
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            return defaultBlockState()
                    .setValue(
                            BlockStateProperties.FACING,
                            context.getNearestLookingDirection().getOpposite())
                    .setValue(
                            BlockStateProperties.POWER,
                            context.getLevel().getBestNeighborSignal(context.getClickedPos()));
        }

        @Override
        protected void neighborChanged(
                BlockState state,
                Level level,
                BlockPos pos,
                Block block,
                BlockPos from,
                boolean moving) {
            signal(state, level, pos);
        }

        @Override
        protected void onPlace(
                BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
            signal(state, level, pos);
        }

        private static void signal(BlockState state, Level level, BlockPos pos) {
            if (level.isClientSide) return;
            int power = level.getBestNeighborSignal(pos);
            if (power != state.getValue(BlockStateProperties.POWER))
                level.setBlock(
                        pos,
                        state.setValue(BlockStateProperties.POWER, power),
                        Block.UPDATE_CLIENTS);
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new Thruster(pos, state);
        }

        @Override
        public <T extends BlockEntity>
                net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(
                        Level level, BlockState state, BlockEntityType<T> type) {
            return level.isClientSide && type == thrusterType
                    ? (world, pos, block, entity) -> ThrusterClient.tick(world, pos, block)
                    : null;
        }
    }

    public static final class Thruster extends BlockEntity implements BlockEntitySubLevelActor {
        Thruster(BlockPos pos, BlockState state) {
            super(thrusterType, pos, state);
        }

        @Override
        public void sable$physicsTick(ServerSubLevel body, RigidBodyHandle handle, double step) {
            // Vertical chart forces are integrated in PRE/POST by the powered-gyro controller.
            if (LiftServer.owns(body, getBlockState().getValue(BlockStateProperties.FACING)))
                return;
            int power = HelmServer.power(body, getBlockPos(), getBlockState());
            if (power == 0) return;
            // A point and a direction of the body's own, as its blocks lie in their plot.
            var push =
                    getBlockState().getValue(BlockStateProperties.FACING).getOpposite().getNormal();
            double impulse =
                    impulse(
                            power,
                            step,
                            getLevel() != null && boosted(body, getLevel().getGameTime()));
            var at = getBlockPos();
            if (getLevel() != null) {
                long tick = getLevel().getGameTime();
                var direction =
                        body.logicalPose()
                                .orientation()
                                .transform(new Vector3d(push.getX(), push.getY(), push.getZ()));
                if (direction.y > .5) LIFTING.put(body, tick);
                var unit =
                        new dev.starfold.geometry.BentStripGeometry.Point(
                                direction.x, direction.y, direction.z);
                PUSHING.compute(
                        body,
                        (key, old) -> old == null ? new Push(tick, unit) : old.add(tick, unit));
            }
            handle.applyImpulseAtPoint(
                    new Vector3d(at.getX() + .5, at.getY() + .5, at.getZ() + .5),
                    new Vector3d(
                            push.getX() * impulse, push.getY() * impulse, push.getZ() * impulse));
        }
    }

    /** Powered, it turns the body until its own up is the up of the planet below, stops it rolling and pitching, and
     *  holds its turning about that up to a rate a crew can stand on; the lift controller commands real nozzles
     *  while SableFrames damps horizontal drift. Unpowered it is a block. */
    static final class GyroBlock extends Block implements EntityBlock {
        GyroBlock(Properties properties) {
            super(properties);
            registerDefaultState(
                    stateDefinition.any().setValue(BlockStateProperties.POWERED, false));
        }

        @Override
        protected void createBlockStateDefinition(
                StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(BlockStateProperties.POWERED);
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            return defaultBlockState()
                    .setValue(
                            BlockStateProperties.POWERED,
                            context.getLevel().hasNeighborSignal(context.getClickedPos()));
        }

        @Override
        protected void neighborChanged(
                BlockState state,
                Level level,
                BlockPos pos,
                Block block,
                BlockPos from,
                boolean moving) {
            signal(state, level, pos);
        }

        @Override
        protected void onPlace(
                BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
            signal(state, level, pos);
        }

        private static void signal(BlockState state, Level level, BlockPos pos) {
            if (level.isClientSide) return;
            boolean powered = level.hasNeighborSignal(pos);
            if (powered != state.getValue(BlockStateProperties.POWERED))
                level.setBlock(
                        pos,
                        state.setValue(BlockStateProperties.POWERED, powered),
                        Block.UPDATE_CLIENTS);
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new Gyro(pos, state);
        }
    }

    static final class Gyro extends BlockEntity implements BlockEntitySubLevelActor {
        Gyro(BlockPos pos, BlockState state) {
            super(gyroType, pos, state);
        }

        @Override
        public void sable$physicsTick(ServerSubLevel body, RigidBodyHandle handle, double step) {
            if (!getBlockState().getValue(BlockStateProperties.POWERED)) return;
            if (getLevel() != null) STEADIED.put(body, getLevel().getGameTime());
            var correction =
                    level(
                            new Quaterniond(body.logicalPose().orientation()),
                            handle.getAngularVelocity(new Vector3d()),
                            step,
                            ShipHost.get().externalRoll(getLevel(), body));
            if (LiftServer.enabled(body)) correction.y *= LiftControl.CHART_YAW_DAMPING;
            handle.addLinearAndAngularVelocity(new Vector3d(), correction);
        }
    }

    static final class BoosterBlock extends Block implements EntityBlock {
        BoosterBlock(Properties properties) {
            super(properties);
            registerDefaultState(
                    stateDefinition.any().setValue(BlockStateProperties.POWERED, false));
        }

        @Override
        protected void createBlockStateDefinition(
                StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(BlockStateProperties.POWERED);
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            return defaultBlockState()
                    .setValue(
                            BlockStateProperties.POWERED,
                            context.getLevel().hasNeighborSignal(context.getClickedPos()));
        }

        @Override
        protected void neighborChanged(
                BlockState state,
                Level level,
                BlockPos pos,
                Block block,
                BlockPos from,
                boolean moving) {
            signal(state, level, pos);
        }

        @Override
        protected void onPlace(
                BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
            signal(state, level, pos);
        }

        private static void signal(BlockState state, Level level, BlockPos pos) {
            if (level.isClientSide) return;
            boolean powered = level.hasNeighborSignal(pos);
            if (powered != state.getValue(BlockStateProperties.POWERED))
                level.setBlock(
                        pos,
                        state.setValue(BlockStateProperties.POWERED, powered),
                        Block.UPDATE_CLIENTS);
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new Booster(pos, state);
        }
    }

    static final class Booster extends BlockEntity implements BlockEntitySubLevelActor {
        Booster(BlockPos pos, BlockState state) {
            super(boosterType, pos, state);
        }

        @Override
        public void sable$physicsTick(ServerSubLevel body, RigidBodyHandle handle, double step) {
            if (!getBlockState().getValue(BlockStateProperties.POWERED)) return;
            if (getLevel() != null) BOOSTED.put(body, getLevel().getGameTime());
        }
    }

    /** The change of spin that brings a body's up towards the level's up: about the axis that carries one to the other,
     *  faster the further off it is, and against whatever roll and pitch it has. Spin about the up itself is worn away
     *  slowly, and corrected toward YAW beyond it; later actor impulses can exceed that target. */
    public static Vector3d level(Quaterniond orientation, Vector3d spin, double step) {
        return level(orientation, spin, step, false);
    }

    public static Vector3d level(
            Quaterniond orientation, Vector3d spin, double step, boolean zeroYaw) {
        var up = orientation.transform(new Vector3d(0, 1, 0));
        var axis = new Vector3d(up).cross(0, 1, 0);
        double sine = axis.length(), angle = Math.atan2(sine, up.y);
        // Upside down exactly, every level axis is as good: take the body's own.
        if (sine < 1e-6) {
            axis = orientation.transform(new Vector3d(1, 0, 0));
            axis.y = 0;
            sine = axis.length();
            if (sine < 1e-6) axis.set(1, 0, 0);
            else axis.div(sine);
            if (up.y > 0) angle = 0;
        } else axis.div(sine);
        var wanted = axis.mul(Math.min(TURN, angle * TURN));
        double turning = zeroYaw ? 0 : Math.clamp(spin.y, -YAW, YAW);
        wanted.y = turning;
        var change = wanted.sub(spin).mul(Math.min(1, BITE * step));
        change.y -= turning * Math.min(1, STEER * step);
        return change;
    }

    private Thrusters() {}
}
