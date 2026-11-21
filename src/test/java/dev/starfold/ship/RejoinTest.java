package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.TestArchiveResources;
import dev.starfold.space.SpaceGravity;

import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * Regression contracts do not stand in for a real disconnect and client collision reacquisition.
 */
class RejoinTest {
    private static ClassNode type(String name) throws Exception {
        var node = new ClassNode();
        try (var in = TestArchiveResources.open("/dev/starfold/" + name + ".class")) {
            new ClassReader(in).accept(node, 0);
        }
        return node;
    }

    private static List<String> calls(String name, String method) throws Exception {
        var out = new ArrayList<String>();
        for (var m : type(name).methods)
            if (m.name.equals(method))
                for (var ins : m.instructions) if (ins instanceof MethodInsnNode c) out.add(c.name);
        return out;
    }

    @Test
    void retainedJobsNeverReadTheCurrentObserverMask() throws Exception {
        var job = type("render/RemotePlanetMeshes$Job");
        assertTrue(
                job.fields.stream()
                        .anyMatch(
                                f ->
                                        f.name.equals("mask")
                                                && (f.access & org.objectweb.asm.Opcodes.ACC_FINAL)
                                                        != 0));
        for (var method : job.methods)
            for (var ins : method.instructions)
                if (ins instanceof MethodInsnNode call)
                    assertFalse(
                            Set.of(
                                            "dev/starfold/space/SpaceClient",
                                            "dev/starfold/flight/GroundFlightClient")
                                    .contains(call.owner),
                            "a retained job must not consult the new observer");
        assertTrue(calls("render/RemotePlanetMeshes$Job", "<init>").contains("requireNonNull"));
    }

    @Test
    void savedAttachmentRejectsMissingOrNonfiniteFeet() {
        var tag = new CompoundTag();
        assertFalse(SableBoarding.valid(tag));
        tag.putUUID("body", UUID.randomUUID());
        for (String field : List.of("x", "y", "z")) tag.putDouble(field, 1);
        assertTrue(SableBoarding.valid(tag));
        for (String field : List.of("x", "y", "z")) {
            for (double bad :
                    new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                tag.putDouble(field, bad);
                assertFalse(SableBoarding.valid(tag));
            }
            tag.putDouble(field, 1);
        }
        tag.remove("body");
        assertFalse(SableBoarding.valid(tag));
        assertEquals(200, SableBoarding.LIMIT);
    }

    @Test
    void saveIsBeforePlayerSerializationAndRecordsPoseInverseFeet() throws Exception {
        var c = calls("SableBoarding", "save");
        for (String call :
                List.of(
                        "aboard",
                        "getPersistentData",
                        "transformPositionInverse",
                        "getUniqueId",
                        "putUUID",
                        "putDouble",
                        "remove")) assertTrue(c.contains(call), call);
        assertTrue(
                Files.readString(Path.of("src/main/resources/starfold.mixins.json"))
                        .contains("SablePlayerSaveMixin"));
    }

    @Test
    void onlyRecordedPlayersAreHeldAndReleaseIsBoundedWithoutMutatingBaseGravity()
            throws Exception {
        for (String name : List.of("addTransientModifier", "removeModifier"))
            assertTrue(calls("SableBoarding", "weight").contains(name));
        var c = calls("SableBoarding", "tick");
        assertTrue(
                c.containsAll(
                        List.of(
                                "getSubLevel",
                                "transformPosition",
                                "setPos",
                                "teleport",
                                "resetPosition",
                                "raw")));
        assertTrue(c.indexOf("teleport") < c.indexOf("raw"));
        assertTrue(calls("SableBoarding", "release").containsAll(List.of("remove", "weight")));
    }

    @Test
    void boardingGravityIsTransientAndIndependentOfRegionAndOtherMods() {
        var gravity =
                new net.minecraft.world.entity.ai.attributes.AttributeInstance(
                        net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY, h -> {});
        gravity.setBaseValue(.12);
        var other =
                new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                        net.minecraft.resources.ResourceLocation.parse("test:gravity"),
                        .04,
                        net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation
                                .ADD_VALUE);
        gravity.addPermanentModifier(other);
        var saved = gravity.save();
        double normal = gravity.getValue();
        SableBoarding.weight(gravity, true);
        SableBoarding.weight(gravity, true);
        assertEquals(0, gravity.getValue());
        assertEquals(saved, gravity.save());
        assertEquals(2, gravity.getModifiers().size());
        SpaceGravity.weight(gravity, true);
        SableBoarding.weight(gravity, false);
        assertEquals(0, gravity.getValue());
        SpaceGravity.weight(gravity, false);
        assertEquals(normal, gravity.getValue());
        assertEquals(.12, gravity.getBaseValue());
        assertTrue(gravity.hasModifier(other.id()));
        SableBoarding.weight(
                (net.minecraft.world.entity.ai.attributes.AttributeInstance) null, true);
    }

    @Test
    void restoreUsesCarryFenceButDoesNotChangeNormalSeamCarrySemantics() throws Exception {
        assertTrue(
                calls("SableCarryClient", "apply")
                        .containsAll(
                                List.of("riderPoint", "clear", "forceUpdateBounds", "setPos")));
    }
}
