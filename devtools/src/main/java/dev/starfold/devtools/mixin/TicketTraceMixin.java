package dev.starfold.devtools.mixin;

import dev.starfold.api.ChunkWorkDiagnostics;
import dev.starfold.api.ChunkWorkDiagnostics.Stage;
import dev.starfold.planet.PlanetLevels;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.SortedArraySet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DistanceManager.class)
abstract class TicketTraceMixin {
    @Shadow @Final private Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> tickets;
    @Unique private ChunkWorkDiagnostics.Listener starfold$ticketTrace;

    @Inject(method = "runAllUpdates", at = @At("HEAD"))
    private void starfold$snapshotTickets(ChunkMap map, CallbackInfoReturnable<Boolean> ci) {
        var trace = ChunkWorkDiagnostics.listener();
        if (trace == starfold$ticketTrace) return;
        starfold$ticketTrace = trace != null && PlanetLevels.is(((GenerationLevelAccess) map).starfold$generationLevel()) ? trace : null;
        if (starfold$ticketTrace == null) return;
        long at = System.nanoTime();
        for (var entry : tickets.long2ObjectEntrySet()) {
            for (var ticket : entry.getValue()) starfold$recordTicket(entry.getLongKey(), ticket, at, true);
        }
    }

    @Inject(method = "addTicket(JLnet/minecraft/server/level/Ticket;)V", at = @At("RETURN"))
    private void starfold$addTicket(long key, Ticket<?> ticket, CallbackInfo ci) {
        if (starfold$ticketTrace != null) starfold$recordTicket(key, ticket, System.nanoTime(), true);
    }

    @Inject(method = "removeTicket(JLnet/minecraft/server/level/Ticket;)V", at = @At("RETURN"))
    private void starfold$removeTicket(long key, Ticket<?> ticket, CallbackInfo ci) {
        if (starfold$ticketTrace == null) return;
        var current = tickets.get(key);
        if (current == null || current.stream().noneMatch(t -> t.getType() == ticket.getType()
                && t.getTicketLevel() == ticket.getTicketLevel())) {
            starfold$recordTicket(key, ticket, System.nanoTime(), false);
        }
    }

    @Unique
    private void starfold$recordTicket(long key, Ticket<?> ticket, long at, boolean present) {
        Stage stage = ticket.getType() == TicketType.PLAYER ? Stage.TICKET_PLAYER : switch (ticket.getType().toString()) {
            case "starfold_native_view" -> Stage.TICKET_NATIVE_VIEW;
            case "starfold_native_chart" -> Stage.TICKET_NATIVE_CHART;
            case "starfold_space_view" -> Stage.TICKET_SPACE_VIEW;
            default -> Stage.TICKET_OTHER;
        };
        int level = ticket.getTicketLevel();
        starfold$ticketTrace.generationLayer(stage, key, present ? level : -level - 1, null, at);
    }
}
