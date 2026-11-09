package dev.starfold.planet;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.*;

import dev.starfold.Sources;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.GroundGuidance;
import dev.starfold.chart.GroundOrbit;
import dev.starfold.chart.GroundSolar;
import dev.starfold.chart.GroundSpace;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.geometry.CubeSurfaceTopology.*;
import dev.starfold.geometry.PlanetMotion;
import dev.starfold.geometry.PlanetSpace;
import dev.starfold.ground.GroundFlightPackets;
import dev.starfold.ground.GroundNavigationSave;
import dev.starfold.render.GroundChunkSnapshot;
import dev.starfold.render.GroundTransition;
import dev.starfold.render.GroundViewCache;
import dev.starfold.render.GroundSurface;
import dev.starfold.render.GroundViewPackets;
import dev.starfold.render.PlanetRemotePackets;
import dev.starfold.render.PlanetRemoteView;
import dev.starfold.sky.GroundClock;
import dev.starfold.sky.GroundEphemerisSave;
import dev.starfold.universe.PlanetChunkGenerator;
import dev.starfold.universe.PlanetPackets;
import dev.starfold.universe.PlanetWorldPreset;
import dev.starfold.universe.SystemChunkGenerator;
import dev.starfold.worldgen.PlanetBiomeFixtures;
import dev.starfold.worldgen.PlanetGeneration;
import dev.starfold.worldgen.PlanetHeightCache;
import dev.starfold.worldgen.PlanetTerrain;

import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.*;

import org.junit.jupiter.api.*;

import java.util.*;

/** Generation foundation only: no claim of live cross-dimension flight or GPU acceptance. */
public class PlanetSystemTest {
    final PlanetBiomeFixtures fixture = new PlanetBiomeFixtures();
    static final long ROCK_SEED = 20261002L;
    static final PlanetGeneration POLICY = new PlanetGeneration("rocky",PlanetGeneration.SURFACE_VERSION,dev.starfold.worldgen.PlanetBiomeFixtures.recipe("rocky"),2048,191,"core_shell",true,false,false,dev.starfold.worldgen.PlanetEnvironment.defaults(false,2048));

    @BeforeAll
    static void boot() {
        PlanetBiomeFixtures.boot();
    }

    PlanetChunkGenerator rock() {
        return new PlanetChunkGenerator(fixture.generator().settings(),ROCK_SEED,dev.starfold.worldgen.PlanetTerrain.VERSION,Optional.empty(),Optional.of(POLICY));
    }

