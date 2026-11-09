package dev.starfold.probes;

import dev.starfold.DevelopmentHooks;
import dev.starfold.dev.*;
import dev.starfold.probe.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod("starfold_probes")
public final class StarfoldProbes {
    public StarfoldProbes() {
        DevelopmentHooks.alwaysTicket = DevShipPersistenceProbe::alwaysTicket;
        DevelopmentHooks.heldTicket = DevShipPersistenceProbe::held;
        DevelopmentHooks.mappedMove = (player, packet, mapped) -> {
            if (DevPositionDiagnostics.enabled()) DevPositionDiagnostics.mappedMove(player, packet, mapped);
        };
        DevelopmentHooks.arrival = new DevelopmentHooks.Arrival() {
            @Override public boolean enabled() { return ArrivalProbe.ENABLED; }
            @Override public long start() { return ArrivalProbe.start(); }
            @Override public void end(String name, long start) { ArrivalProbe.end(name, start); }
            @Override public void seamHeld(ServerLevel level, long chunk, boolean ready) { ArrivalProbe.seamHeld(level, chunk, ready); }
            @Override public void missingRead(ServerLevel level, BlockPos query, BlockPos owner) { ArrivalProbe.missingRead(level, query, owner); }
            @Override public void log(String message, Object... values) {
                org.slf4j.LoggerFactory.getLogger("starfold-probe").info(message, values);
            }
        };
        DevelopmentHooks.relight = new DevelopmentHooks.Relight() {
            @Override public boolean enabled() { return RelightProbe.ENABLED; }
            @Override public void changed(ServerLevel level, BlockPos owner) { RelightProbe.changed(level, owner); }
            @Override public void stream(ServerPlayer player, int queued, int processed, long nanos, int sent,
                    int pending, int remaining, int dirty, int lit, long oldestAge) {
                RelightProbe.stream(player, queued, processed, nanos, sent, pending, remaining, dirty, lit, oldestAge);
            }
            @Override public void mismatch(int planet, long image, boolean identity, byte[] emitted, byte[] reference) {
                RelightProbe.mismatch(planet, image, identity, emitted, reference);
            }
        };
        SpaceFixtures.initialize();
        DevBoardingProbe.initialize();
        DevEntityBench.initialize();
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent event) -> DevShipPersistenceProbe.clear());
        DevelopmentHooks.tickLevel = DevShipPersistenceProbe::tick;
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.RegisterCommandsEvent event) ->
                event.getDispatcher().register(net.minecraft.commands.Commands.literal("starfold")
                        .then(DevShipRecords.command()).then(DevShipPersistenceProbe.command())));
    }
}
