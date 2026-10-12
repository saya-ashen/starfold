package dev.starfold;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Wiring regression; the rotated-seam probe supplies the actual client/server timing test. */
class TeleportSeamTest {
    private ClassNode type(String name) throws Exception {
        try (var in = TestArchiveResources.open("/" + name + ".class")) {
            assertNotNull(in, name);
            var node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    @Test
    void movementAndTeleportAcknowledgementUseTheSamePendingTeleportGate() throws Exception {
        var mixin = type("dev/starfold/mixin/GroundPlayerSeamNetworkMixin");
        for (String name :
                new String[] {"starfold$nativePlayerSeam", "starfold$nativeAcknowledgedSeam"}) {
            var method =
                    mixin.methods.stream()
                            .filter(m -> m.name.equals(name))
                            .findFirst()
                            .orElseThrow();
            boolean gated = false;
            for (var ins : method.instructions)
                if (ins instanceof MethodInsnNode call) {
                    assertNotEquals("moved", call.name, "No direct seam transport before the gate");
                    gated |= call.name.equals("starfold$finishSeam");
                }
            assertTrue(gated, name);
        }
        var gate =
                mixin.methods.stream()
                        .filter(m -> m.name.equals("starfold$finishSeam"))
                        .findFirst()
                        .orElseThrow();
        FieldInsnNode pending = null;
        JumpInsnNode skip = null;
        MethodInsnNode moved = null;
        for (var ins : gate.instructions) {
            if (ins instanceof FieldInsnNode f && f.name.equals("awaitingPositionFromClient"))
                pending = f;
            if (ins instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNONNULL)
                skip = jump;
            if (ins instanceof MethodInsnNode call && call.name.equals("moved")) moved = call;
        }
        assertNotNull(pending);
        assertNotNull(skip);
        assertNotNull(moved);
        assertTrue(gate.instructions.indexOf(pending) < gate.instructions.indexOf(skip));
        assertTrue(gate.instructions.indexOf(skip) < gate.instructions.indexOf(moved));
        assertTrue(gate.instructions.indexOf(skip.label) > gate.instructions.indexOf(moved));
    }

    @Test
    void upstreamAcknowledgementStillRestoresTheSavedDestinationAndMovementBaseline()
            throws Exception {
        var upstream = type("net/minecraft/server/network/ServerGamePacketListenerImpl");
        var ack =
                upstream.methods.stream()
                        .filter(m -> m.name.equals("handleAcceptTeleportPacket"))
                        .findFirst()
                        .orElseThrow();
        boolean position = false, baseline = false;
        for (var ins : ack.instructions) {
            if (ins instanceof MethodInsnNode call) position |= call.name.equals("absMoveTo");
            if (ins instanceof FieldInsnNode field)
                baseline |= field.name.equals("lastGoodX") && field.getOpcode() == Opcodes.PUTFIELD;
        }
        assertTrue(position);
        assertTrue(baseline);
    }

    private MethodNode method(ClassNode type, String name) {
        return type.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private boolean readsPlayerMover(MethodNode method) {
        for (var ins : method.instructions)
            if (ins instanceof FieldInsnNode f
                    && f.getOpcode() == Opcodes.GETSTATIC
                    && f.owner.equals("net/minecraft/world/entity/MoverType")
                    && f.name.equals("PLAYER")) return true;
        return false;
    }

    /** The vehicle a client steers changes chart after vanilla has compared it with the client's packet, not inside its move. */
    @Test
    void aSteeredVehicleIsTransportedByItsNetworkHandlerBehindTheSameGate() throws Exception {
        var upstream =
                method(
                        type("net/minecraft/server/network/ServerGamePacketListenerImpl"),
                        "handleMoveVehicle");
        assertTrue(
                readsPlayerMover(upstream), "vanilla moves a steered vehicle as MoverType.PLAYER");
        assertTrue(
                readsPlayerMover(
                        method(
                                type("dev/starfold/mixin/GroundTopologyEntityMixin"),
                                "starfold$nativeSeam")),
                "the move hook leaves such a move alone");
        var mixin = type("dev/starfold/mixin/GroundPlayerSeamNetworkMixin");
        for (var ins : method(mixin, "starfold$nativeVehicleSeam").instructions)
            if (ins instanceof MethodInsnNode call) assertNotEquals("moved", call.name);
        var gate = method(mixin, "starfold$finishVehicleSeam");
        FieldInsnNode pending = null, baseline = null;
        MethodInsnNode moved = null;
        for (var ins : gate.instructions) {
            if (ins instanceof FieldInsnNode f && f.name.equals("awaitingPositionFromClient"))
                pending = f;
            if (ins instanceof MethodInsnNode call && call.name.equals("moved")) moved = call;
            if (ins instanceof FieldInsnNode f
                    && f.getOpcode() == Opcodes.PUTFIELD
                    && f.name.equals("vehicleFirstGoodX")) baseline = f;
        }
        assertNotNull(pending);
        assertNotNull(moved);
        assertNotNull(baseline);
        assertTrue(gate.instructions.indexOf(pending) < gate.instructions.indexOf(moved));
        assertTrue(gate.instructions.indexOf(moved) < gate.instructions.indexOf(baseline));
    }

    @Test
    void vehiclePacketsAreRelabelledBothWaysAndRidersGoWithTheirCarrier() throws Exception {
        assertNotNull(
                method(
                        type("dev/starfold/mixin/GroundInboundMixin"),
                        "starfold$nativeVehicleFrame"));
        var map = type("dev/starfold/ground/PacketMap");
        for (String name : new String[] {"inbound", "outbound"}) {
            boolean seen = false;
            String packet =
                    name.equals("inbound")
                            ? "ServerboundMoveVehiclePacket"
                            : "ClientboundMoveVehiclePacket";
            for (var ins : method(map, name).instructions)
                if (ins instanceof TypeInsnNode t
                        && t.getOpcode() == Opcodes.INSTANCEOF
                        && t.desc.endsWith(packet)) seen = true;
            assertTrue(seen, packet);
        }
        boolean passengers = false, carried = false;
        for (var ins : method(type("dev/starfold/ground/GroundSeams"), "moved").instructions)
            if (ins instanceof MethodInsnNode call) {
                passengers |= call.name.equals("getIndirectPassengers");
                carried |= call.name.equals("carry");
            }
        assertTrue(passengers && carried);
    }
}
