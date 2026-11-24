package dev.starfold.compat.jade.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import dev.starfold.ground.ObserverFrames;
import dev.starfold.planet.PlanetAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Consumer;

@Pseudo
@Mixin(targets = "snownee.jade.impl.BlockAccessorImpl", remap = false)
public abstract class JadeBlockResponseMixin {
    @WrapOperation(method = "lambda$handleRequest$0", at = @At(value = "INVOKE",
            target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"))
    private static void starfold$clientPosition(Consumer<Object> response, Object value, Operation<Void> original,
            @Local(argsOnly = true) ServerPlayer player) {
        if (ObserverFrames.active(player) && value instanceof CompoundTag data) {
            var frame = ObserverFrames.get(player);
            var pos = new BlockPos(data.getInt("x"), data.getInt("y"), data.getInt("z"));
            if (!frame.isIdentity() && PlanetAccess.of(player.level()).inChart(pos)) {
                var image = frame.toClient(pos);
                data.putInt("x", image.getX());
                data.putInt("y", image.getY());
                data.putInt("z", image.getZ());
            }
        }
        original.call(response, value);
    }
}
