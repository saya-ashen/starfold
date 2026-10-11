package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.starfold.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;

/** Light crosses face seams. Only light PROPAGATION is re-addressed: a halo position's stored light is its owner's,
 * and propagation keeps walking the halo in the querying chart (block states come from the ghost chunk views), so a
 * torch lights the other side of a seam and darkening there propagates back. The storages' own section/column
 * bookkeeping is never re-addressed. Light is a scalar, so no rotation is needed. */
@Mixin(LightEngine.class)
public abstract class NativeGroundLightSeamMixin implements NativeLightSeams.Engine {
    @Shadow @Final protected LightChunkGetter chunkSource;
    @Unique private NativePlanetAccess starfold$access;@Unique private volatile boolean starfold$resolved;
    @Override public NativePlanetAccess starfold$seams(){
        if(!starfold$resolved){var level=chunkSource.getLevel();starfold$access=level instanceof ServerLevel server&&NativeGroundServer.is(server)?NativePlanetAccess.of(server):null;starfold$resolved=true;}
        return starfold$access;
    }
    @WrapOperation(method="propagateIncreases",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/lighting/LayerLightSectionStorage;getStoredLevel(J)I"))
    private int starfold$get(LayerLightSectionStorage<?> storage,long pos,Operation<Integer> original){return NativeLightSeams.get(this,storage,pos,owner->original.call(storage,owner));}
    @WrapOperation(method="propagateIncreases",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/lighting/LayerLightSectionStorage;setStoredLevel(JI)V"))
    private void starfold$set(LayerLightSectionStorage<?> storage,long pos,int level,Operation<Void> original){NativeLightSeams.set(this,storage,pos,level,owner->original.call(storage,owner,level));}
}
