package dev.starfold.ship;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.starfold.geometry.BentStripGeometry;
import dev.starfold.geometry.ChartLabSpace;
import dev.starfold.ground.PacketMap;
import dev.starfold.ground.PayloadMap;
import dev.starfold.network.StarfoldNetwork;
import dev.starfold.planet.GroundCoordinates;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.sky.Space;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.DeferredRegister;

import org.joml.Vector3d;

/** A sill-owned instrument, not temporary terrain. Chart gravity is Level -Y, never hull -Y. */
public final class Airstair {
    public static final int MAX_REACH = 10, SETTLE_TICKS = 20;
    static final double ENTER_SPEED = .12, EXIT_SPEED = .30;

    /** A lowered stair stands on the ground, not on the hull: it stays where it was planned until the sill really leaves it. */
    static final double HOLD_DISTANCE = .3, HOLD_TURN = Math.cos(Math.toRadians(4));

    static final int HOLD_TICKS = 10;
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("starfold-airstair");
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("starfold");
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("starfold");
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, "starfold");
    private static Block block;
    static BlockEntityType<Stair> type;
    private static final java.util.Map<Stair, Boolean> loaded =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static void initialize(IEventBus bus) {
        BLOCKS.register(
                "airstair",
                () ->
                        block =
                                new StairBlock(
                                        Block.Properties.of()
                                                .mapColor(
                                                        net.minecraft.world.level.material.MapColor
                                                                .METAL)
                                                .sound(
                                                        net.minecraft.world.level.block.SoundType
                                                                .METAL)
                                                .strength(2)
                                                .requiresCorrectToolForDrops()));
        ITEMS.register("airstair", () -> new BlockItem(block, new Item.Properties()));
        ENTITIES.register(
                "airstair", () -> type = BlockEntityType.Builder.of(Stair::new, block).build(null));
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
        bus.addListener(
                (net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent e) -> {
                    if (e.getTabKey().equals(CreativeModeTabs.REDSTONE_BLOCKS)) e.accept(block);
                });
        StarfoldNetwork.playS2C(State.TYPE, State.CODEC);
        PayloadMap.register(
                State.class,
                (player, frame, state) -> {
                    var hinge = PacketMap.plane(player, frame, state.x, state.y, state.z);
                    if (hinge == null) return state;
                    int turns =
                            PacketMap.turns(
                                    PlanetAccess.of(player.level()),
                                    frame,
                                    state.x,
                                    state.y,
                                    state.z);
                    return state.inObserverChart(hinge, turns);
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.tick.LevelTickEvent.Post e) -> {
                    if (e.getLevel() instanceof ServerLevel level) tick(level);
                });
    }

    static int settled(
            int previous,
            boolean chart,
            boolean gravity,
            boolean lift,
            double speed,
            double reach) {
        if (!chart
                || !gravity
                || lift
                || !Double.isFinite(speed)
                || !Double.isFinite(reach)
                || reach <= 0
                || reach > MAX_REACH
                || speed > (previous >= SETTLE_TICKS ? EXIT_SPEED : ENTER_SPEED)) return 0;
        return Math.min(SETTLE_TICKS, previous + 1);
    }

    /** Ticks of continuous motion a lowered stair has seen, or -1 once it must fold. Resting jitter of a heavy hull is neither a
     *  departure nor a new landing: one fast tick does not fold it, drift past the sill gap or a lift command does at once. */
    static int held(
            int moving,
            boolean chart,
            boolean gravity,
            boolean lift,
            double speed,
            double drift,
            double turn) {
        if (!chart
                || !gravity
                || lift
                || !Double.isFinite(speed)
                || !Double.isFinite(drift)
                || drift > HOLD_DISTANCE
                || !(turn >= HOLD_TURN)) return -1;
        if (speed <= EXIT_SPEED) return 0;
        return moving + 1 >= HOLD_TICKS ? -1 : moving + 1;
    }

    static final class StairBlock extends Block implements EntityBlock {
        StairBlock(Properties properties) {
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

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            return defaultBlockState()
                    .setValue(
                            BlockStateProperties.HORIZONTAL_FACING,
                            context.getHorizontalDirection().getOpposite());
        }

        @Override
        protected BlockState rotate(
                BlockState state, net.minecraft.world.level.block.Rotation rotation) {
            return state.setValue(
                    BlockStateProperties.HORIZONTAL_FACING,
                    rotation.rotate(state.getValue(BlockStateProperties.HORIZONTAL_FACING)));
        }

        @Override
        protected BlockState mirror(
                BlockState state, net.minecraft.world.level.block.Mirror mirror) {
            return state.rotate(
                    mirror.getRotation(state.getValue(BlockStateProperties.HORIZONTAL_FACING)));
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new Stair(pos, state);
        }
    }

    public static final class Stair extends BlockEntity {
        public int settled, width = 1, moving;
        public double length, visual, oldVisual;
        double treadRun = AirstairGeometry.STEP;
        long receivedNanos;
        Vector3d previous,
                published = new Vector3d(),
                publishedOut = new Vector3d(),
                anchor,
                anchorOut;
        java.util.List<AABB> boxes = java.util.List.of();

        Stair(BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    record State(
            BlockPos pos,
            double x,
            double y,
            double z,
            double ox,
            double oz,
            double length,
            double treadRun,
            int width)
            implements CustomPacketPayload {
        static final Type<State> TYPE = new Type<>(ResourceLocation.parse("starfold:airstair"));
        static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeBlockPos(p.pos);
                            b.writeDouble(p.x);
                            b.writeDouble(p.y);
                            b.writeDouble(p.z);
                            b.writeDouble(p.ox);
                            b.writeDouble(p.oz);
                            b.writeDouble(p.length);
                            b.writeDouble(p.treadRun);
                            b.writeByte(p.width);
                        },
                        b ->
                                new State(
                                        b.readBlockPos(),
                                        b.readDouble(),
                                        b.readDouble(),
                                        b.readDouble(),
                                        b.readDouble(),
                                        b.readDouble(),
                                        b.readDouble(),
                                        b.readDouble(),
                                        b.readUnsignedByte()));

        State inObserverChart(BentStripGeometry.Point hinge, int turns) {
            if (turns == 0 && hinge.x() == x && hinge.y() == y && hinge.z() == z) return this;
            var out = ChartLabSpace.rotate(new BentStripGeometry.Point(ox, 0, oz), turns);
            return new State(pos, hinge.x(), hinge.y(), hinge.z(), out.x(), out.z(), length, treadRun, width);
        }

        @Override
        public Type<State> type() {
            return TYPE;
        }
    }

    static Direction lateral(Stair stair) {
        return stair.getBlockState()
                .getValue(BlockStateProperties.HORIZONTAL_FACING)
                .getClockWise();
    }

    static boolean neighbour(Stair stair, Direction side) {
        var state = stair.getLevel().getBlockState(stair.getBlockPos().relative(side));
        return state.is(block)
                && state.getValue(BlockStateProperties.HORIZONTAL_FACING)
                        == stair.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING);
    }

    /** Pair adjacent sills deterministically; a run of more than two becomes separate two-wide flights. */
    static int width(Stair stair) {
        var left = lateral(stair).getOpposite();
        var at = stair.getBlockPos();
        int before = 0;
        while (before < 64) {
            var state = stair.getLevel().getBlockState(at.relative(left, ++before));
            if (!state.is(block)
                    || state.getValue(BlockStateProperties.HORIZONTAL_FACING)
                            != stair.getBlockState()
                                    .getValue(BlockStateProperties.HORIZONTAL_FACING)) break;
        }
        return AirstairGeometry.pairedWidth(before - 1, neighbour(stair, lateral(stair)));
    }

    /** The published foot follows the selected tread depth and stays planted while deployed. */
    public static Vector3d foot(Stair stair, double beyond) {
        var foot =
                new Vector3d(stair.publishedOut)
                        .mul(AirstairGeometry.steps(stair.length) * stair.treadRun + beyond)
                        .add(stair.published);
        foot.y -= stair.length;
        return foot;
    }

    static Vector3d localTop(Stair stair) {
        var p = stair.getBlockPos();
        var d = stair.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING);
        var side = lateral(stair);
        double offset = stair.width == 2 ? .5 : 0;
        return new Vector3d(
                p.getX() + .5 + .5 * d.getStepX() + offset * side.getStepX(),
                p.getY() + 1,
                p.getZ() + .5 + .5 * d.getStepZ() + offset * side.getStepZ());
    }

    public static Vector3d top(Stair stair, SubLevel body) {
        return body.logicalPose().transformPosition(localTop(stair));
    }

    public static Vector3d outward(Stair stair, SubLevel body) {
        var d = stair.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING);
        var out =
                body.logicalPose()
                        .orientation()
                        .transform(new Vector3d(d.getStepX(), 0, d.getStepZ()));
        out.y = 0;
        return out.lengthSquared() < 1e-8 ? out : out.normalize();
    }

    static boolean lifting(ServerSubLevel body, Level level) {
        // Balanced hover lift is not a take-off command and must not lock the stair away.
        if (LiftServer.enabled(body)) return (HelmServer.bits(body) & HelmCommand.UP) != 0;
        if ((HelmServer.bits(body) & HelmCommand.UP) != 0
                || Thrusters.lifting(body, level.getGameTime())) return true;
        for (var actor : body.getPlot().getBlockEntityActors())
            if (actor instanceof Thrusters.Thruster thruster
                    && !thruster.isRemoved()
                    && HelmServer.power(body, thruster.getBlockPos(), thruster.getBlockState())
                            > 0) {
                var d =
                        thruster.getBlockState()
                                .getValue(BlockStateProperties.FACING)
                                .getOpposite();
                if (body.logicalPose()
                                .orientation()
                                .transform(new Vector3d(d.getStepX(), d.getStepY(), d.getStepZ()))
                                .y
                        > .5) return true;
            }
        return false;
    }

    /** Highest shape in the complete flight/headroom band. Overhangs are obstructions, never skipped. */
    static double surface(
            ServerLevel level,
            double x,
            double z,
            double top,
            java.util.Map<Long, java.util.List<AABB>> columns) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        long key = net.minecraft.world.level.ChunkPos.asLong(bx, bz);
        var boxes = columns.computeIfAbsent(key, k -> column(level, bx, bz, top));
        if (boxes == null) return Double.POSITIVE_INFINITY;
        double highest = Double.NaN;
        for (var box : boxes)
            if (x >= box.minX && x <= box.maxX && z >= box.minZ && z <= box.maxZ)
                highest = Double.isNaN(highest) ? box.maxY : Math.max(highest, box.maxY);
        return highest;
    }

    static java.util.List<AABB> column(ServerLevel level, int bx, int bz, double top) {
        var access = PlanetAccess.of(level);
        var boxes = new java.util.ArrayList<AABB>();
        for (int y = (int) Math.floor(top + 2); y >= Math.floor(top - MAX_REACH - 1); y--) {
            var at = new BlockPos(bx, y, bz);
            if (!access.inChart(at)) return null;
            var address = access.address(at);
            var owner = address.owner();
            var chunk = level.getChunkSource().getChunkNow(owner.getX() >> 4, owner.getZ() >> 4);
            if (chunk == null) return null;
            var state = address.read(chunk.getBlockState(owner));
            for (var box : state.getCollisionShape(level, owner).toAabbs())
                boxes.add(box.move(bx, y, bz));
            if (!state.getFluidState().isEmpty())
                return null; // A fluid surface is not a walkable stair landing.
        }
        return boxes;
    }

    static boolean clearance(
            ServerLevel level,
            AirstairGeometry.Flight flight,
            java.util.Map<Long, java.util.List<AABB>> columns,
            java.util.function.Consumer<String> rejected) {
        for (var tread : flight.boxes()) {
            var head =
                    new AABB(
                            tread.minX,
                            tread.maxY + .01,
                            tread.minZ,
                            tread.maxX,
                            tread.maxY + 2,
                            tread.maxZ);
            for (int x = (int) Math.floor(head.minX); x <= Math.floor(head.maxX); x++)
                for (int z = (int) Math.floor(head.minZ); z <= Math.floor(head.maxZ); z++) {
                    final int bx = x, bz = z;
                    var terrain =
                            columns.computeIfAbsent(
                                    net.minecraft.world.level.ChunkPos.asLong(x, z),
                                    k -> column(level, bx, bz, flight.hinge().y));
                    if (terrain == null) {
                        if (rejected != null)
                            rejected.accept(
                                    "headroom-column-unavailable treadRun="
                                            + flight.treadRun()
                                            + " x="
                                            + bx
                                            + " z="
                                            + bz);
                        return false;
                    }
                    for (var box : terrain)
                        if (box.intersects(head)) {
                            if (rejected != null)
                                rejected.accept(
                                        "headroom-blocked treadRun="
                                                + flight.treadRun()
                                                + " obstacle="
                                                + box
                                                + " headroom="
                                                + head);
                            return false;
                        }
                }
        }
        return true;
    }

    /** Identical terrain/headroom planner for deployment and projected touchdown instruments. */
    static AirstairGeometry.Flight plan(
            ServerLevel level, Stair stair, SubLevel body, Vector3d at, boolean gravity) {
        return plan(level, stair, body, at, gravity, null);
    }

    private static AirstairGeometry.Flight plan(
            ServerLevel level,
            Stair stair,
            SubLevel body,
            Vector3d at,
            boolean gravity,
            java.util.function.Consumer<String> rejected) {
        var columns = new java.util.HashMap<Long, java.util.List<AABB>>();
        return stair.width > 0
                ? AirstairGeometry.flight(
                        at,
                        outward(stair, body),
                        new Vector3d(0, gravity ? -1 : 0, 0),
                        stair.width,
                        (x, z) -> {
                            double h = surface(level, x, z, at.y, columns);
                            return h == Double.POSITIVE_INFINITY ? at.y + 3 : h;
                        },
                        candidate -> clearance(level, candidate, columns, rejected),
                        rejected)
                : null;
    }

    static boolean canDeployAt(ServerLevel level, ServerSubLevel body, double descent) {
        return canDeployAt(level, body, descent, 0, 0);
    }

    static boolean canDeployAt(
            ServerLevel level, ServerSubLevel body, double descent, double dx, double dz) {
        for (var holder : body.getPlot().getLoadedChunks())
            for (var actor : holder.getChunk().getBlockEntities().values())
                if (actor instanceof Stair stair && !stair.isRemoved()) {
                    stair.width = width(stair);
                    var at = top(stair, body);
                    at.add(dx, -descent, dz);
                    if (plan(level, stair, body, at, true) != null) return true;
                }
        return false;
    }

    static boolean deployed(ServerSubLevel body) {
        if (lifting(body, body.getLevel())) return false;
        for (var holder : body.getPlot().getLoadedChunks())
            for (var actor : holder.getChunk().getBlockEntities().values())
                if (actor instanceof Stair stair
                        && !stair.isRemoved()
                        && stair.width > 0
                        && stair.settled >= SETTLE_TICKS
                        && stair.length > 0) return true;
        return false;
    }

    static void tick(Level level) {
        var container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        for (var body : container.getAllSubLevels())
            if (!body.isRemoved())
                for (var holder : body.getPlot().getLoadedChunks())
                    for (var entity : holder.getChunk().getBlockEntities().values())
                        if (entity instanceof Stair stair && !stair.isRemoved())
                            tickStair(level, body, stair);
    }

    static boolean canManuallyDeploy(double clearance, double speed) {
        return Double.isFinite(clearance)
                && clearance <= LiftControl.CONTACT
                && Double.isFinite(speed)
                && speed <= EXIT_SPEED;
    }

    static boolean deployOnExit(ServerLevel level, ServerSubLevel ship) {
        if (!PlanetLevels.is(level))
            return manualResult(ship, null, "outside-planet", Double.NaN, Double.NaN);
        if (lifting(ship, level))
            return manualResult(ship, null, "lift-active", Double.NaN, Double.NaN);
        double clearance = HullLanding.ground(ship).clearance();
        if (!canManuallyDeploy(clearance, 0))
            return manualResult(ship, null, "no-ground-contact", clearance, Double.NaN);
        for (var holder : ship.getPlot().getLoadedChunks())
            for (var entity : holder.getChunk().getBlockEntities().values()) {
                if (!(entity instanceof Stair stair) || stair.isRemoved()) continue;
                stair.width = width(stair);
                if (stair.width == 0) continue;
                var at = top(stair, ship);
                if (!charted(level, at) || !gravity(level, at)) {
                    manualResult(ship, stair, "no-chart-gravity", clearance, Double.NaN);
                    continue;
                }
                double speed = sillSpeed(level, ship, stair, at);
                if (!canManuallyDeploy(clearance, speed)) {
                    manualResult(ship, stair, "sill-motion", clearance, speed);
                    continue;
                }
                java.util.function.Consumer<String> rejected =
                        Boolean.getBoolean("starfold.helmProbe")
                                ? reason ->
                                        LOG.info(
                                                "AIRSTAIR PLAN tick={} hull={} stair={} sill=({},{},{}) {}",
                                                level.getGameTime(),
                                                ship.getUniqueId(),
                                                stair.getBlockPos(),
                                                at.x,
                                                at.y,
                                                at.z,
                                                reason)
                                : null;
                var flight = plan(level, stair, ship, at, true, rejected);
                if (flight == null) {
                    manualResult(ship, stair, "no-walkable-flight", clearance, speed);
                    continue;
                }
                boolean alreadyLowered = stair.anchor != null;
                if (!alreadyLowered) lower(stair, flight);
                manualResult(
                        ship,
                        stair,
                        alreadyLowered ? "already-lowered" : "lowered",
                        clearance,
                        speed);
                return true;
            }
        return manualResult(ship, null, "no-eligible-stair", clearance, Double.NaN);
    }

    private static boolean manualResult(
            ServerSubLevel ship, Stair stair, String result, double clearance, double speed) {
        if (Boolean.getBoolean("starfold.helmProbe"))
            LOG.info(
                    "AIRSTAIR MANUAL tick={} hull={} stair={} result={} clearance={} contactLimit={} sillSpeed={} speedLimit={} settled={} drop={} treadRun={}",
                    ship.getLevel().getGameTime(),
                    ship.getUniqueId(),
                    stair == null ? null : stair.getBlockPos(),
                    result,
                    clearance,
                    LiftControl.CONTACT,
                    speed,
                    EXIT_SPEED,
                    stair == null ? 0 : stair.settled,
                    stair == null ? 0 : stair.length,
                    stair == null ? 0 : stair.treadRun);
        return false;
    }

    private static void lower(Stair stair, AirstairGeometry.Flight flight) {
        stair.anchor = new Vector3d(flight.hinge());
        stair.anchorOut = new Vector3d(flight.out());
        stair.moving = 0;
        stair.settled = SETTLE_TICKS;
        stair.length = flight.drop();
        stair.treadRun = flight.treadRun();
    }

    private static void tickStair(
            Level level, dev.ryanhcode.sable.sublevel.SubLevel body, Stair stair) {
        loaded.put(stair, true);
        if (level instanceof ServerLevel) stair.width = width(stair);
        var at = top(stair, body);
        var out = outward(stair, body);
        if (level instanceof ServerLevel server && body instanceof ServerSubLevel ship) {
            tickServerStair(server, ship, stair, at, out);
            return;
        }
        if (stair.length > 0) {
            if (clientStateExpired(stair.receivedNanos, System.nanoTime())) stair.length = 0;
            else {
                at = stair.published;
                out = stair.publishedOut;
            }
        }
        updateVisual(stair, at, out);
    }

    // TCP snapshots can arrive in bursts. Use receipt time so game-clock corrections cannot fold
    // the stairs.
    static boolean clientStateExpired(long receivedNanos, long nowNanos) {
        return nowNanos - receivedNanos > 1_000_000_000L;
    }

    private static boolean charted(ServerLevel level, Vector3d at) {
        return PlanetLevels.is(level)
                && !Space.holds(at.x, at.z)
                && PlanetAccess.of(level)
                        .inChart(BlockPos.containing(at.x, GroundCoordinates.FLOOR, at.z));
    }

    private static boolean gravity(ServerLevel level, Vector3d at) {
        return HullWeight.at(level, new BentStripGeometry.Point(at.x, at.y, at.z)) > 0
                && dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData
                                .getGravity(level)
                                .y()
                        < 0;
    }

    private static double sillSpeed(
            ServerLevel level, ServerSubLevel ship, Stair stair, Vector3d at) {
        double poseSpeed =
                stair.previous == null
                        ? Double.POSITIVE_INFINITY
                        : at.distance(stair.previous) * 20;
        var pipeline = SubLevelContainer.getContainer(level).physicsSystem().getPipeline();
        var bounds = ship.getPlot().getBoundingBox();
        double radius =
                .5
                        * Math.sqrt(
                                Math.pow(bounds.maxX() - bounds.minX() + 1, 2)
                                        + Math.pow(bounds.maxY() - bounds.minY() + 1, 2)
                                        + Math.pow(bounds.maxZ() - bounds.minZ() + 1, 2));
        double linear = pipeline.getLinearVelocity(ship, new Vector3d()).length(),
                angular = pipeline.getAngularVelocity(ship, new Vector3d()).length() * radius;
        return Math.max(poseSpeed, linear + angular);
    }

    private static void tickServerStair(
            ServerLevel server, ServerSubLevel ship, Stair stair, Vector3d at, Vector3d out) {
        boolean chart = charted(server, at);
        double speed = sillSpeed(server, ship, stair, at);
        stair.previous = new Vector3d(at);
        boolean gravity = chart && gravity(server, at);
        boolean lift = lifting(ship, server);
        var flight =
                gravity && !lift && speed <= ENTER_SPEED && stair.anchor == null
                        ? plan(server, stair, ship, at, true)
                        : null;
        double reach = flight == null ? Double.NaN : flight.drop();
        String why = null;
        if (stair.anchor != null) {
            // Lowered: the flight keeps its planned place and length; only
            // leaving it folds it.
            stair.moving =
                    held(
                            stair.moving,
                            chart,
                            gravity,
                            lift,
                            speed,
                            at.distance(stair.anchor),
                            out.dot(stair.anchorOut));
            if (stair.moving < 0) {
                why =
                        !chart
                                ? "chart"
                                : !gravity
                                        ? "gravity"
                                        : lift
                                                ? "lift"
                                                : at.distance(stair.anchor) > HOLD_DISTANCE
                                                        ? "drift"
                                                        : !(out.dot(stair.anchorOut) >= HOLD_TURN)
                                                                ? "turn"
                                                                : "motion";
                stair.anchor = null;
                stair.anchorOut = null;
                stair.moving = 0;
                stair.settled = 0;
                stair.length = 0;
            }
        } else {
            stair.settled = settled(stair.settled, chart, gravity, lift, speed, reach);
            if (stair.settled >= SETTLE_TICKS) {
                lower(stair, flight);
                why = "lowered";
            } else stair.length = 0;
        }
        if (why != null && stair.width > 0 && Boolean.getBoolean("starfold.helmProbe"))
            LOG.info(
                    "AIRSTAIR TRANSITION tick={} hull={} stair={} result={} sillSpeed={} drop={} treadRun={}",
                    server.getGameTime(),
                    ship.getUniqueId(),
                    stair.getBlockPos(),
                    why,
                    speed,
                    stair.length,
                    stair.treadRun);
        if (why != null && stair.width > 0)
            LOG.debug(
                    "{} {} speed {} drop {} tread run {}",
                    why,
                    stair.getBlockPos(),
                    speed,
                    reach,
                    stair.treadRun);
        if (stair.anchor != null) {
            at = stair.anchor;
            out = stair.anchorOut;
        }
        stair.published.set(at);
        stair.publishedOut.set(out);
        var packet =
                new State(
                        stair.getBlockPos(),
                        at.x,
                        at.y,
                        at.z,
                        out.x,
                        out.z,
                        stair.length,
                        stair.treadRun,
                        stair.width);
        for (var player : server.players())
            if (ship.getTrackingPlayers().contains(player.getUUID())
                    && player.connection.hasChannel(State.TYPE))
                PacketDistributor.sendToPlayer(player, packet);
        updateVisual(stair, at, out);
    }

    private static void updateVisual(Stair stair, Vector3d at, Vector3d out) {
        stair.oldVisual = stair.visual;
        stair.visual = Math.max(0, Math.min(1, stair.visual + (stair.length > 0 ? 1d / 16 : -.5)));
        stair.boxes =
                stair.length > 0 && stair.width > 0
                        ? new AirstairGeometry.Flight(
                                        at, out, stair.width, stair.length, stair.treadRun)
                                .boxes()
                        : java.util.List.of();
    }

    /** EntityGetter supplies these to vanilla move/step-up, noCollision and sneak-edge checks on both sides.
     * All non-spectator entities participate, including mobs and item entities. No climb flags/fall immunity. */
    public static java.util.List<VoxelShape> collisions(
            Level level, Entity entity, AABB query, java.util.List<VoxelShape> original) {
        if (entity != null && entity.isSpectator()) return original;
        java.util.List<Stair> entries;
        synchronized (loaded) {
            entries = java.util.List.copyOf(loaded.keySet());
        }
        java.util.ArrayList<VoxelShape> result = null;
        for (var stair : entries) {
            if (stair.isRemoved() || stair.getLevel() != level || stair.length <= 0) continue;
            var body = Sable.HELPER.getContaining(level, stair.getBlockPos());
            if (body == null
                    || body.isRemoved()
                    || body instanceof ServerSubLevel ship && lifting(ship, level)) continue;
            for (var box : stair.boxes)
                if (box.intersects(query)) {
                    if (result == null) result = new java.util.ArrayList<>(original);
                    result.add(Shapes.create(box));
                }
        }
        return result == null ? original : result;
    }

    private Airstair() {}
}
