package dev.starfold.planet;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.PlanetShape;
import dev.starfold.ground.GroundFlightPackets;
import dev.starfold.ground.GroundPackets;

import io.netty.buffer.Unpooled;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class PlanetCatalogTest {
    private PlanetCatalog saved;

    @BeforeAll
    static void bootstrapRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @BeforeEach
    void isolateCatalog() {
        saved = PlanetCatalog.current();
        PlanetSystem.reset();
    }

    @AfterEach
    void restoreCatalog() {
        PlanetCatalog.install(saved);
    }

    private static PlanetIdentity identity(String name) {
        return new PlanetIdentity(Optional.empty(), "rocky", Optional.of(name));
    }

    private static PlanetCatalog catalog(int count, int size, int depth, String name) {
        int[] sizes = new int[count], depths = new int[count];
        java.util.Arrays.fill(sizes, size);
        java.util.Arrays.fill(depths, depth);
        return new PlanetCatalog(
                sizes,
                depths,
                java.util.Collections.nCopies(count, name),
                java.util.Collections.nCopies(count, identity(name)));
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    @Test
    void catalogAndPacketOwnTheirArraysAndMetadata() {
        int[] sizes = {1024}, depths = {200};
        var names = new ArrayList<>(List.of("Amber"));
        var identities = new ArrayList<>(List.of(identity("Amber")));
        var catalog = new PlanetCatalog(sizes, depths, names, identities);
        var packet = new GroundPackets.Shapes(sizes, depths, names, identities);
        sizes[0] = 512;
        depths[0] = 191;
        names.set(0, "Changed");
        identities.clear();
        catalog.sizes()[0] = 2048;
        catalog.depths()[0] = 300;
        packet.sizes()[0] = 2048;
        packet.depths()[0] = 300;
        assertEquals(1024, catalog.shape(0).size());
        assertEquals(200, catalog.depth(0));
        assertEquals("Amber", catalog.name(0));
        assertEquals(identity("Amber"), catalog.identity(0));
        assertThrows(UnsupportedOperationException.class, () -> catalog.names().clear());
        assertThrows(UnsupportedOperationException.class, () -> catalog.identities().clear());
        assertThrows(UnsupportedOperationException.class, () -> packet.names().clear());
        assertThrows(UnsupportedOperationException.class, () -> packet.identities().clear());
        packet.assign();
        assertArrayEquals(new int[] {1024}, PlanetCatalog.current().sizes());
        assertArrayEquals(new int[] {200}, PlanetCatalog.current().depths());
        assertEquals("Amber", PlanetSystem.name(0));
        assertEquals(identity("Amber"), PlanetSystem.identity(0));
    }

    @Test
    void compatibilitySettersCopyOnWriteAndRetainTheirIndependentMetadataSemantics() {
        var original = catalog(1, 1024, 200, "Amber");
        PlanetCatalog.install(original);
        PlanetShape.assign(0, new PlanetShape(512), 191);
        var assigned = PlanetCatalog.current();
        assertEquals(1024, original.shape(0).size());
        assertEquals(200, original.depth(0));
        assertEquals(512, assigned.shape(0).size());
        assertEquals(191, assigned.depth(0));
        assertEquals(new PlanetShape(512).radius() - 191 - 1 - .01, PlanetShape.coreRadius(0));
        assertEquals(original.names(), assigned.names());
        assertEquals(original.identities(), assigned.identities());

        PlanetShape.system(new int[] {2048, 1024}, new int[] {300, 200});
        var geometry = PlanetCatalog.current();
        PlanetSystem.names(List.of("Aster", "Beryl", "Cinder"));
        var named = PlanetCatalog.current();
        PlanetSystem.identities(List.of(identity("Beryl")));
        var identified = PlanetCatalog.current();
        assertEquals(1, assigned.count());
        assertEquals(2, geometry.count());
        assertEquals(List.of("Amber"), geometry.names());
        assertEquals(identity("Amber"), named.identity(0));
        assertEquals("Cinder", PlanetSystem.name(2));
        assertEquals("D", PlanetSystem.name(3));
        assertEquals("rocky", PlanetSystem.identity(1).family());
        assertEquals(identity("Beryl"), identified.identity(0));

        PlanetShape.reset();
        var resetShapes = PlanetCatalog.current();
        assertEquals(300, identified.depth(0));
        assertEquals(191, resetShapes.depth(0));
        assertEquals(identified.names(), resetShapes.names());
        assertEquals(identified.identities(), resetShapes.identities());
        PlanetSystem.reset();
        assertEquals(List.of("Aster", "Beryl", "Cinder"), resetShapes.names());
        assertEquals(2, PlanetShape.count());
        assertTrue(PlanetSystem.names().isEmpty());
        assertTrue(PlanetSystem.identities().isEmpty());
        assertEquals("temperate", PlanetSystem.identity(0).family());
    }

    @Test
    void invalidChangesLeaveTheEntirePublishedCatalogUntouched() {
        var before = catalog(1, 1024, 200, "Amber");
        PlanetCatalog.install(before);
        assertThrows(
                IllegalArgumentException.class,
                () -> PlanetShape.system(new int[] {512, 1024}, new int[] {191, 1024}));
        assertSame(before, PlanetCatalog.current());
        assertThrows(
                IllegalArgumentException.class,
                () -> PlanetShape.assign(0, new PlanetShape(512), 512));
        assertSame(before, PlanetCatalog.current());
        assertThrows(
                IllegalArgumentException.class,
                () -> PlanetShape.assign(1, new PlanetShape(512), 191));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PlanetCatalog(
                                new int[] {512}, new int[] {191}, List.of("A", "B"), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PlanetCatalog(
                                new int[] {512},
                                new int[] {191},
                                List.of(),
                                List.of(identity("A"), identity("B"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GroundPackets.Shapes(new int[] {512}, new int[] {512}));
        assertSame(before, PlanetCatalog.current());
    }

    @Test
    void concurrentFullInstallsExposeCoherentRetainedSnapshots() throws Exception {
        var one = catalog(1, 512, 191, "One");
        var three = catalog(3, 1024, 240, "Three");
        PlanetCatalog.install(one);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var writer =
                    executor.submit(
                            () -> {
                                start.await();
                                for (int i = 0; i < 4000; i++)
                                    PlanetCatalog.install((i & 1) == 0 ? three : one);
                                return null;
                            });
            var reader =
                    executor.submit(
                            () -> {
                                start.await();
                                for (int i = 0; i < 4000; i++) {
                                    var snapshot = PlanetCatalog.current();
                                    Thread.yield();
                                    boolean small = snapshot.count() == 1;
                                    int count = small ? 1 : 3,
                                            size = small ? 512 : 1024,
                                            depth = small ? 191 : 240;
                                    String name = small ? "One" : "Three";
                                    assertEquals(count, snapshot.sizes().length);
                                    assertEquals(count, snapshot.depths().length);
                                    assertEquals(count, snapshot.names().size());
                                    assertEquals(count, snapshot.identities().size());
                                    for (int id = 0; id < count; id++) {
                                        assertEquals(size, snapshot.shape(id).size());
                                        assertEquals(depth, snapshot.depth(id));
                                        assertEquals(name, snapshot.name(id));
                                        assertEquals(identity(name), snapshot.identity(id));
                                        assertEquals(
                                                new PlanetShape(size).radius() - depth - 1 - .01,
                                                snapshot.coreRadius(id));
                                    }
                                }
                                return null;
                            });
            start.countDown();
            writer.get(10, TimeUnit.SECONDS);
            reader.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void shapesDecodeInstallsBeforeTheNextPacketsSlotValidationWithoutHandlers() {
        var incoming = catalog(3, 512, 191, "New");
        var packet =
                new GroundPackets.Shapes(
                        incoming.sizes(),
                        incoming.depths(),
                        incoming.names(),
                        incoming.identities());
        var wire = buffer();
        var frameWire = buffer();
        try {
            frameWire.writeVarInt(2);
            frameWire.writeByte(0);
            frameWire.writeByte(0);
            frameWire.writeInt(0);
            frameWire.writeInt(0);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> GroundFlightPackets.Frame.CODEC.decode(frameWire));
            frameWire.readerIndex(0);
            GroundPackets.Shapes.CODEC.encode(wire, packet);
            assertEquals(2, PlanetShape.count());
            GroundPackets.Shapes.CODEC.decode(wire);
            assertEquals(3, PlanetShape.count());
            assertEquals("New", PlanetSystem.name(2));
            assertEquals(identity("New"), PlanetSystem.identity(2));
            assertEquals(2, GroundFlightPackets.Frame.CODEC.decode(frameWire).frame().planet());
            assertEquals(0, wire.readableBytes());
            assertEquals(0, frameWire.readableBytes());
        } finally {
            wire.release();
            frameWire.release();
        }
    }

    @Test
    void malformedDecodeDoesNotPartiallyPublishGeometryOrLabels() {
        var before = catalog(1, 1024, 200, "Amber");
        PlanetCatalog.install(before);
        var wire = buffer();
        try {
            wire.writeVarIntArray(new int[] {512, 1024});
            wire.writeVarIntArray(new int[] {191, 1024});
            wire.writeVarInt(0);
            for (int i = 0; i < 2; i++) {
                wire.writeBoolean(false);
                wire.writeUtf("rocky", 16);
                wire.writeBoolean(false);
            }
            assertThrows(
                    IllegalArgumentException.class, () -> GroundPackets.Shapes.CODEC.decode(wire));
            assertSame(before, PlanetCatalog.current());
        } finally {
            wire.release();
        }
    }
}
