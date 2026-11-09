package dev.starfold.mixin;

import dev.starfold.worldgen.plan.StructureEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;

@Mixin(StructureTemplate.class)
public abstract class StructureTemplateEntitiesMixin {
    @Shadow @Final private List<StructureTemplate.StructureEntityInfo> entityInfoList;
    @Inject(method="addEntitiesToWorld",at=@At("HEAD"),cancellable=true)
    private void starfold$recordEntities(ServerLevelAccessor level,BlockPos origin,StructurePlaceSettings settings,CallbackInfo ci) {
        if(StructureEntities.captureTemplate((StructureTemplate)(Object)this,level,origin,settings,entityInfoList))ci.cancel();
    }
}
