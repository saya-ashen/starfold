package dev.starfold.waypoint;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.space.SpacePackets;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

class IntentValidationTest {
    @Test
    void attitudeKeepsWireValuesAndRejectsNonfiniteIntentWithoutDecodeFailure() {
        for (float value : new float[] {0, -1, 1, 8, Float.NaN,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
            for (int field = 0; field < 4; field++) {
                var values = new float[4];
                values[field] = value;
                var packet = new SpacePackets.Attitude(values[0], values[1], values[2], values[3]);
                var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
                try {
                    SpacePackets.Attitude.CODEC.encode(buffer, packet);
                    assertEquals(16, buffer.readableBytes());
                    var decoded = SpacePackets.Attitude.CODEC.decode(buffer);
                    assertEquals(packet, decoded);
                    assertEquals(Float.isFinite(value), decoded.finite());
                    assertFalse(buffer.isReadable());
                } finally { buffer.release(); }
            }
    }

    @Test
    void editKeepsLegacyWireNamesAndUnknownActionFeedbackPath() {
        for (var action : new WaypointPackets.Action[] {WaypointPackets.Action.LIST,
                WaypointPackets.Action.ADD, WaypointPackets.Action.REMOVE, WaypointPackets.Action.TARGET}) {
            var packet = new WaypointPackets.Edit(action, "base", 3, 5, 12.5, 0);
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                WaypointPackets.Edit.CODEC.encode(buffer, packet);
                assertEquals(action.wireName, buffer.readUtf(16));
                assertEquals("base", buffer.readUtf(32));
                assertEquals(3, buffer.readInt());
                assertEquals(5, buffer.readInt());
                assertEquals(12.5, buffer.readDouble());
                assertEquals(0, buffer.readDouble());
                buffer.readerIndex(0);
                assertEquals(packet, WaypointPackets.Edit.CODEC.decode(buffer));

            } finally { buffer.release(); }
        }
        for (String unknown : new String[] {"unknown", "ADD", "", "0123456789abcdef"}) {
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                buffer.writeUtf(unknown, 16);
                buffer.writeUtf("base", 32);
                buffer.writeInt(3);
                buffer.writeInt(5);
                buffer.writeDouble(12.5);
                buffer.writeDouble(0);
                var decoded = WaypointPackets.Edit.CODEC.decode(buffer);
                assertEquals(WaypointPackets.Action.UNKNOWN, decoded.action());
                assertEquals("base", decoded.name());
                assertEquals(3, decoded.body());
                assertEquals(5, decoded.face());
                assertEquals(12.5, decoded.u());
                assertEquals(0, decoded.v());
                assertFalse(buffer.isReadable());
            } finally { buffer.release(); }
        }
        for (int face : new int[] {-1, 0, 5, 6})
            for (double u : new double[] {-1, 0, 511.5, 512, Double.NaN, Double.POSITIVE_INFINITY})
                for (double v : new double[] {-1, 0, 511.5, 512, Double.NEGATIVE_INFINITY}) {
                    var packet = new WaypointPackets.Edit(WaypointPackets.Action.ADD, "base", 0, face, u, v);
                    assertEquals(face >= 0 && face < 6 && Double.isFinite(u) && Double.isFinite(v)
                            && u >= 0 && v >= 0 && u < 512 && v < 512, packet.validCoordinates(512));
                }
    }
}
