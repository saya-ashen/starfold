package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.starfold.ship.RiderPose;
import dev.starfold.ship.RiderPoseContext;

import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Vanilla still selects the pose; a live helm only shares its passenger squeeze exemption. */
@Mixin(Player.class)
public abstract class HelmFitMixin {
    @ModifyExpressionValue(
            method = "updatePlayerPose",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/world/entity/player/Player;isPassenger()Z"))
    private boolean starfold$helmPassenger(boolean passenger) {
        var player = (Player) (Object) this;
        boolean helm = RiderPoseContext.liveHelm(player);
        boolean exempt = RiderPose.passengerDecision(passenger, helm);
        if (Boolean.getBoolean("starfold.proneProbe"))
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE POSEDECISION side {} tick {} passenger {} liveHelm {} squeezeExempt {} uuid {}",
                            player.level().isClientSide ? "client" : "server",
                            player.level().getGameTime(),
                            passenger,
                            helm,
                            exempt,
                            player.getUUID());
        return exempt;
    }

    @WrapMethod(method = "canPlayerFitWithinBlocksAndEntitiesWhen")
    private boolean starfold$fit(Pose pose, Operation<Boolean> original) {
        var previous = RiderPoseContext.FITTING.get();
        RiderPoseContext.FITTING.set(RiderPoseContext.fit((Player) (Object) this, pose));
        try {
            return original.call(pose);
        } finally {
            if (previous == null) RiderPoseContext.FITTING.remove();
            else RiderPoseContext.FITTING.set(previous);
        }
    }
}
