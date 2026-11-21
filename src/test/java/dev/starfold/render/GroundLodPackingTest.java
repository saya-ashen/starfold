package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.lod.VoxelColumn;
import dev.starfold.worldgen.lod.VoxelMesher;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

class GroundLodPackingTest {
    @BeforeAll
    static void registry() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void workerOutputMatchesOriginalVertexEncodingForSolidWaterLeavesAndPlants() {
        for (var block : List.of(Blocks.STONE, Blocks.WATER, Blocks.OAK_LEAVES, Blocks.SHORT_GRASS)) {
            var state = block.defaultBlockState();
            var built = GroundQuadtreeMesher.build(Face.TOP, 32, 64, 16, 2, 118,
                    (x, z) -> new VoxelColumn(List.of(new VoxelColumn.Run(120, 130, state))));
            var decoded = GroundLodPacking.prepare(built, () -> true);
            assertFalse(decoded.quads().isEmpty(), block.toString());
            var quads = new ArrayList<GroundLodPacking.Quad>();
            var appearances = new GroundLodPacking.Appearance[decoded.quads().size()];
            int[] lights = new int[built.vertexCount()];
            for (int q = 0; q < decoded.quads().size(); q++) {
                var quad = decoded.quads().get(q);
                var m = quad.material();
                int rgb = new int[] {-1, -2, -3, 0x447799}[q % 4];
                var material = new GroundQuadtreeMesher.Material(m.block(), m.axis(), m.volume(), rgb,
                        m.normalX(), m.normalY(), m.normalZ(), m.bottomY(), m.topY(),
                        m.lowerHeightOffset(), m.upperHeightOffset());
                quads.add(new GroundLodPacking.Quad(material, quad.points(), quad.lights(), quad.appearancePosition()));
                int layer = q % 4;
                appearances[q] = new GroundLodPacking.Appearance(.25f,
                        .75f + 2 * dev.starfold.api.SurfaceClass.WATER, 0xabcd1234,
                        0x89abcd, 0x567890, 0x6789ab, layer, block == Blocks.WATER);
                for (int corner = 0; corner < 4; corner++) lights[q * 4 + corner] = 0xf000 | corner * 16;
            }
            assertEquals(Map.of(state, built.vertexCount()), decoded.verticesByState());
            var geometry = new GroundLodPacking.Geometry(built, List.copyOf(quads), decoded.verticesByState());
            var ids = new LinkedHashMap<GroundCompact.Material, Integer>();
            var expected = reference(geometry, appearances, lights, ids);
            var owner = new AtomicLong();
            var caller = Thread.currentThread();
            try (var packed = CompletableFuture.supplyAsync(() ->
                    GroundLodPacking.pack(geometry, appearances, lights, () -> true, owner)).join()) {
                assertEquals(built.bytes() + packed.materials.size() * 32L, owner.get());
                assertTrue(packed.remap(new GroundUploadBudget(Long.MAX_VALUE), material -> {
                    assertSame(caller, Thread.currentThread(), "global palette registration stays on caller");
                    return Objects.requireNonNull(ids.get(material), "material must match original resolver");
                }));
                assertEquals(expected.keySet(), packed.layers.keySet());
                expected.forEach((layer, bytes) -> assertEquals(bytes, packed.layers.get(layer),
                        block + " layer " + layer));
            }
            assertEquals(0, owner.get());
        }
    }

