package dev.starfold.space;

import dev.starfold.chart.GasPlayerExposure;
import dev.starfold.chart.GroundEphemeris;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** Personal session/dimension/order/TTL fence, independent of ships and their pressure cache. */
public final class GasPlayerExposureCache {
    public record Identity(ResourceLocation dimension, long session) {
        public Identity {
            if (dimension == null || session == 0)
                throw new IllegalArgumentException("Personal gas identity");
        }
    }

    private Identity focus, lastIdentity;
    private long retiredSession, sequence = -1, received;
    private GasPlayerExposurePacket packet;

    /** Authority comes from the current region View, never from a personal pressure packet. */
    public void focus(Identity next) {
        if (next != null && next.session() == retiredSession) next = null;
        if (!Objects.equals(focus, next)) {
            focus = next;
            // Losing authority clears the display, not the last identity's ordering fence.
            if (next != null && !next.equals(lastIdentity)) {
                lastIdentity = next;
                sequence = -1;
            }
            packet = null;
        }
    }

    /** Level/player replacement must wait for a new View session, even for same-dimension respawn. */
    public void invalidate() {
        retiredSession = lastIdentity == null ? 0 : lastIdentity.session();
        focus(null);
    }

    /** Disconnect ends authority altogether; a new connection must supply its own View session. */
    public void reset() {
        focus(null);
        lastIdentity = null;
        retiredSession = 0;
        sequence = -1;
    }

    public boolean receive(
            GasPlayerExposurePacket next, GroundEphemeris.Model model, long clientTick) {
        if (clientTick < 0) throw new IllegalArgumentException("Personal gas receipt clock");
        if (next == null || focus == null
                || next.session() != focus.session()
                || !next.dimension().equals(focus.dimension())
                || next.sequence() <= sequence || !compatible(next, model)) return false;
        sequence = next.sequence();
        received = clientTick;
        packet = next;
        return true;
    }

    /** Validate catalog readings without turning client geometry into damage authority. */
    public static boolean compatible(GasPlayerExposurePacket next, GroundEphemeris.Model model) {
        if (next.snapshot().isEmpty()) return true;
        var sample = next.snapshot().orElseThrow().sample();
        if (sample.reading().isEmpty()) return true;
        if (model == null) return false;
        var r = sample.reading().orElseThrow();
        if (r.bodyIndex() >= model.bodies().size()) return false;
        var body = model.catalogBody(r.bodyIndex());
        if (body.gas().isEmpty() || r.depth() > body.radius()) return false;
        var policy = body.gas().orElseThrow();
        if (Math.abs(policy.pressure(r.depth()) - r.pressure()) > 1e-6
                || sample.condition() == GasPlayerExposure.Condition.DANGER && r.pressure() != 1)
            return false;
        // The core compares exact depth, not rounded/saturated pressure, for SAFE.
        // DANGER may have a tied worst reading from another GAS; never infer damage from it.
        return sample.condition() != GasPlayerExposure.Condition.SAFE
                || r.depth() < policy.maxDepth();
    }

    /** Receipt TTL spends server age too; transit is unmeasured. Timers are NEVER extrapolated. */
    public GasPlayerExposurePacket current(long clientTick) {
        if (packet == null || clientTick < received
                || clientTick - received >= GasPlayerExposurePacket.FRESH_TICKS - packet.age())
            return null;
        return packet;
    }
}
