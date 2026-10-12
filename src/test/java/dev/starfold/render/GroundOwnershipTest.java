package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.worldgen.PlanetHeightCache;
import dev.starfold.worldgen.PlanetTerrain;

import net.minecraft.world.level.block.*;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

/** Registry/mathematical tests only: no client, window, GPU or saved world. */
class GroundOwnershipTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    static GroundViewCache.Surface surface(long revision, int h, Block block) {
        int[] heights = new int[256], states = new int[256];
        Arrays.fill(heights, h);
        Arrays.fill(states, Block.getId(block.defaultBlockState()));
        return new GroundViewCache.Surface(revision, heights, states);
    }

    static GroundOwnership.Snapshot snapshot(
            long key, GroundViewCache.Surface s, boolean nativeOwner) {
        var bits = new BitSet();
        if (nativeOwner) bits.set((int) key);
        return new GroundOwnership.Snapshot(Map.of(key, s), bits);
    }

    static boolean commit(GroundOwnership.Snapshot target, GroundOwnership.Snapshot available) {
        return GroundOwnership.canCommit(false, false, 3, 3, 0, 0, target, available);
    }

    static PlanetHeightCache heights() {
        return new PlanetHeightCache(new PlanetTerrain(PlanetTerrain.TEST_SEED));
    }

    @Test
    void nativeArrivingAfterDetailBatchCannotUseDetailOwnership() throws Exception {
        long key = 12;
        var s = surface(0, 147, Blocks.GRASS_BLOCK);
        var requested = snapshot(key, s, false);
        var arrived = snapshot(key, s, true);
        var selected = new BitSet();
        selected.set((int) key);
        assertTrue(GroundSelection.detail(key, selected, new BitSet()));
        assertTrue(selected.get((int) key)); // old one-bit GPU test also drew native
        assertFalse(commit(requested, arrived));
        assertEquals(0, requested.retainCompatible(arrived).code(key));
        assertEquals(127, requested.code(key));
        assertFalse(requested.code(key) / 255f > .75);
        assertTrue(commit(arrived, arrived));
        assertEquals(255, arrived.code(key));
        var unknown = new BitSet();
        unknown.set(13);
        var parent = GroundCoverSnapshot.parent(null, unknown);
        var live = surface(1, 147, Blocks.GRASS_BLOCK);
        assertEquals(20, live.height(0, 0) + 1 - 128);
        double actual = parent.height(0, 208, 0, 208, 0);
        assertEquals(0, actual);
        var bridge = GroundTransition.bridge(208, 0, 0, 1, 20, actual, actual);
        assertFalse(bridge.isEmpty());
        assertEquals(0, bridge.get(2).y());
        assertEquals(20, bridge.get(0).y());
        var out = Path.of("build/native-ownership");
        Files.createDirectories(out);
        Files.writeString(
                out.resolve("reproduction.json"),
                "{\"old_native_and_detail\":2,\"new_owner_count\":1,\"old_unknown_neighbor_bridge_skipped\":true,\"old_physical_gap\":20,\"new_endpoint_error\":0,\"exact_screenshot_reproduced\":false,\"gpu_verified\":false}\n");
    }

    @Test
    void everyOwnerHasExactlyOneSurfaceDraw() {
        var s = surface(0, 127, Blocks.GRASS_BLOCK);
        for (var state :
                List.of(GroundOwnership.empty(), snapshot(12, s, false), snapshot(12, s, true))) {
            float code = state.code(12) / 255f;
            int draws =
                    (code < .1 ? 1 : 0)
                            + (state.owner(12) == GroundOwnership.Owner.DETAIL ? 1 : 0)
                            + (code > .75 ? 1 : 0);
            assertEquals(1, draws);
        }
    }

    @Test
    void immutableSnapshotsDoNotFollowMutableNativeFlagsOrMaps() {
        var nativeTiles = new BitSet();
        var map = new HashMap<Long, GroundViewCache.Surface>();
        map.put(12L, surface(0, 147, Blocks.GRASS_BLOCK));
        var s = new GroundOwnership.Snapshot(map, nativeTiles);
        nativeTiles.set(12);
        map.clear();
        assertEquals(127, s.code(12));
        var selected = s.selected();
        selected.clear();
        assertTrue(s.selected().get(12));
    }

    @Test
    void sameHeightAndFlagsStillRejectChangedMeshVersion() {
        var a = snapshot(12, surface(1, 147, Blocks.GRASS_BLOCK), false);
        var b = snapshot(12, surface(2, 147, Blocks.GRASS_BLOCK), false);
        assertFalse(commit(a, b));
        assertEquals(a.code(12), b.code(12));
    }

    @Test
    void sameHeightAndVersionStillRejectChangedNativeMaterial() {
        var a = snapshot(12, surface(0, 147, Blocks.GRASS_BLOCK), true);
        var b = snapshot(12, surface(0, 147, Blocks.STONE), true);
        assertFalse(commit(a, b));
        assertEquals(a.code(12), b.code(12));
    }

    @Test
    void removingOneFineTileActivatesItsPrebuiltBorderWithoutHidingValidNeighbours() {
        var s = surface(1, 147, Blocks.GRASS_BLOCK);
        var old = new GroundOwnership.Snapshot(Map.of(12L, s, 13L, s), new BitSet());
        var available = snapshot(12, s, false);
        assertFalse(commit(old, available));
        var retained = old.retainCompatible(available);
        assertTrue(commit(retained, available));
        assertEquals(127, retained.code(12));
        assertEquals(0, retained.code(13));
        assertFalse(old.code(12) > 0 && old.code(13) == 0);
        assertTrue(retained.code(12) > 0 && retained.code(13) == 0);
        assertEquals(127, old.code(13));
    }

    @Test
    void extraAvailableMeshesDoNotStarveAnOtherwiseValidBatch() {
        var s = surface(1, 147, Blocks.GRASS_BLOCK);
        var available = new GroundOwnership.Snapshot(Map.of(12L, s, 13L, s), new BitSet());
        assertTrue(commit(snapshot(12, s, false), available));
        assertFalse(snapshot(12, s, false).sameAs(available));
    }

    @Test
    void readyFlagsCannotPublishAgainstAnotherResidentCoverVersion() {
        var s = snapshot(12, surface(1, 147, Blocks.GRASS_BLOCK), false);
        assertFalse(GroundOwnership.canCommit(false, true, 3, 4, 5, 5, s, s));
        assertTrue(GroundOwnership.canCommit(false, false, 3, 3, 5, 6, s, s));
    }

    @Test
    void stagedCoversAndStripsMustBecomeReadyTogether() {
        var s = snapshot(12, surface(1, 147, Blocks.GRASS_BLOCK), false);
        assertFalse(GroundOwnership.canCommit(true, false, 3, 3, 5, 5, s, s));
        assertFalse(GroundOwnership.canCommit(true, true, 3, 3, 5, 6, s, s));
        assertTrue(GroundOwnership.canCommit(true, true, 3, 3, 5, 5, s, s));
    }

    @Test
    void aNativeOwnerWithoutItsBakedSurfaceIsNotPublishable() {
        var bits = new BitSet();
        bits.set(12);
        assertThrows(
                IllegalArgumentException.class, () -> new GroundOwnership.Snapshot(Map.of(), bits));
        assertThrows(
                IllegalArgumentException.class,
                () -> snapshot(98304, surface(1, 127, Blocks.GRASS_BLOCK), false));
    }

    @Test
    void residentCoverUsesBakedSurfaceNotNewestCachedSummary() {
        var map = new HashMap<Long, GroundViewCache.Surface>();
        map.put(12L, surface(1, 147, Blocks.GRASS_BLOCK));
        var old = new GroundCoverSnapshot(heights(), map, new BitSet());
        map.put(12L, surface(2, 167, Blocks.STONE));
        assertEquals(20, old.height(0, 192, 0, 192, 0));
        assertEquals(
                40,
                new GroundCoverSnapshot(heights(), map, new BitSet()).height(0, 192, 0, 192, 0));
    }

    @Test
    void anUnknownParentStandsAtItsGeneratedHeightAndFlatOnlyWithoutTerrain() {
        var cache = heights();
        var unknown = new BitSet();
        unknown.set(12);
        double generated =
                GroundTransition.generated(cache, CubeSurfaceTopology.Face.values()[0], 192, 0);
        assertEquals(
                generated, GroundCoverSnapshot.parent(cache, unknown).height(0, 192, 0, 192, 0));
        assertEquals(0, GroundCoverSnapshot.parent(null, unknown).height(0, 192, 0, 192, 0));
        unknown.clear();
        assertEquals(
                generated, GroundCoverSnapshot.parent(cache, unknown).height(0, 192, 0, 192, 0));
    }

    @Test
    void capturedPatchHaloUsesCanonicalNeighbourColumnOnAllTwentyFourSeams() {
        var cache = heights();
        int[] h = new int[256], states = new int[256];
        for (int i = 0; i < 256; i++) {
            h[i] = 127 + i % 32;
            states[i] = Block.getId(Blocks.GRASS_BLOCK.defaultBlockState());
        }
        var s = new GroundViewCache.Surface(1, h, states);
        for (var f : CubeSurfaceTopology.Face.values())
            for (var p : new int[][] {{-1, 512}, {2048, 512}, {512, -1}, {512, 2048}}) {
                var c =
                        GroundExteriorMesh.TOPOLOGY
                                .canonical(new CubeSurfaceTopology.Cell(f, p[0], 0, p[1]))
                                .cell();
                long key = GroundOwnership.key(f.ordinal(), p[0], p[1]);
                var changed = new BitSet();
                changed.set((int) key);
                int x0 = p[0] < 0 ? 0 : p[0] >= 2048 ? 1792 : 512,
                        z0 = p[1] < 0 ? 0 : p[1] >= 2048 ? 1792 : 512;
                var snapshot =
                        GroundCoverSnapshot.patch(
                                cache, changed, f.ordinal(), x0, z0, k -> k == key ? s : null);
                changed.clear();
                assertTrue(snapshot.column(key));
                assertEquals(
                        s.height(c.u() & 15, c.v() & 15) + 1 - 128,
                        snapshot.height(f.ordinal(), p[0], p[1], p[0], p[1]));
            }
    }

    @Test
    void threeStateAtlasIncludesEveryFaceEdgeAndCornerHalo() {
        var s = surface(0, 147, Blocks.GRASS_BLOCK);
        var map = new HashMap<Long, GroundViewCache.Surface>();
        var natives = new BitSet();
        for (int f = 0; f < 6; f++)
            for (int i = 0; i < 128; i++)
                for (var p : new int[][] {{0, i}, {127, i}, {i, 0}, {i, 127}}) {
                    long k = p[0] + 128L * (p[1] + 128L * f);
                    map.put(k, s);
                    if ((k & 1) == 0) natives.set((int) k);
                }
        var snapshot = new GroundOwnership.Snapshot(map, natives);
        var atlas = GroundOwnership.atlas(snapshot);
        assertEquals(130 * 780, atlas.length);
        for (int f = 0; f < 6; f++)
            for (int z = -1; z <= 128; z++)
                for (int x = -1; x <= 128; x++)
                    assertEquals(
                            snapshot.code(GroundOwnership.key(f, x * 16 + 8, z * 16 + 8)),
                            Byte.toUnsignedInt(atlas[x + 1 + 130 * (z + 1 + 130 * f)]));
    }

    @Test
    void runtimeUsesBakedMeshImagesAndCommitsCoverOnlyWithItsStrips() throws Exception {
        var source = Sources.text("GroundRenderer");
        var draw =
                source.substring(
                        source.indexOf("private static void draw("),
                        source.indexOf("private static boolean drawDetail"));
        assertFalse(draw.contains("GroundExterior.commit()"));
        assertTrue(source.contains("if(pendingJob.nextCover)commitCovers()"));
        assertTrue(source.contains("columns.put(e.getKey(),e.getValue().surface)"));
        var strips =
                source.substring(
                        source.indexOf("private static final class StitchJob"),
                        source.indexOf("private static final class ParentJob"));
        assertFalse(strips.contains("GroundViewClient.surface"));
        assertFalse(strips.contains("level.getBlockState"));
        assertTrue(strips.contains("covers[GroundViewPolicy.patch(neighbour)]"));
        assertFalse(strips.contains("selection.owner(neighbour)=="));
        assertTrue(source.contains("publishOwnership(ownership.retainCompatible(resident))"));
        assertTrue(source.contains("commitDetails(retained)"));
        assertTrue(source.contains("GL11.GL_UNPACK_ALIGNMENT,alignment"));
        assertTrue(source.contains("GL11.GL_UNPACK_ROW_LENGTH,0"));
        assertTrue(source.contains("GL11.GL_UNPACK_ROW_LENGTH,rowLength"));
        assertTrue(source.contains("GL11.GL_UNPACK_SKIP_PIXELS,skipPixels"));
    }

    @Test
    void shaderSeparatesNativeOwnersPreservesBlockLightAndBiasesLookupCoordinates() {
        String f =
                GroundShaderSource.patch(
                        "rendertype_solid",
                        false,
                        "#version 150\nuniform sampler2D Sampler0;in vec2 texCoord0;out vec4 fragColor;void main(){fragColor=texture(Sampler0,texCoord0);}");
        assertFalse(f.contains("CPNativeDerived<0.5&&owner<0.75)discard"));
        assertTrue(f.contains("owner>0.1&&!lod)discard"));
        assertTrue(f.contains("bool deep=dither<far,lod=far>0.0&&dither<far+0.125;"));
        assertTrue(f.contains("if(CPNativeDerived<0.5&&deep)discard;"));
        assertTrue(f.contains("if(owner<0.1||deep)discard;"));
        assertTrue(f.contains("int(CPNativeFace)*130"));
        assertTrue(
                f.contains(
                        "ivec2 neighbour=cpNativeNeighbour(tile);if(cpNativeOwner(neighbour)>0.1)discard"));
        assertTrue(f.contains("border&&cpNativeOwner(neighbour)>0.1"));
        assertTrue(GroundShaderSource.VERTEX.contains("cpNativeSourcePoint.x+=0.02"));
        assertTrue(
                GroundShaderSource.VERTEX.contains(
                        "cpNativeSolarPosition=cpNativeSolarNormal*(CPNativeOrbitLimits.y+point.y)"));
        assertTrue(f.contains("(CPNativeDerived>0.5||CPNativeTerrain>0.5)"));
        String v =
                GroundShaderSource.patch(
                        "rendertype_solid",
                        true,
                        "#version 150\nin vec3 Position;in vec3 Normal;in vec4 Color;in ivec2 UV2;uniform sampler2D Sampler2;uniform mat4 ProjMat,ModelViewMat;out vec4 vertexColor;vec4 minecraft_sample_lightmap(sampler2D s,ivec2 uv){return vec4(1);}void main(){vertexColor=Color*minecraft_sample_lightmap(Sampler2,UV2);gl_Position=ProjMat*ModelViewMat*vec4(Position,1);}");
        assertTrue(v.contains("max(vec3(0.0001),minecraft_sample_lightmap(Sampler2,UV2).rgb)"));
        assertTrue(v.contains("if(CPNativeDerived>0.5||CPNativeTerrain>0.5)"));
        assertTrue(
                f.contains(
                        "max(vec3((0.12+0.88*daylight)*cpNativeSkyVisibility),cpNativeBlockLight)"));
        assertTrue(
                GroundShaderSource.TEXTURE.contains(
                        "CPNativeStitch>0.5)return vec4(vec3(1.0),texture(source,uv).a)"));
    }
}
