package dev.starfold.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.starfold.NativeGroundFlightServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerPlayer.class)
public abstract class NativeGroundFlightAbilitiesMixin {
    // NeoForge otherwise clears the vessel's flying bit and sends abilities every tick after permit loss.
    // Only this doTick check yields to the session owner; all other mayFly calls retain their meaning.
    @WrapOperation(method="doTick",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayer;mayFly()Z"))
    private boolean starfold$nativeVesselMayFly(ServerPlayer player,Operation<Boolean> original){return NativeGroundFlightServer.controlled(player)||original.call(player);}
}
