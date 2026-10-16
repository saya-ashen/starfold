package dev.starfold.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.LevelEntityGetter;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Raw storage lookup, without chart images or sub-level projection. */
@Mixin(Level.class)
public interface LevelEntityAccess {
    @Invoker("getEntities")
    LevelEntityGetter<Entity> starfold$entities();
}
