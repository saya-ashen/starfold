package dev.starfold.compat.create.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorConnectionPacket;

import dev.starfold.ground.ObserverFrames;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "com.simibubi.create.foundation.networking.BlockEntityConfigurationPacket", remap = false)
public abstract class CreateConfigurationPacketMixin {
    @ModifyExpressionValue(method = "handle", at = @At(value = "FIELD",
            target = "Lcom/simibubi/create/foundation/networking/BlockEntityConfigurationPacket;pos:Lnet/minecraft/core/BlockPos;"))
    private BlockPos starfold$conveyorSource(BlockPos pos, ServerPlayer player) {
        return (Object) this instanceof ChainConveyorConnectionPacket && ObserverFrames.active(player)
                ? ObserverFrames.get(player).toStorage(pos) : pos;
    }
}
