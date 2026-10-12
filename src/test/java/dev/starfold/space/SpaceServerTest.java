package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.TestArchiveResources;
import dev.starfold.geometry.BentStripGeometry;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.sky.Space;
import dev.starfold.sky.SpaceTest;

import net.minecraft.nbt.CompoundTag;

import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

class SpaceServerTest {
    @Test
    void savedFrameRoundTripsWithoutRegistriesAndRejectsCorruption() {
        var frame =
                Space.Frame.keeping(
                        SpaceTest.chart(new BentStripGeometry.Point(123, 456, 789)),
                        new Quaterniond().rotationXYZ(.4, .8, -.2),
                        SpaceTest.tilted(),
                        1,
                        1234);
        var value = new SpaceServer(frame);
        var tag = value.save(new CompoundTag(), null);
        tag.putLong("epoch", 1234);
        var loaded = SpaceServer.load(tag, null);
        assertEquals(tag, loaded.save(new CompoundTag(), null));
        assertFalse(loaded.isDirty());
        var fresh = SpaceServer.fresh(false);
        assertTrue(fresh.isDirty());
        assertEquals(
                new SpaceServer(Space.Frame.IDENTITY).save(new CompoundTag(), null),
                fresh.save(new CompoundTag(), null));
        assertThrows(IllegalStateException.class, () -> SpaceServer.fresh(true));
        for (String key : new String[] {"qx", "qy", "qz", "qw", "x", "y", "z", "planet", "epoch"}) {
            var bad = tag.copy();
            bad.remove(key);
            assertThrows(IllegalArgumentException.class, () -> SpaceServer.load(bad, null));
        }
        var bad = tag.copy();
        bad.putDouble("qw", 20);
        assertThrows(IllegalArgumentException.class, () -> SpaceServer.load(bad, null));
        var invalid = tag.copy();
        invalid.putDouble("x", Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> SpaceServer.load(invalid, null));
        for (int planet : new int[] {-1, PlanetStorage.MAX_PLANETS}) {
            var wrong = tag.copy();
            wrong.putInt("planet", planet);
            assertThrows(IllegalArgumentException.class, () -> SpaceServer.load(wrong, null));
        }
        var past = tag.copy();
        past.putLong("epoch", -1);
        assertThrows(IllegalArgumentException.class, () -> SpaceServer.load(past, null));
    }

    @Test
    void anchorThresholdIsStrictOnEveryAxisAndWithinSablesDefaultBand() {
        assertEquals(-10000, Space.SABLE_MIN_Y);
        assertEquals(100000, Space.SABLE_MAX_Y);
        assertEquals(8192, Space.Y);
        assertEquals(12000, Space.ANCHOR_REACH);
        assertTrue(Space.Y - Space.ANCHOR_REACH > Space.SABLE_MIN_Y);
        assertTrue(Space.Y + Space.ANCHOR_REACH < Space.SABLE_MAX_Y);
        for (int axis = 0; axis < 3; axis++)
            for (int sign : new int[] {-1, 1})
                for (double distance : new double[] {12000, 12001}) {
                    var delta =
                            new BentStripGeometry.Point(
                                    axis == 0 ? sign * distance : 0,
                                    axis == 1 ? sign * distance : 0,
                                    axis == 2 ? sign * distance : 0);
                    var at = Space.ORIGIN.add(delta);
                    var shift = SpaceServer.anchor(at);
                    if (distance == 12000) assertNull(shift);
                    else SpaceTest.point(Space.ORIGIN, at.add(shift));
                }
    }

    @Test
    void referenceNeedsMoreThan512BlocksAdvantage() {
        var model = SpaceTest.tilted();
        for (double tick : new double[] {0, 1234, 9876}) {
            var a = model.at(0, tick).center();
            var b = model.at(1, tick).center();
            var axis = b.sub(a).scale(1 / b.sub(a).length());
            var middle = a.add(b).scale(.5);
            assertFalse(SpaceServer.nearer(model, middle.add(axis.scale(255)), 0, 1, tick));
            assertTrue(SpaceServer.nearer(model, middle.add(axis.scale(257)), 0, 1, tick));
            assertFalse(SpaceServer.nearer(model, b, 1, 1, tick));
        }
    }

    @Test
    void enteringChecksLiveRegionBodiesBeforeReplacingAndDirtyingItsOwnLevelsFrame()
            throws Exception {
        var type = new ClassNode();
        try (var in = TestArchiveResources.open("/dev/starfold/space/SpaceServer.class")) {
            new ClassReader(in).accept(type, 0);
        }
        var method =
                type.methods.stream()
                        .filter(m -> m.name.equals("entering"))
                        .findFirst()
                        .orElseThrow();
        var names = new java.util.ArrayList<String>();
        for (var ins : method.instructions)
            if (ins instanceof MethodInsnNode call) names.add(call.name);
        for (String name :
                new String[] {
                    "getContainer", "getAllSubLevels", "isRemoved", "holds", "keeping", "setDirty"
                }) assertTrue(names.contains(name), name);
        assertTrue(names.indexOf("isRemoved") < names.indexOf("holds"));
        assertTrue(names.indexOf("holds") < names.indexOf("keeping"));
        assertTrue(names.indexOf("keeping") < names.indexOf("setDirty"));
        boolean returned = false;
        for (var ins : method.instructions) {
            if (ins instanceof MethodInsnNode call && call.name.equals("keeping")) break;
            if (ins.getOpcode() == org.objectweb.asm.Opcodes.ARETURN) returned = true;
        }
        assertTrue(returned, "occupied region returns before replacement");
        var get = type.methods.stream().filter(m -> m.name.equals("get")).findFirst().orElseThrow();
        boolean storage = false;
        for (var ins : get.instructions)
            if (ins instanceof MethodInsnNode call) {
                assertNotEquals("authority", call.name);
                if (call.name.equals("getDataStorage")) storage = true;
            }
        assertTrue(storage);
    }
}
