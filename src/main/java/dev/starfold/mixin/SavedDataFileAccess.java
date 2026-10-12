package dev.starfold.mixin;

import net.minecraft.world.level.storage.DimensionDataStorage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.io.File;

@Mixin(DimensionDataStorage.class)
public interface SavedDataFileAccess {
    @Invoker("getDataFile")
    File starfold$spaceDataFile(String name);
}