    /** Previous per-vertex formula, independent of the moved packing implementation. */
    private static Map<Integer, ByteBuffer> reference(GroundLodPacking.Geometry geometry,
            GroundLodPacking.Appearance[] appearances, int[] lights,
            Map<GroundCompact.Material, Integer> ids) {
        var result = new TreeMap<Integer, ByteBuffer>();
        var source = geometry.built().vertices();
        for (int q = 0; q < appearances.length; q++) {
            var quad = geometry.quads().get(q);
            var m = quad.material();
            var a = appearances[q];
            int color = a.color(), albedo = a.albedo();
            boolean average = m.rgb() >= 0 || m.rgb() == -2;
            if (average) {
                if (m.rgb() >= 0) albedo = GroundAlbedo.calibrate(m.rgb(),
                        dev.starfold.worldgen.surface.SurfaceColor.color(m.block()), a.calibrationAlbedo());
                color = albedo;
            }
            var direction = Direction.getNearest((float) m.normalX(), (float) m.normalY(), (float) m.normalZ());
            float shade = VoxelMesher.plant(m.block()) ? 1 : switch (direction) {
                case UP -> 1; case DOWN -> .5f; case NORTH, SOUTH -> .8f; case EAST, WEST -> .6f;
            };
            color = shade(color, shade);
            albedo = shade(albedo, shade);
            int normal = 0;
            for (int channel = 0; channel < 3; channel++)
                normal |= ((byte) (((albedo >> (16 - 8 * channel) & 255) / 255f * 2 - 1) * 127f) & 255)
                        << (8 * channel);
            int tint = color >> 16 & 255 | (color >> 8 & 255) << 8 | (color & 255) << 16;
            int tag = VoxelMesher.plant(m.block()) ? 234 : Math.abs(m.normalY()) > .0001 ? 250
                    : m.normalX() < -.5 ? 240 : m.normalX() > .5 ? 241 : m.normalZ() < 0 ? 242 : 243;
            var out = result.computeIfAbsent(a.layer(), k -> ByteBuffer.allocate((int) geometry.built().bytes())
                    .order(ByteOrder.nativeOrder()));
            for (int corner = 0; corner < 4; corner++) {
                double y = quad.points().get(corner).y();
                float offset = a.translucent() ? waterOffset(y, a.v())
                        : (float) (y - Math.round(y * 16) / 16.);
                var material = new GroundCompact.Material(average ? -1 : a.u(), a.v(), a.bounds(), tint,
                        normal, a.translucent() ? .7f : -1, offset);
                int id = ids.computeIfAbsent(material, k -> 0x123400 + ids.size());
                int off = q * 48 + corner * 12;
                out.putShort(source.getShort(off)).putShort(source.getShort(off + 2))
                        .putShort(source.getShort(off + 4)).putShort((short) id)
                        .putShort((short) (tag | (id >>> 16) << 8)).putShort((short) lights[q * 4 + corner]);
            }
        }
        result.values().forEach(ByteBuffer::flip);
        return result;
    }

    private static int shade(int rgb, float shade) {
        return (int) ((rgb >> 16 & 255) * shade) << 16 | (int) ((rgb >> 8 & 255) * shade) << 8
                | (int) ((rgb & 255) * shade);
    }

    private static float waterOffset(double y, float v) {
        if ((int) Math.floor(v / 2) != dev.starfold.api.SurfaceClass.WATER) return 0;
        double base = Math.floor(y), fraction = y - base, ninth = Math.rint(fraction * 9);
        double exact = ninth > 0 && ninth < 9 && Math.abs(fraction - ninth / 9) < .0001
                ? base + ninth / 9 - .001 : y;
        return (float) (exact - (Math.round((y + 2048) * 16) / 16.0 - 2048));
    }

    @Test
    void remapResumesWithoutDoubleRegisteringOrTouchingPositionTagAndLight() {
        var data = ByteBuffer.allocate(1024 * 12).order(ByteOrder.nativeOrder());
        for (int v = 0; v < 1024; v++) data.putShort((short) v).putShort((short) 32768)
                .putShort((short) 22).putShort((short) (v % 2)).putShort((short) 234).putShort((short) 0xf0a0);
        data.flip();
        var materials = List.of(new GroundCompact.Material(.1f, .2f, 3, 4, 5, -1, 0),
                new GroundCompact.Material(.2f, .3f, 4, 5, 6, -1, 0));
        var owner = new AtomicLong(data.remaining() + 64L);
        var clock = new AtomicLong();
        var registrations = new AtomicInteger();
        try (var packed = new GroundLodPacking.Result(Map.of(0, data), materials, owner, owner.get())) {
            assertFalse(packed.remap(new GroundUploadBudget(100, 0, clock::get), material -> {
                clock.set(100);
                return 0xabc000 + registrations.getAndIncrement();
            }));
            assertThrows(IllegalStateException.class, packed::requiredMaterials);
            assertEquals(1, registrations.get());
            assertEquals(0, GroundFaceArenas.materialId(data, 0), "no patch until whole palette is registered");
            clock.set(0);
            assertFalse(packed.remap(new GroundUploadBudget(5, 0, clock::incrementAndGet),
                    material -> 0xabc000 + registrations.getAndIncrement()));
            assertEquals(2, registrations.get());
            assertEquals(0xabc000, GroundFaceArenas.materialId(data, 0));
            assertEquals(0, GroundFaceArenas.materialId(data, 256 * 12));
            assertTrue(packed.remap(new GroundUploadBudget(Long.MAX_VALUE), material -> fail("registered twice")));
            assertEquals(0xabc002, packed.requiredMaterials());
            for (int v = 0; v < 1024; v++) {
                int off = v * 12;
                assertEquals(v, Short.toUnsignedInt(data.getShort(off)));
                assertEquals(32768, Short.toUnsignedInt(data.getShort(off + 2)));
                assertEquals(22, Short.toUnsignedInt(data.getShort(off + 4)));
                assertEquals(0xabc000 + v % 2, GroundFaceArenas.materialId(data, off));
                assertEquals(234, Short.toUnsignedInt(data.getShort(off + 8)) & 255);
                assertEquals(0xf0a0, Short.toUnsignedInt(data.getShort(off + 10)));
            }
        }
        assertEquals(0, owner.get());
    }

