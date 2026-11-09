package dev.starfold.ship;

import dev.starfold.chart.GasPressure;
import dev.starfold.chart.GroundEphemeris;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.UUID;

/** Pure identity/order/freshness fence. Focus comes from current client authority, never the packet. */
public final class GasPressureCache {
    public record Identity(ResourceLocation dimension, long session, UUID hull) {
        public Identity {
            if (dimension == null || session == 0 || hull == null)
                throw new IllegalArgumentException("Gas pressure identity");
        }
    }

    private Identity focus;
    private long sequence = -1, received;
    private GasPressurePacket packet;

    public void focus(Identity next) {
        if (!java.util.Objects.equals(focus, next)) {
            focus = next;
            sequence = -1;
            packet = null;
        }
    }

    /** Invalid/reordered data never refreshes packet state, sequence or the remaining TTL. */
    public boolean receive(GasPressurePacket next, GroundEphemeris.Model model, long clientTick) {
        if (clientTick < 0) throw new IllegalArgumentException("Gas pressure receipt clock");
        if (focus == null
                || model == null
                || next.session() != focus.session()
                || !next.hull().equals(focus.hull())
                || next.sequence() <= sequence) return false;
        if (!compatible(next, model)) return false;
        sequence = next.sequence();
        received = clientTick;
        packet = next;
        return true;
    }

    public static boolean compatible(GasPressurePacket next, GroundEphemeris.Model model) {
        if (model == null) return false;
        for (var r : next.readings()) {
            if (r.bodyIndex() >= model.bodies().size()) return false;
            var body = model.catalogBody(r.bodyIndex());
            if (body.gas().isEmpty()
                    || r.depth() > body.radius()
                    || Math.abs(body.gas().orElseThrow().pressure(r.depth()) - r.pressure()) > 1e-6)
                return false;
        }
        if (next.readings().size() == 2
                && next.readings().get(0).pressure() < next.readings().get(1).pressure())
            return false;
        return true;
    }

    /** Server-reported age spends part of the 40-tick receipt budget; network transit is not measured. */
    public GasPressurePacket current(long clientTick) {
        if (packet == null
                || clientTick < received
                || clientTick - received >= GasPressurePacket.FRESH_TICKS - packet.age())
            return null;
        return packet;
    }

    public Optional<GasPressure.Reading> ambient(long clientTick) {
        var current = current(clientTick);
        return current == null || current.status() != GasPressurePacket.Status.KNOWN
                ? Optional.empty()
                : Optional.of(current.readings().getFirst());
    }

    public Optional<GasPressure.Reading> selected(int bodyIndex, long clientTick) {
        var current = current(clientTick);
        return current == null
                ? Optional.empty()
                : current.readings().stream().filter(r -> r.bodyIndex() == bodyIndex).findFirst();
    }
}
