package dev.starfold.devtools;

import static dev.starfold.devtools.FarRawCapture.*;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.render.GroundClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FlowingFluid;

/** Minecraft adapter. Invoke on the client thread, once for the selected center tile. */
public final class FarRawWorld {
    private FarRawWorld() {}

    /** Face block coordinates are normalized to the 16-block tile origin by the pure sampler. */
    public static Report capture(int planet, int face, int tileX, int tileZ) {
        if (planet < 0) throw new IllegalArgumentException("planet must be nonnegative");
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread())
            throw new IllegalStateException("raw capture requires client thread");
        return FarRawCapture.capture(face, tileX, tileZ, new LoadedSource(planet, client.level));
    }

    private static final class LoadedSource implements Source {
        final int planet;
        final ClientLevel level;

        LoadedSource(int planet, ClientLevel level) {
            this.planet = planet;
            this.level = level;
        }

        public int minY() {
            return level == null ? 0 : level.getMinBuildHeight();
        }

        public int maxYExclusive() {
            return level == null ? 0 : level.getMaxBuildHeight();
        }

        public MappedColumn map(int face, int u, int v) {
            // Without a world there is no coherent client plane to map into.
            if (level == null) return null;
            Point point =
                    GroundClient.planePoint(
                            planet, Face.values()[face], new Point(u + .5, 0, v + .5));
            if (point == null
                    || !Double.isFinite(point.x())
                    || !Double.isFinite(point.z())
                    || point.x() < Integer.MIN_VALUE
                    || point.x() >= Integer.MAX_VALUE
                    || point.z() < Integer.MIN_VALUE
                    || point.z() >= Integer.MAX_VALUE) return null;
            return new MappedColumn((int) Math.floor(point.x()), (int) Math.floor(point.z()));
        }

        private LevelChunk chunk(int x, int z) {
            return level == null ? null : level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        }

        public Surface surface(int x, int z) {
            LevelChunk chunk = chunk(x, z);
            return chunk == null
                    ? new Surface(Status.UNLOADED, null, "client chunk absent")
                    : new Surface(
                            Status.LOADED,
                            chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15),
                            "loaded chunk WORLD_SURFACE");
        }

        public Block block(Position p) {
            LevelChunk chunk = chunk(p.x(), p.z());
            if (chunk == null)
                return Block.unavailable(Status.UNLOADED, "client chunk absent at read");
            var state = chunk.getBlockState(new BlockPos(p.x(), p.y(), p.z()));
            var fluid = state.getFluidState();
            Fluid raw = null;
            if (!fluid.isEmpty()) {
                var type = fluid.getType();
                var family = type instanceof FlowingFluid flowing ? flowing.getSource() : type;
                raw =
                        new Fluid(
                                BuiltInRegistries.FLUID.getKey(type).toString(),
                                BuiltInRegistries.FLUID.getKey(family).toString(),
                                fluid.isSource(),
                                !fluid.isSource(),
                                fluid.getAmount(),
                                fluid.hasProperty(FlowingFluid.FALLING)
                                        ? fluid.getValue(FlowingFluid.FALLING)
                                        : null,
                                fluid.getOwnHeight());
            }
            return new Block(
                    Status.LOADED, state.toString(), state.isAir(), raw, "loaded chunk state");
        }

        public boolean sameFluid(Fluid fluid, Fluid above) {
            return BuiltInRegistries.FLUID
                    .get(ResourceLocation.parse(fluid.type()))
                    .isSame(BuiltInRegistries.FLUID.get(ResourceLocation.parse(above.type())));
        }

        public Light light(Position p) {
            if (chunk(p.x(), p.z()) == null)
                return new Light(Status.UNLOADED, null, null, "client chunk absent at light read");
            var engine = level.getChunkSource().getLightEngine();
            BlockPos at = new BlockPos(p.x(), p.y(), p.z());
            // Layer listeners read retained light storage and do not request chunks or update
            // light.
            return new Light(
                    Status.LOADED,
                    engine.getLayerListener(LightLayer.SKY).getLightValue(at),
                    engine.getLayerListener(LightLayer.BLOCK).getLightValue(at),
                    LIGHT_MEANING);
        }
    }
}
