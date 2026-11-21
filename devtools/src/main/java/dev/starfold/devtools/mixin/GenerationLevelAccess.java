package dev.starfold.devtools.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkMap.class)
interface GenerationLevelAccess {
    @Accessor("level")
    ServerLevel starfold$generationLevel();
}
