package dev.starfold.mixin;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import java.util.List;
@Mixin(targets="net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface PeriodicTrackedAccess {
    @Invoker("updatePlayers") void starfold$updateObservers(List<ServerPlayer> players);
}
