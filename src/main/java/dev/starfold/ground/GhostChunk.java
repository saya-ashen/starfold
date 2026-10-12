package dev.starfold.ground;

import dev.starfold.geometry.ChartOrientation;
import dev.starfold.mixin.BlockEntityAccess;
import dev.starfold.mixin.GroundPistonAccess;
import dev.starfold.planet.Anomaly;
import dev.starfold.planet.PlanetAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.level.material.FluidState;

/** One halo chunk of a face chart, seen on the server: each column reads and writes its canonical owner
 * column on the neighbouring face, turned by the seam's quarter turns (see {@link ChunkViews}).
 * Its own sections stay empty and it is never registered with the chunk map, ticked or saved. */
public final class GhostChunk extends LevelChunk {
    private static final BlockState SOLID = Blocks.BEDROCK.defaultBlockState();
    private final ServerLevel level;
    private final ChunkViews.Columns columns;
    private final ChunkSkyLightSources sky;

    GhostChunk(ServerLevel level, ChunkPos pos, ChunkViews.Columns columns) {
        super(level, pos);
        this.level = level;
        this.columns = columns;
        this.sky =
                new ChunkSkyLightSources(level) {
                    @Override
                    public int getLowestSourceY(int x, int z) {
                        int i = columns.index(x, z);
                        var owner = owner(i);
                        return owner == null
                                ? level.getMinBuildHeight()
                                : owner.getSkyLightSources()
                                        .getLowestSourceY(columns.x()[i] & 15, columns.z()[i] & 15);
                    }

                    @Override
                    public int getHighestLowestSourceY() {
                        int max = Integer.MIN_VALUE;
                        for (int i = 0; i < 256; i++)
                            max = Math.max(max, getLowestSourceY(i & 15, i >> 4));
                        return max;
                    }

                    @Override
                    public void fillFrom(ChunkAccess chunk) {}

                    @Override
                    public boolean update(
                            net.minecraft.world.level.BlockGetter getter, int x, int y, int z) {
                        return false;
                    }
                };
    }

    public ChunkViews.Columns columns() {
        return columns;
    }

    private ChunkAccess owner(int i) {
        return columns.present(i)
                ? ChunkViews.read(level, columns.x()[i] >> 4, columns.z()[i] >> 4)
                : null;
    }

    private LevelChunk writable(int i) {
        return columns.present(i)
                ? ChunkViews.write(level, columns.x()[i] >> 4, columns.z()[i] >> 4)
                : null;
    }

    private BlockPos at(int i, int y) {
        return new BlockPos(columns.x()[i], y, columns.z()[i]);
    }

    private int turns(int i) {
        return columns.turns()[i];
    }

    /** The solid corner closure is virtual everywhere on the server; the view honours it like Level reads do. */
    private boolean masked(int i, int y) {
        var mask = dev.starfold.sky.GroundClock.get(level).mask;
        var owner = at(i, y);
        var access = PlanetAccess.of(level);
        var shape = access.shape(owner);
        int u = columns.u()[i], v = columns.v()[i], last = shape.size() - 1;
        if (Math.min(u, last - u) >= mask.core() || Math.min(v, last - v) >= mask.core())
            return false;
        return Anomaly.mask(shape, mask, access.cell(owner));
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int i = columns.index(pos.getX(), pos.getZ());
        var owner = owner(i);
        if (owner == null || masked(i, pos.getY())) return SOLID;
        return ChartOrientation.state(owner.getBlockState(at(i, pos.getY())), -turns(i));
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public FluidState getFluidState(int x, int y, int z) {
        return getBlockState(new BlockPos(x, y, z)).getFluidState();
    }

    @Override
    public BlockState setBlockState(BlockPos pos, BlockState state, boolean moved) {
        int i = columns.index(pos.getX(), pos.getZ());
        var owner = writable(i);
        if (owner == null || masked(i, pos.getY())) return null;
        var old =
                owner.setBlockState(
                        at(i, pos.getY()), ChartOrientation.state(state, turns(i)), moved);
        return old == null ? null : ChartOrientation.state(old, -turns(i));
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return getBlockEntity(pos, EntityCreationType.CHECK);
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos, EntityCreationType creation) {
        int i = columns.index(pos.getX(), pos.getZ());
        var owner = owner(i);
        if (owner == null) return null;
        return owner instanceof LevelChunk chunk
                ? chunk.getBlockEntity(at(i, pos.getY()), creation)
                : owner.getBlockEntity(at(i, pos.getY()));
    }

    /** A block entity created at a halo position moves (same instance) to its owner; a moving piston's direction and payload turn with it. */
    private BlockEntity transport(BlockEntity be, int i) {
        int turns = turns(i);
        ((BlockEntityAccess) be).starfold$ownerPosition(at(i, be.getBlockPos().getY()));
        be.setBlockState(ChartOrientation.state(be.getBlockState(), turns));
        if (be instanceof PistonMovingBlockEntity piston) {
            var access = (GroundPistonAccess) piston;
            access.starfold$movedState(ChartOrientation.state(access.starfold$movedState(), turns));
            access.starfold$direction(
                    ChartOrientation.direction(access.starfold$direction(), turns));
        }
        return be;
    }

    @Override
    public void addAndRegisterBlockEntity(BlockEntity be) {
        int i = columns.index(be.getBlockPos().getX(), be.getBlockPos().getZ());
        var owner = writable(i);
        if (owner != null) owner.addAndRegisterBlockEntity(transport(be, i));
    }

    @Override
    public void setBlockEntity(BlockEntity be) {
        int i = columns.index(be.getBlockPos().getX(), be.getBlockPos().getZ());
        var owner = writable(i);
        if (owner != null) owner.setBlockEntity(transport(be, i));
    }

    @Override
    public void removeBlockEntity(BlockPos pos) {
        int i = columns.index(pos.getX(), pos.getZ());
        var owner = writable(i);
        if (owner != null) owner.removeBlockEntity(at(i, pos.getY()));
    }

    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        int i = columns.index(x, z);
        var owner = owner(i);
        return owner == null
                ? level.getMinBuildHeight()
                : owner.getHeight(type, columns.x()[i] & 15, columns.z()[i] & 15);
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z) {
        int i = columns.index((x << 2) + 2, (z << 2) + 2);
        var owner = owner(i);
        return owner == null
                ? super.getNoiseBiome(x, y, z)
                : owner.getNoiseBiome(columns.x()[i] >> 2, y, columns.z()[i] >> 2);
    }

    @Override
    public ChunkSkyLightSources getSkyLightSources() {
        return sky;
    }
}
