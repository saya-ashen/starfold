package dev.starfold.mixin;

import dev.starfold.ship.HelmActions;
import dev.starfold.ship.HelmClient;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Stop vanilla before swing, prediction, inventory screens or destruction effects. Flight keys remain queued. */
@Mixin(Minecraft.class)
public abstract class HelmActionsMixin {
    private static boolean blocked() {
        return !HelmActions.allowed(HelmClient.body() != null, HelmActions.Action.ATTACK);
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void starfold$attack(CallbackInfoReturnable<Boolean> ci) {
        if (blocked()) ci.setReturnValue(false);
    }

    @Inject(
            method = {"startUseItem", "pickBlock"},
            at = @At("HEAD"),
            cancellable = true)
    private void starfold$use(CallbackInfo ci) {
        if (blocked()) ci.cancel();
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void starfold$continue(boolean held, CallbackInfo ci) {
        if (blocked()) ci.cancel();
    }

    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void starfold$keys(CallbackInfo ci) {
        var mc = (Minecraft) (Object) this;
        dev.starfold.dev.DevFeelProbe.prepare(mc);
        if (!blocked()) return;
        var o = mc.options;
        for (var key :
                new net.minecraft.client.KeyMapping[] {
                    o.keyAttack,
                    o.keyUse,
                    o.keyPickItem,
                    o.keyDrop,
                    o.keySwapOffhand,
                    o.keyInventory
                }) {
            key.setDown(false);
            while (key.consumeClick()) {}
        }
        if (mc.gameMode != null && mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
    }
}
