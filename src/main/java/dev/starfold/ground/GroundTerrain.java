package dev.starfold.ground;

import dev.starfold.chart.GroundSpace;
import dev.starfold.flight.GroundDynamics;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.planet.Anomaly;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetShape;
import dev.starfold.planet.PlanetSystem;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.*;

/** Native voxel shapes, including state-dependent stairs/slabs/fences; no material proxies. */
public final class GroundTerrain implements GroundDynamics.Terrain {
    private final Level level;
    private final Entity entity;
    private final GroundSpace.Config mask;
    private final BlockGetter view;
    private final PlanetAccess access;
    private final int planet;

    public GroundTerrain(Level level, Entity entity, GroundSpace.Config mask) {
        this(
                level,
                entity,
                mask,
                entity == null ? PlanetSystem.planet(level) : PlanetSystem.planet(entity));
    }

    public GroundTerrain(Level level, Entity entity, GroundSpace.Config mask, int planet) {
        if (!GroundServer.is(level) || mask == null)
            throw new IllegalArgumentException("Native collision world");
        PlanetShape.valid(planet);
        this.level = level;
        this.entity = entity;
        this.mask = mask;
        this.planet = planet;
        access = PlanetAccess.of(level);
        view = view(level, access, mask);
    }

    /** Testable real-block adapter, also used by live collision and shape neighbor queries. */
    public static BlockGetter view(
            BlockGetter source, PlanetAccess access, GroundSpace.Config mask) {
        return view(
                source,
                access,
                mask,
                owner -> {
                    if (!(source instanceof Level l)) return true;
                    if (l.isClientSide)
                        return l.getChunkSource().hasChunk(owner.getX() >> 4, owner.getZ() >> 4);
                    return access != PlanetAccess.SHARED
                            || !(l instanceof net.minecraft.server.level.ServerLevel server)
                            || server.getChunkSource()
                                            .getChunkNow(owner.getX() >> 4, owner.getZ() >> 4)
                                    != null;
                },
                owner ->
                        source instanceof Level l && l.isClientSide
                                ? GroundSeams.clientCollisionState(l, owner)
                                : null);
    }

    /** Test seam for native residency and complete synchronized block data. Null remains UNKNOWN. */
    public static BlockGetter view(
            BlockGetter source,
            PlanetAccess access,
            GroundSpace.Config mask,
            java.util.function.Predicate<BlockPos> resident,
            java.util.function.Function<BlockPos, BlockState> synchronizedState) {
        return new BlockGetter() {
            @Override
            public BlockState getBlockState(BlockPos query) {
                if (!access.inChart(query)) return source.getBlockState(query);
                var a = access.address(query);
                var c = access.cell(a.owner());
                if (Anomaly.mask(access.shape(a.owner()), mask, c))
                    return Blocks.BEDROCK.defaultBlockState();
                // Other-face owners normally live outside vanilla's client chunk window.
                // Their full server snapshot is valid collision data; LOD/generated data is not.
                if (!resident.test(a.owner())) {
                    var state = synchronizedState.apply(a.owner());
                    return state == null ? Blocks.BEDROCK.defaultBlockState() : a.read(state);
                }
                return a.read(source.getBlockState(a.owner()));
            }

            @Override
            public FluidState getFluidState(BlockPos query) {
                return getBlockState(query).getFluidState();
            }

            @Override
            public BlockEntity getBlockEntity(BlockPos query) {
                return source.getBlockEntity(
                        access.inChart(query) ? access.address(query).owner() : query);
            }

            @Override
            public int getHeight() {
                return source.getHeight();
            }

            @Override
            public int getMinBuildHeight() {
                return source.getMinBuildHeight();
            }
        };
    }

    private CollisionContext context() {
        return entity == null ? CollisionContext.empty() : CollisionContext.of(entity);
    }

    public BlockGetter view() {
        return view;
    }

    public static boolean overlaps(BlockGetter view, CollisionContext context, AABB body) {
        var small = body.deflate(1e-7);
        var pos = new BlockPos.MutableBlockPos();
        // One-cell halo covers extended fence/wall collision shapes without replacing them by
        // cubes.
        for (int x = (int) Math.floor(small.minX) - 1; x <= Math.floor(small.maxX) + 1; x++)
            for (int z = (int) Math.floor(small.minZ) - 1; z <= Math.floor(small.maxZ) + 1; z++)
                for (int y = (int) Math.floor(small.minY) - 1;
                        y <= Math.floor(small.maxY) + 1;
                        y++) {
                    pos.set(x, y, z);
                    var state = view.getBlockState(pos);
                    if (state.isAir()) continue;
                    var shape = state.getCollisionShape(view, pos, context);
                    for (var box : shape.toAabbs())
                        if (box.move(pos).intersects(small)) return true;
                }
        return false;
    }

    /** Client: the world is the observer's own coordinates; canonical poses are relabelled into it. */
    private Point clientFeet(GroundTopology.Pose pose) {
        return GroundSeams.clientFeet(planet, pose);
    }