    net.minecraft.world.level.chunk.ProtoChunk chunk(PlanetChunkGenerator g, int x, int z) {
        var c =
                new net.minecraft.world.level.chunk.ProtoChunk(
                        new net.minecraft.world.level.ChunkPos(x, z),
                        net.minecraft.world.level.chunk.UpgradeData.EMPTY,
                        PlanetBiomeFixtures.HEIGHT,
                        PlanetBiomeFixtures.palette,
                        null);
        c.fillBiomesFromNoise(g.getBiomeSource(), null);
        c.setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.BIOMES);
        dev.starfold.worldgen.SystemGeneration.fill(g, c).join();
        return c;
    }

    @Test
    void allSixRealRockChunksAgreeWithColumnsAndDryHeightmaps() {
        var g = rock();
        for (var f : Face.values()) {
            var c = chunk(g, f.ordinal() * 256 + 64, 64);
            for (int x = 0; x < 16; x += 3)
                for (int z = 0; z < 16; z += 3) {
                    int wx = c.getPos().getMinBlockX() + x, wz = c.getPos().getMinBlockZ() + z;
                    var col = g.getBaseColumn(wx, wz, PlanetBiomeFixtures.HEIGHT, null);
                    int ground = g.terrain().groundY(f, 1024 + x, 1024 + z);
                    for (int y = -64; y < 320; y++)
                        assertSame(col.getBlock(y), c.getBlockState(new BlockPos(wx, y, wz)));
                    for (var type :
                            List.of(
                                    Heightmap.Types.WORLD_SURFACE_WG,
                                    Heightmap.Types.OCEAN_FLOOR_WG)) {
                        assertEquals(ground, c.getHeight(type, x, z));
                        assertEquals(
                                ground + 1,
                                g.getBaseHeight(wx, wz, type, PlanetBiomeFixtures.HEIGHT, null));
                    }
                    assertEquals(ground, g.terrain().surfaceY(f, 1024 + x, 1024 + z));
                    assertTrue(g.terrain().surfaceState(f, 1024 + x, 1024 + z).is(Blocks.STONE));
                }
        }
        assertEquals(-64, g.getSeaLevel());
    }

    @Test
    void rockSharedEdgesCornersAndCanonicalHaloRemainOneAlgorithm() {
        var t = rock().terrain();
        var topology = new CubeSurfaceTopology(2048);
        for (var f : Face.values()) {
            for (int[] e :
                    new int[][] {
                        {0, 1024}, {2048, 1024}, {1024, 0}, {1024, 2048}, {0, 0}, {2048, 2048}
                    }) {
                var p =
                        f.normal
                                .scale(1024)
                                .add(f.u.scale(e[0] - 1024))
                                .add(f.v.scale(e[1] - 1024));
                for (var g : Face.values())
                    if (g != f && p.dot(g.normal) == 1024)
                        assertEquals(
                                t.elevation(f, e[0], e[1]),
                                t.elevation(g, 1024 + p.dot(g.u), 1024 + p.dot(g.v)),
                                1e-10);
            }
            for (int[] p : new int[][] {{-1, 1024}, {2048, 1024}, {1024, -1}, {1024, 2048}}) {
                var c = topology.canonical(new Cell(f, p[0], 0, p[1])).cell();
                for (int y = -64; y < 180; y++)
                    assertSame(t.state(f, p[0], y, p[1]), t.state(c.face(), c.u(), y, c.v()));
            }
        }
    }

    @Test
    void actualRockBaselineDetectsEditsAndLaterStagesNeverOverwriteThem() {
        var g = rock();
        var c = chunk(g, 64, 64);
        assertTrue(
                g.terrain()
                        .matches(GroundChunkSnapshot.fromStates(0, 64, 64, 1, fixture.values(c))));
        var pos = new BlockPos(1024, 50, 1024);
        c.setBlockState(pos, Blocks.CHEST.defaultBlockState(), false);
        g.buildSurface(null, null, null, c);
        assertTrue(c.getBlockState(pos).is(Blocks.CHEST));
        assertFalse(
                g.terrain()
                        .matches(GroundChunkSnapshot.fromStates(0, 64, 64, 2, fixture.values(c))));
        assertTrue(Arrays.stream(chunk(g, 128, 64).getSections()).allMatch(s -> s.hasOnlyAir()));
    }

    public static final GroundDynamics.Terrain OPEN =
            new GroundDynamics.Terrain() {
                public boolean clear(PlanetShape.Pose p) {
                    return p.point().y() > 100;
                }

                public double clearance(PlanetShape.Pose p) {
                    return p.point().y() - 50;
                }
            };

    public org.joml.Quaterniond aim(Point direction) {
        return new org.joml.Quaterniond()
                .rotationTo(
                        new org.joml.Vector3d(0, 0, 1),
                        new org.joml.Vector3d(direction.x(), direction.y(), direction.z()))
                .normalize();
    }

    @Test
    void explicitSurfaceSlotsRoundTripWithoutAPhantomCompanion() {
        var old = GroundSolar.defaults();
        assertEquals(1, old.count());
        assertFalse(GroundEphemerisSave.modelTag(old).contains("multiple"));
        assertEquals(old, GroundEphemerisSave.model(GroundEphemerisSave.modelTag(old)));
        var dual = PlanetSystem.model();
        assertEquals(2, dual.count());
        assertEquals(dual, GroundEphemerisSave.model(GroundEphemerisSave.modelTag(dual)));
        for (long tick : new long[] {0, 12, 900, 12000}) {
            assertEquals(old.at(0, tick), dual.at(0, tick));
            assertThrows(IllegalArgumentException.class, () -> old.at(1, tick));
        }
        assertEquals(2, GroundEphemeris.spinning(dual, true, 100).count());
    }

    @Test
    void bothPlanetReferenceConversionsPreserveExactMomentumAndRay() {
        var m = GroundEphemeris.spinning(PlanetSystem.model(), true, 0);
        for (int id = 0; id < 2; id++) {
            var local =
                    GroundDynamics.local(
                            900,
                            id,
                            new PlanetShape.Pose(Face.TOP, new Point(800, 6000, 900)),
                            new Point(.3, .4, .1),
                            new org.joml.Quaterniond().rotationXYZ(.1, .2, .3),
                            true,
                            new GroundGuidance.Options(id, false, false));
            var space = GroundDynamics.detach(m, local);
            var returned = GroundDynamics.attach(m, space, id);
            assertEquals(id, returned.planet());
            assertEquals(
                    0,
                    GroundDynamics.position(m, local, 900)
                            .sub(GroundDynamics.position(m, returned, 900))
                            .length(),
                    1e-8);
            assertEquals(
                    0,
                    GroundDynamics.velocity(m, local, 900)
                            .sub(GroundDynamics.velocity(m, returned, 900))
                            .length(),
                    1e-8);
            assertEquals(
                    1,
                    Math.abs(
                            GroundDynamics.orientation(m, local, 900)
                                    .dot(GroundDynamics.orientation(m, returned, 900))),
                    1e-10);
        }
    }

    @Test
    void navigationPinsPlanetAndTargetWithoutChangingOldAbsentFields() {
        var m = PlanetSystem.model();
        var local =
                GroundDynamics.local(
                        900,
                        1,
                        new PlanetShape.Pose(Face.TOP, new Point(1024, 180, 1024)),
                        new Point(.1, .2, .3),
                        new org.joml.Quaterniond(),
                        true,
                        new GroundGuidance.Options(1, true, true));
        assertEquals(local, GroundNavigationSave.decode(GroundNavigationSave.encode(local)));
        var old =
                GroundDynamics.local(
                        900,
                        local.local(),
                        new Point(.1, .2, .3),
                        new org.joml.Quaterniond(),
                        true,
                        GroundDynamics.OFF);
        var tag = GroundNavigationSave.encode(old);
        assertFalse(tag.contains("planet"));
        assertFalse(tag.getCompound("guidance").contains("target"));
        assertEquals(old, GroundNavigationSave.decode(tag));
        var space = GroundDynamics.detach(m, local);
        var saved = GroundNavigationSave.decode(GroundNavigationSave.encode(space));
        var resumed = GroundDynamics.resume(m, OPEN, saved, 1000);
        assertEquals(space.position(), resumed.position());
        assertEquals(space.velocity(), resumed.velocity());
        assertEquals(space.orientation(), resumed.orientation());
        assertEquals(1, resumed.planet());
        assertThrows(
                IllegalArgumentException.class,
                () -> GroundDynamics.resume(GroundSolar.defaults(), OPEN, local, 900));
        tag.putString("planet", "1");
        assertThrows(IllegalArgumentException.class, () -> GroundNavigationSave.decode(tag));
    }

    GroundDynamics.State flyTo(GroundEphemeris.Model m, GroundDynamics.State state, int target) {
        for (int i = 0; i < 2500; i++) {
            if (!state.space() && state.planet() == target && state.local().point().y() < 230)
                return state;
            var world = GroundDynamics.position(m, state, state.tick());
            var direction = PlanetSpace.unit(m.at(target, state.tick()).center().sub(world));
            var q = aim(direction);
            var input = new PlanetMotion.Input(i + 1, q.x, q.y, q.z, q.w, 1, 0, 0, true, true);
            state = GroundDynamics.step(m, OPEN, state, input, false);
            if (i % 71 == 0)
                state =
                        GroundDynamics.resume(
                                m,
                                OPEN,
                                GroundNavigationSave.decode(GroundNavigationSave.encode(state)),
                                state.tick());
        }
        throw new AssertionError("Failed flight to " + target);
    }

    @Test
    void actualNativeKernelFliesAToBToAAndReloadsAlongTheRoute() throws Exception {
        var m = PlanetSystem.model();
        var a = m.at(0, 0);
        var toward = PlanetSpace.unit(m.at(1, 0).center().sub(a.center()));
        var pose = GroundTopology.locate(a.inverseVector(toward.scale(GroundOrbit.RADIUS + 4100)));
        var state =
                GroundDynamics.detach(
                        m,
                        GroundDynamics.local(
                                0,
                                0,
                                pose,
                                GroundEphemeris.ZERO,
                                new org.joml.Quaterniond(),
                                true,
                                GroundDynamics.OFF));
        var b = flyTo(m, state, 1);
        assertEquals(1, b.planet());
        assertFalse(b.space());
        var returned = flyTo(m, b, 0);
        assertEquals(0, returned.planet());
        assertFalse(returned.space());
        var out = java.nio.file.Path.of("build/native-binary");
        java.nio.file.Files.createDirectories(out);
        java.nio.file.Files.writeString(
                out.resolve("reproduction.json"),
                "{\"gpu_verified\":false,\"native_kernel_roundtrip\":true,\"b_arrival_tick\":"
                        + b.tick()
                        + ",\"a_return_tick\":"
                        + returned.tick()
                        + ",\"old_worlds_upgraded\":false}\n");
    }

    @Test
    void newPresetPinsBothGeneratorsButNeverRegistersAGlobalRockDimension() throws Exception {
        var root = java.nio.file.Path.of("src/main/resources/data/starfold");
        var a =
                new PlanetChunkGenerator(fixture.generator().settings(),20261001L,dev.starfold.worldgen.PlanetTerrain.VERSION,Optional.of(fixture.source(20261001L)),Optional.of(PlanetGeneration.defaults(dev.starfold.worldgen.PlanetTerrain.VERSION)));
        var b =
                new PlanetChunkGenerator(fixture.generator().settings(),ROCK_SEED,dev.starfold.worldgen.PlanetTerrain.VERSION,Optional.empty(),Optional.of(POLICY));
        assertEquals(dev.starfold.worldgen.PlanetTerrain.VERSION, a.version());
        assertEquals(dev.starfold.worldgen.PlanetTerrain.VERSION, b.version());
        assertNotEquals(a.seed(), b.seed());
        assertFalse(java.nio.file.Files.exists(root.resolve("dimension/native_rock.json")));
        var type =
                PlanetBiomeFixtures.lookup
                        .lookupOrThrow(net.minecraft.core.registries.Registries.DIMENSION_TYPE)
                        .getOrThrow(
                                net.minecraft.world.level.dimension.BuiltinDimensionTypes
                                        .OVERWORLD);
        var ak =
                net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.LEVEL_STEM,
                        PlanetLevels.PLANET.location());
        var bk =
                net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.LEVEL_STEM,
                        net.minecraft.resources.ResourceLocation.parse("starfold:native_rock"));
        var old = new net.minecraft.world.level.dimension.LevelStem(type, fixture.generator());
        var system =
                new dev.starfold.universe.SystemChunkGenerator(
                        PlanetStorage.LAYOUT, java.util.List.of(a, b));
        assertEquals(2, system.count());
        var as = new net.minecraft.world.level.dimension.LevelStem(type, system);
        var r =
                new MappedRegistry<net.minecraft.world.level.dimension.LevelStem>(
                        net.minecraft.core.registries.Registries.LEVEL_STEM, Lifecycle.stable());
        var over = new net.minecraft.world.level.dimension.LevelStem(type, fixture.generator());
        Registry.register(r, net.minecraft.world.level.dimension.LevelStem.OVERWORLD, over);
        Registry.register(r, ak, old);
        var original = r.freeze();
        var dims =
                new WorldDimensions(
                        Map.of(
                                net.minecraft.world.level.dimension.LevelStem.OVERWORLD,
                                over,
                                ak,
                                as));
        var baked = dims.bake(PlanetWorldPreset.registryFor(dims, original)).dimensions();
        assertSame(as, baked.get(ak));
        assertNull(baked.get(bk));
        assertFalse(original.containsKey(bk));
    }

    @Test
    void bothPlanetSetupWireAndSharedClockSurviveReload() {
        var m = PlanetSystem.model();
        var saved = new GroundClock(new GroundEphemerisSave(m, 900));
        var copy = GroundClock.load(saved.save(new net.minecraft.nbt.CompoundTag(), null), null);
        assertEquals(2, copy.clock.model.count());
        assertEquals(900, copy.clock.tick());
        var local =
                GroundDynamics.local(
                        900,
                        1,
                        new PlanetShape.Pose(Face.TOP, new Point(1024, GroundDynamics.HANDOFF + 1, 1024)),
                        GroundEphemeris.ZERO,
                        new org.joml.Quaterniond(),
                        true,
                        new GroundGuidance.Options(1, false, false));
        var packet = new GroundFlightPackets.Setup(7, m, GroundSpace.DEFAULT_MASK, local, true);
        var raw = io.netty.buffer.Unpooled.buffer();
        try {
            var buf = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
            GroundFlightPackets.Setup.CODEC.encode(buf, packet);
            assertEquals(packet, GroundFlightPackets.Setup.CODEC.decode(buf));
            assertEquals(0, buf.readableBytes());
        } finally {
            raw.release();
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GroundFlightPackets.Setup(
                                7, GroundSolar.defaults(), GroundSpace.DEFAULT_MASK, local, true));
    }

    GroundSurface surface(long revision) {
        int[] heights = new int[256], states = new int[256];
        Arrays.fill(heights, 250);
        Arrays.fill(
                states, Block.BLOCK_STATE_REGISTRY.getId(Blocks.GOLD_BLOCK.defaultBlockState()));
        return new GroundSurface(revision, heights, states);
    }

    @Test
    void remoteCacheIsSessionAndPlanetIsolatedAndInvalidatesUnknownEdits() {
        var view = new PlanetRemoteView();
        var profile = dev.starfold.render.SurfaceProfiles.current(new PlanetPackets.Profile(7,ROCK_SEED,dev.starfold.worldgen.PlanetTerrain.VERSION,POLICY));
        view.summary(new PlanetRemotePackets.Summary(1, profile, 0, new long[] {1}, new long[0]));
        assertTrue(
                view.surface(
                        new PlanetRemotePackets.Surface(
                                1, new GroundViewPackets.Surface(7, 0, surface(1)))));
        assertEquals(1, view.size());
        assertFalse(
                view.surface(
                        new PlanetRemotePackets.Surface(
                                0, new GroundViewPackets.Surface(7, 0, surface(2)))));
        assertFalse(
                view.surface(
                        new PlanetRemotePackets.Surface(
                                1, new GroundViewPackets.Surface(8, 0, surface(2)))));
        view.summary(
                new PlanetRemotePackets.Summary(1, profile, 1, new long[] {1}, new long[] {1}));
        assertEquals(0, view.size());
        assertFalse(
                view.surface(
                        new PlanetRemotePackets.Surface(
                                1, new GroundViewPackets.Surface(7, 0, surface(2)))));
        view.summary(
                new PlanetRemotePackets.Summary(
                        0, dev.starfold.render.SurfaceProfiles.current(new PlanetPackets.Profile(8,1,dev.starfold.worldgen.PlanetTerrain.VERSION)), 0, new long[0], new long[0]));
        assertEquals(0, view.snapshot().planet());
        assertEquals(8, view.session());
    }

    @Test
    void remoteWireDefensivelyCopiesCoverageAndCarriesOnlySurfaceSummaries() {
        var changed = new long[] {1};
        var p =
                new PlanetRemotePackets.Summary(
                        1,
                        new PlanetPackets.Profile(7,ROCK_SEED,dev.starfold.worldgen.PlanetTerrain.VERSION,POLICY),
                        1,
                        changed,
                        new long[0]);
        changed[0] = 0;
        assertEquals(1, p.changed()[0]);
        var raw = io.netty.buffer.Unpooled.buffer();
        try {
            var buf = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
            PlanetRemotePackets.Summary.CODEC.encode(buf, p);
            var copy = PlanetRemotePackets.Summary.CODEC.decode(buf);
            assertEquals(p.profile(), copy.profile());
            assertArrayEquals(p.changed(), copy.changed());
            var s =
                    new PlanetRemotePackets.Surface(
                            1, new GroundViewPackets.Surface(7, 0, surface(1)));
            PlanetRemotePackets.Surface.CODEC.encode(buf, s);
            var decoded = PlanetRemotePackets.Surface.CODEC.decode(buf);
            assertEquals(s.planet(), decoded.planet());
            assertEquals(s.surface().session(), decoded.surface().session());
            assertEquals(s.surface().key(), decoded.surface().key());
            assertEquals(s.surface().image().revision(), decoded.surface().image().revision());
            assertArrayEquals(s.surface().image().heights(), decoded.surface().image().heights());
            assertArrayEquals(s.surface().image().states(), decoded.surface().image().states());
            assertEquals(0, buf.readableBytes());
        } finally {
            raw.release();
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlanetRemotePackets.Summary(2, p.profile(), 1, new long[0], new long[0]));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PlanetRemotePackets.Summary(
                                1, p.profile(), 1, new long[0], new long[] {1}));
    }

    @Test
    void rockCoverNeverInventsASeaPlaneOverDryValleys() {
        var t = rock().terrain();
        var cache = new PlanetHeightCache(t);
        assertEquals(-10, cache.surfaceHeight(Face.TOP, 512, 512, -10));
        var local=fixture.terrain.liquid(Face.TOP,512,512);
        assertEquals(local==null?-10:Math.max(-10,local.elevation()),new PlanetHeightCache(fixture.terrain).surfaceHeight(Face.TOP,512,512,-10));
        var function = new dev.starfold.worldgen.surface.SurfaceFunction(t);
        boolean found = false;
        for (int u = 64; u < 2048; u += 128)
            for (int v = 64; v < 2048; v += 128)
                if (t.elevation(Face.TOP, u, v) < -1) {
                    assertTrue(GroundTransition.generated(cache, Face.TOP, u, v) < 0);
                    var column = dev.starfold.worldgen.lod.VoxelColumn.visible(function, Face.TOP, u, v);
                    assertTrue(column.runs().stream().allMatch(r -> r.block().getFluidState().isEmpty()));
                    assertTrue(column.runs().getLast().top() < 128);
                    found = true;
                }
        assertTrue(found);
    }

    /** The declared sea is exactly what generated columns contain, so derived views never invent or miss one. */
    @Test
    void declaredSeaMatchesTheFluidInGeneratedColumns() {
        for (var t :
                new PlanetTerrain[] {
                    fixture.terrain,
                    new PlanetTerrain(PlanetTerrain.TEST_SEED,dev.starfold.worldgen.PlanetTerrain.VERSION),
                    new PlanetTerrain(PlanetTerrain.TEST_SEED,dev.starfold.worldgen.PlanetTerrain.VERSION),
                    rock().terrain()
                }) {
            var sea = t.sea();
            var cache = new PlanetHeightCache(t);
            boolean wet = false;
            for (var f : Face.values())
                for (int u = 40; u < 2048; u += 251)
                    for (int v = 40; v < 2048; v += 251) {
                        int ground = t.groundY(f, u, v);
                        for (int y = ground + 1; y < 140; y++) {
                            var state = t.state(f, u, y, v);
                            var local=t.liquid(f,u,v);
                            boolean covered = local != null && y < local.level();
                            final int level=y;
                            var direction=t.unit(f,u+.5,v+.5);
                            boolean filled=t.terrainPlan().localVolumes(f,u,v).stream().anyMatch(volume->
                                volume.operation()==dev.starfold.worldgen.plan.terrain.TerrainPlan.Operation.FILL
                                && t.terrainPlan().contains(volume,direction,level));
                            if(filled)assertEquals(new dev.starfold.worldgen.surface.SurfaceFunction(t).blocks(f,u,v)[y-t.floor()],state);
                            else assertEquals(covered, !state.getFluidState().isEmpty());
                            if (!state.getFluidState().isEmpty()) {
                                assertNotNull(local);
                                assertEquals(local.fluid(), state);
                                wet = true;
                            }
                        }
                        int top = cache.surfaceY(f, u, v);
                        assertEquals(t.surfaceY(f, u, v), top);
                        assertEquals(t.state(f, u, top, v), cache.surfaceState(f, u, v));
                        assertTrue(t.state(f, u, top + 1, v).isAir(),f+" "+u+","+v+" top="+top+" next="+t.state(f,u,top+1,v)+" volumes="+t.terrainPlan().localVolumes(f,u,v));
                    }
            assertEquals(sea != null, wet);
        }
        assertNull(rock().terrain().sea());
        assertEquals(0, fixture.terrain.sea().elevation());
    }

    @Test
    void liveSystemHooksRouteOneClockAndPreserveStateDuringChartHandoff() throws Exception {
        var flight = Sources.text("GroundFlightServer");
        var clock = Sources.text("GroundClock");
        var wiring = Sources.text("StarfoldServer");
        assertTrue(
                wiring.contains(
                        "NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Pre cpEvent)"));
        assertTrue(wiring.contains("if(a!=null)GroundClock.get(a).advance()"));
        assertFalse(wiring.contains("saved.advance()"));
        assertTrue(flight.contains("s.state.tick()==clock.clock.tick()"));
        assertTrue(clock.contains("var storage=level.getDataStorage()"));
        assertTrue(
                flight.contains(
                        "player.connection.teleport(feet.x(),feet.y(),feet.z(),yaw,pitch)"));
        assertFalse(flight.contains("s.state=GroundDynamics.local(clock.clock.tick(),pose"));
        assertTrue(
                Sources.text("GroundNavigationSave")
                        .contains("var storage=level.getDataStorage()"));
        assertTrue(
                Sources.text("GroundFlightClient").contains("predicted.planet()!=state.planet()"));
    }

    @Test
    void profileRoundTripCarriesCustomPolicyNotJustVersion() {
        var raw = io.netty.buffer.Unpooled.buffer();
        try {
            var buf = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
            var profile = new PlanetPackets.Profile(7,ROCK_SEED,dev.starfold.worldgen.PlanetTerrain.VERSION,POLICY);
            PlanetPackets.Profile.CODEC.encode(buf, profile);
            var copy = PlanetPackets.Profile.CODEC.decode(buf);
            assertEquals(profile, copy);
            assertEquals(0, buf.readableBytes());
            assertEquals(
                    POLICY,
                    new PlanetTerrain(copy.seed(), copy.version(), copy.generation()).generation());
            assertEquals("starfold:native_planet_v17_profile", profile.type().id().toString());
        } finally {
            raw.release();
        }
    }
}