    @Test
    void cancellationBeforeAllocationAndDuringPackingReturnsAllChargedBytes() {
        var built = GroundQuadtreeMesher.build(Face.TOP, 0, 0, 16, 2, 118,
                (x, z) -> new VoxelColumn(List.of(new VoxelColumn.Run(120, 130, Blocks.STONE.defaultBlockState()))));
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> GroundLodPacking.prepare(built, () -> false));
        var geometry = GroundLodPacking.prepare(built, () -> true);
        var appearances = new GroundLodPacking.Appearance[geometry.quads().size()];
        Arrays.fill(appearances, new GroundLodPacking.Appearance(.1f, .2f, 3, 4, 5, 6, 0, false));
        var owner = new AtomicLong();
        assertThrows(java.util.concurrent.CancellationException.class, () -> GroundLodPacking.pack(
                geometry, appearances, new int[built.vertexCount()], () -> false, owner));
        assertEquals(0, owner.get());
        var calls = new AtomicInteger();
        assertThrows(java.util.concurrent.CancellationException.class, () -> GroundLodPacking.pack(
                geometry, appearances, new int[built.vertexCount()], () -> calls.incrementAndGet() < 7, owner));
        assertEquals(0, owner.get(), "partial buffers and local materials must be released");
    }

    @Test
    void taskTransfersResultAndReleasesSnapshotsOnSuccessFailureAndPreStartCancellation() {
        var built = GroundQuadtreeMesher.build(Face.TOP, 0, 0, 16, 2, 118,
                (x, z) -> new VoxelColumn(List.of(new VoxelColumn.Run(120, 130, Blocks.STONE.defaultBlockState()))));
        var geometry = GroundLodPacking.prepare(built, () -> true);
        var owner = new AtomicLong();
        try (var task = new GroundLodPacking(geometry, owner)) {
            Arrays.fill(task.appearances, new GroundLodPacking.Appearance(.1f, .2f, 3, 4, 5, 6, 0, false));
            CompletableFuture.runAsync(task).join();
            assertTrue(task.done());
            assertEquals("finished", task.outcome());
            assertNull(task.lights);
            assertTrue(Arrays.stream(task.appearances).allMatch(Objects::isNull));
            try (var result = task.take()) {
                assertNotNull(result);
                assertEquals(built.bytes() + 32L * result.materials.size(), owner.get());
            }
        }
        assertEquals(0, owner.get());
        try (var task = new GroundLodPacking(geometry, owner)) {
            task.run(); // Missing snapshot is a stored failure, not a successful empty mesh.
            assertEquals("failure", task.outcome());
            assertThrows(IllegalStateException.class, task::take);
            assertNull(task.lights);
        }
        assertEquals(0, owner.get());
        var cancelled = new GroundLodPacking(geometry, owner);
        cancelled.close();
        cancelled.close();
        cancelled.run();
        assertEquals("cancelled", cancelled.outcome());
        assertEquals(0, owner.get());
    }
}