    @Override
    public boolean clear(GroundTopology.Pose pose) {
        if (level.isClientSide) {
            // High above any planet (including the other one) nothing can collide, drawn in this
            // chart or not.
            if (access.feet(planet, pose).y()
                    >= Math.max(
                            (long) level.getMaxBuildHeight() + 2,
                            (long) GroundCoordinates.FLOOR + mask.height())) return true;
            var at = clientFeet(pose);
            if (at == null) return false;
            double height = entity == null ? 1.8 : entity.getBbHeight(),
                    width = entity == null ? .6 : entity.getBbWidth();
            var box =
                    new AABB(
                            at.x() - width / 2,
                            at.y(),
                            at.z() - width / 2,
                            at.x() + width / 2,
                            at.y() + height,
                            at.z() + width / 2);
            for (int x = (int) Math.floor(box.minX) >> 4; x <= (int) Math.floor(box.maxX) >> 4; x++)
                for (int z = (int) Math.floor(box.minZ) >> 4;
                        z <= (int) Math.floor(box.maxZ) >> 4;
                        z++) if (!level.getChunkSource().hasChunk(x, z)) return false;
            return !overlaps(level, context(), box);
        }
        var p = access.feet(planet, pose);
        double height = entity == null ? 1.8 : entity.getBbHeight(),
                width = entity == null ? .6 : entity.getBbWidth();
        if (p.y()
                >= Math.max(
                        (long) level.getMaxBuildHeight() + 2,
                        (long) GroundCoordinates.FLOOR + mask.height())) return true;
        return !overlaps(
                view,
                context(),
                new AABB(
                        p.x() - width / 2,
                        p.y(),
                        p.z() - width / 2,
                        p.x() + width / 2,
                        p.y() + height,
                        p.z() + width / 2));
    }

    @Override
    public double clearance(GroundTopology.Pose pose) {
        if (level.isClientSide) {
            var chart = access.feet(planet, pose);
            double limit =
                    Math.max(
                            level.getMaxBuildHeight() + 2, GroundCoordinates.FLOOR + mask.height());
            if (chart.y() > limit + 512) return chart.y() - limit;
            var at = clientFeet(pose);
            if (at == null) return chart.y() - limit;
            double highest = level.getMinBuildHeight();
            var cell = new BlockPos.MutableBlockPos();
            for (int x = (int) Math.floor(at.x() - .3); x <= Math.floor(at.x() + .3); x++)
                for (int z = (int) Math.floor(at.z() - .3); z <= Math.floor(at.z() + .3); z++) {
                    if (GroundSeams.clientMasked(level, cell.set(x, GroundCoordinates.FLOOR, z)))
                        highest = Math.max(highest, GroundCoordinates.FLOOR + mask.height());
                    if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) {
                        highest = Math.max(highest, limit);
                        continue;
                    }
                    for (int y =
                                    Math.min(
                                            level.getMaxBuildHeight() - 1,
                                            level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)
                                                    - 1);
                            y >= level.getMinBuildHeight();
                            y--) {
                        cell.set(x, y, z);
                        var shape =
                                level.getBlockState(cell).getCollisionShape(level, cell, context());
                        if (!shape.isEmpty()) {
                            highest =
                                    Math.max(
                                            highest,
                                            y + shape.max(net.minecraft.core.Direction.Axis.Y));
                            break;
                        }
                    }
                }
            return at.y() - highest;
        }
        var feet = access.feet(planet, pose);
        double ceiling =
                Math.max(level.getMaxBuildHeight() + 2, GroundCoordinates.FLOOR + mask.height());
        if (feet.y() > ceiling + 512) return feet.y() - ceiling;
        double top = level.getMinBuildHeight();
        var pos = new BlockPos.MutableBlockPos();
        for (int x = (int) Math.floor(feet.x() - .3); x <= Math.floor(feet.x() + .3); x++)
            for (int z = (int) Math.floor(feet.z() - .3); z <= Math.floor(feet.z() + .3); z++) {
                pos.set(x, GroundCoordinates.FLOOR, z);
                var canonical = access.address(pos).owner();
                var cell = access.cell(canonical);
                if (Anomaly.column(access.shape(canonical), mask, cell))
                    top = Math.max(top, GroundCoordinates.FLOOR + mask.height());
                boolean missing =
                        level.isClientSide
                                ? !level.getChunkSource()
                                        .hasChunk(canonical.getX() >> 4, canonical.getZ() >> 4)
                                : access == PlanetAccess.SHARED
                                        && level
                                                instanceof
                                                net.minecraft.server.level.ServerLevel server
                                        && server.getChunkSource()
                                                        .getChunkNow(
                                                                canonical.getX() >> 4,
                                                                canonical.getZ() >> 4)
                                                == null;
                if (missing
                        && (!level.isClientSide
                                || GroundSeams.clientCollisionState(level, canonical) == null)) {
                    top = Math.max(top, ceiling);
                    continue;
                }
                // A nonresident snapshot has no vanilla heightmap; scan its real column instead.
                int start =
                        missing
                                ? level.getMaxBuildHeight() - 1
                                : Math.min(
                                        level.getMaxBuildHeight() - 1,
                                        level.getHeight(
                                                        Heightmap.Types.MOTION_BLOCKING,
                                                        canonical.getX(),
                                                        canonical.getZ())
                                                - 1);
                for (int y = start; y >= level.getMinBuildHeight(); y--) {
                    pos.set(x, y, z);
                    var shape = view.getBlockState(pos).getCollisionShape(view, pos, context());
                    if (!shape.isEmpty()) {
                        top = Math.max(top, y + shape.max(net.minecraft.core.Direction.Axis.Y));
                        break;
                    }
                }
            }
        return feet.y() - top;
    }

    private GroundTerrain() {
        throw new AssertionError();
    }
}
