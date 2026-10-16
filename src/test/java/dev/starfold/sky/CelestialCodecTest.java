package dev.starfold.sky;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.ground.GroundPackets;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.universe.SystemSkyCodec;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

class CelestialCodecTest {
    private static GroundEphemeris.Model moon() {
        var home = GroundEphemeris.spinning(PlanetSystem.model(new int[] {2, 2}), true, 123);
        var bodies = new ArrayList<>(home.bodies());
        var orbit = home.orbit(0);
        bodies.add(
                new GroundEphemeris.Body(
                        new GroundEphemeris.Orbit(
                                home.index(1),
                                orbit.axis(),
                                orbit.radial(),
                                2000,
                                18000,
                                .7,
                                123,
                                home.spin(1)),
                        0,
                        false,
                        home.count(),
                        512));
        return new GroundEphemeris.Model(bodies);
    }

    @Test
    void jsonNbtSaveAndWireUseTheSameBodySchemaAndPreserveParentsSlotsAndFrozenSpins() {
        var model = moon();
        var json = SystemSkyCodec.MODEL.encodeStart(JsonOps.INSTANCE, model).getOrThrow();
        assertEquals(model, SystemSkyCodec.MODEL.parse(JsonOps.INSTANCE, json).getOrThrow());
        var nbt = SystemSkyCodec.MODEL.encodeStart(NbtOps.INSTANCE, model).getOrThrow();
        assertEquals(nbt, GroundEphemerisSave.modelTag(model));
        assertEquals(model, GroundEphemerisSave.model((CompoundTag) nbt));
        var save = new GroundEphemerisSave(model, 456);
        var tag = save.save(new CompoundTag(), null);
        assertEquals(nbt, tag.getCompound("motion"));
        var loaded = GroundEphemerisSave.load(tag, null);
        assertEquals(456, loaded.tick());
        assertEquals(model, loaded.model);
        for (long tick : new long[] {0, 123, 12345, 999999999999L})
            for (int slot = 0; slot < model.count(); slot++)
                assertEquals(model.at(slot, tick), loaded.model.at(slot, tick));
        var raw = io.netty.buffer.Unpooled.buffer();
        try {
            var buffer = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
            GroundPackets.Sky.CODEC.encode(buffer, new GroundPackets.Sky(456, model));
            assertEquals(456, buffer.readLong());
            assertTrue(buffer.readBoolean());
            assertEquals(nbt, buffer.readNbt());
            assertEquals(0, buffer.readableBytes());
        } finally {
            raw.release();
        }
    }

    @Test
    void oldAndMalformedModelsAreRejectedInsteadOfDefaulted() {
        var tag = GroundEphemerisSave.modelTag(moon());
        for (int version : new int[] {0, 1, 2, 3, 4, 6}) {
            var old = tag.copy();
            old.putInt("format", version);
            assertThrows(IllegalArgumentException.class, () -> GroundEphemerisSave.model(old));
        }
        for (String field : new String[] {"format", "bodies"}) {
            var missing = tag.copy();
            missing.remove(field);
            assertThrows(IllegalArgumentException.class, () -> GroundEphemerisSave.model(missing));
        }
        for (int slot : new int[] {0, 1}) {
            var missingReach = tag.copy();
            missingReach.getList("bodies", 10).getCompound(slot).remove("reach");
            assertThrows(
                    IllegalArgumentException.class, () -> GroundEphemerisSave.model(missingReach));
        }
        var badParent = tag.copy();
        badParent.getList("bodies", 10).getCompound(1).getCompound("motion").putInt("parent", 1);
        assertThrows(IllegalArgumentException.class, () -> GroundEphemerisSave.model(badParent));
        var noLight = tag.copy();
        for (var body : noLight.getList("bodies", 10))
            ((CompoundTag) body).putBoolean("light", false);
        assertThrows(IllegalArgumentException.class, () -> GroundEphemerisSave.model(noLight));
        var missingSpin = tag.copy();
        missingSpin
                .getList("bodies", 10)
                .getCompound(1)
                .getCompound("motion")
                .getCompound("spin")
                .remove("frozen");
        assertThrows(IllegalArgumentException.class, () -> GroundEphemerisSave.model(missingSpin));
        var invalidSlot = tag.copy();
        invalidSlot.getList("bodies", 10).getCompound(1).putInt("slot", 99);
        assertThrows(IllegalArgumentException.class, () -> GroundEphemerisSave.model(invalidSlot));
        var obsolete = new CompoundTag();
        obsolete.put("center", new CompoundTag());
        obsolete.put("sun", new CompoundTag());
        assertThrows(IllegalArgumentException.class, () -> GroundEphemerisSave.model(obsolete));
    }
}
