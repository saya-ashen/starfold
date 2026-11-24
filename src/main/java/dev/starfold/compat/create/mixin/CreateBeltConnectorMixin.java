package dev.starfold.compat.create.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;

import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.UseOnContext;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Give Create's distance, axis and placement checks two endpoints in the same chart. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem", remap = false)
public abstract class CreateBeltConnectorMixin {
    @ModifyExpressionValue(method = "useOn", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/item/ItemStack;get(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;"))
    private Object starfold$projectFirstShaft(Object saved, @Local(argsOnly = true) UseOnContext context) {
        var level = context.getLevel();
        if (!(saved instanceof BlockPos first) || !PlanetLevels.canonical(level)) return saved;
        var access = PlanetAccess.of(level);
        var clicked = context.getClickedPos();
        if (!access.inChart(clicked) || !access.inChart(first)) return saved;
        var owner = access.address(first).owner();
        // The second click can itself be a halo coordinate. Keep the chart it arrived in.
        var image = access.adjacentImage(clicked, owner);
        return image == null ? saved : image;
    }
}
