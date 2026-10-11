package dev.starfold.mixin;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Our exhaust and space dust: no artificial air drag on inherited ship/universe velocity. */
@Mixin(Particle.class)
public interface NativeExhaustParticleAccess {
    @Accessor("friction") void starfold$friction(float friction);
}
