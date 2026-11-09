package dev.starfold.probes.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerGamePacketListenerImpl.class)
public interface GroundPositionDiagnosticAccess {
    @Accessor("awaitingTeleport")
    int starfold$diagnosticTeleportId();

    @Accessor("awaitingPositionFromClient")
    Vec3 starfold$diagnosticAwaiting();

    @Accessor("tickCount")
    int starfold$diagnosticListenerTick();
}
