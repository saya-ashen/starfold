package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.starfold.NativeRiderFall;
import dev.starfold.NativeRiderFallContext;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Keep vanilla landing/damage handling, but do not count the deck's motion as a rider's fall. */
@Mixin(ServerPlayer.class)
public abstract class NativeRiderFallMixin {
    @Unique private final NativeRiderFall starfold$riderFall=new NativeRiderFall();
    @WrapMethod(method="doCheckFallDamage")
    private void starfold$relativeFall(double x,double y,double z,boolean ground,Operation<Void> original){
        original.call(x,NativeRiderFallContext.displacement((ServerPlayer)(Object)this,starfold$riderFall,y),z,ground);
    }
}
