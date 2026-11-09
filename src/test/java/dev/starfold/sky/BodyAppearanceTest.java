package dev.starfold.sky;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.GroundEphemeris.Appearance;
import dev.starfold.chart.GroundSolar;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.ground.GroundPackets;
import dev.starfold.planet.MoonSystemTest;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.universe.SystemSkyCodec;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;

import org.junit.jupiter.api.Test;

import java.util.List;

class BodyAppearanceTest {
    @Test
    void appearanceRequiresItsCapabilityAndCannotBeNull() {
        var fixed = new GroundEphemeris.Fixed(GroundEphemeris.ZERO);
        assertThrows(
                IllegalArgumentException.class,
                () -> new GroundEphemeris.Body(fixed, 512, true, -1, Appearance.SURFACE));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GroundEphemeris.Body(fixed, 512, false, 0, Appearance.LIGHT));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GroundEphemeris.Body(fixed, 512, true, -1, null));
    }

    private static GroundEphemeris.Model declared() {
        // A luminous surface must not be turned into a sun by either codec or the renderer.
        return new GroundEphemeris.Model(
                List.of(
                        new GroundEphemeris.Body(
                                new GroundEphemeris.Fixed(GroundEphemeris.ZERO),
                                512,
                                true,
                                0,
                                Appearance.SURFACE),
                        new GroundEphemeris.Body(
                                new GroundEphemeris.Fixed(new Point(10000, 0, 0)),
                                512,
                                true,
                                -1,
                                Appearance.LIGHT)));
    }

    @Test
    void drawnLightsAreChosenByAppearanceNotIllumination() {
        var model = declared();
        assertEquals(2, model.everyStar().size());
        assertEquals(List.of(model.everyStar().get(1)), model.visibleStars());
        assertEquals(model.bodies(), GroundEphemeris.spinning(model, true, 100).bodies());
    }

    @Test
    void catalogAndActualNetworkPacketRoundTripDeclaredAppearance() {
        var model = declared();
        var catalog =
                SystemSkyCodec.CATALOG.encodeStart(NbtOps.INSTANCE, model.bodies()).getOrThrow();
        assertEquals(
                model.bodies(),
                SystemSkyCodec.CATALOG.parse(NbtOps.INSTANCE, catalog).getOrThrow());
        var save = new GroundEphemerisSave(model, 123).save(new CompoundTag(), null);
        assertEquals(model, GroundEphemerisSave.load(save, null).model);
        var raw = io.netty.buffer.Unpooled.buffer();
        try {
            var buffer = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
            var packet = new GroundPackets.Sky(123, model);
            GroundPackets.Sky.CODEC.encode(buffer, packet);
            assertEquals(packet, GroundPackets.Sky.CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            raw.release();
        }
    }

    @Test
    void missingUnknownAndInconsistentAppearanceAreRejectedOnDiskAndWire() {
        var model = GroundSolar.defaults();
        for (var codec : List.of(SystemSkyCodec.MODEL, SystemSkyCodec.SAVED_MODEL)) {
            var original = (CompoundTag) codec.encodeStart(NbtOps.INSTANCE, model).getOrThrow();
            for (String value : new String[] {"", "UNKNOWN", "SURFACE"}) {
                var bad = original.copy();
                var sun = bad.getList("bodies", 10).getCompound(0);
                if (value.isEmpty()) sun.remove("appearance");
                else sun.putString("appearance", value);
                assertThrows(
                        RuntimeException.class,
                        () -> codec.parse(NbtOps.INSTANCE, bad).getOrThrow());
            }
            var bad = original.copy();
            bad.getList("bodies", 10).getCompound(1).putString("appearance", "LIGHT");
            assertThrows(
                    RuntimeException.class, () -> codec.parse(NbtOps.INSTANCE, bad).getOrThrow());
        }
    }

    @Test
    void previousSaveAndWireFormatsAreRefusedEvenWithNewFields() {
        var model = declared();
        var saved = new GroundEphemerisSave(model, 0).save(new CompoundTag(), null);
        saved.putInt("format", 6);
        assertThrows(IllegalArgumentException.class, () -> GroundEphemerisSave.load(saved, null));
        var inner = new GroundEphemerisSave(model, 0).save(new CompoundTag(), null);
        inner.getCompound("motion").putInt("format", 6);
        assertThrows(IllegalArgumentException.class, () -> GroundEphemerisSave.load(inner, null));
        var wire = GroundEphemerisSave.modelTag(model);
        wire.putInt("format", 5);
        var raw = io.netty.buffer.Unpooled.buffer();
        try {
            var buffer = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
            buffer.writeLong(0);
            buffer.writeBoolean(true);
            buffer.writeNbt(wire);
            assertThrows(
                    IllegalArgumentException.class, () -> GroundPackets.Sky.CODEC.decode(buffer));
        } finally {
            raw.release();
        }
    }

    @Test
    void templatesFiniteSystemsAndAuthoredMoonsDeclareExistingKinds() throws Exception {
        MoonSystemTest.boot();
        for (var model :
                List.of(
                        GroundSolar.defaults(),
                        PlanetSystem.model(new int[] {2, 2}),
                        MoonSystemTest.model())) {
            for (var body : model.bodies()) {
                assertEquals(
                        body.slot() >= 0 ? Appearance.SURFACE : Appearance.LIGHT,
                        body.appearance());
            }
            assertEquals(model.everyStar(), model.visibleStars());
            assertEquals(
                    model.bodies().stream().map(GroundEphemeris.Body::appearance).toList(),
                    GroundEphemeris.spinning(model, false, 123).bodies().stream()
                            .map(GroundEphemeris.Body::appearance)
                            .toList());
        }
    }
}
