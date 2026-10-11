package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.starfold.NativeRiderPose;
import dev.starfold.NativeRiderPoseContext;
import dev.ryanhcode.sable.api.math.OrientedBoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinhelpers.CanFallAtleastHelper;
import dev.ryanhcode.sable.sublevel.SubLevel;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Fit queries only: carried client feet use current hull pose; own float32 floor contact gets 1mm grace. */
@Mixin(value=CanFallAtleastHelper.class,remap=false)
public abstract class NativeHelmCollisionPoseMixin {
    @WrapOperation(method="canFallAtleastWithSubLevels",at=@At(value="INVOKE",target="Ldev/ryanhcode/sable/sublevel/SubLevel;lastPose()Ldev/ryanhcode/sable/companion/math/Pose3dc;"),remap=false)
    private static Pose3dc starfold$fitPose(SubLevel body,Operation<Pose3dc> original){
        var fit=NativeRiderPoseContext.FITTING.get();return fit!=null&&fit.currentPose()&&body==fit.carrying()?body.logicalPose():original.call(body);
    }
    @WrapOperation(method="canFallAtleastWithSubLevels",at=@At(value="INVOKE",target="Ldev/ryanhcode/sable/api/math/OrientedBoundingBox3d;sat(Ldev/ryanhcode/sable/api/math/OrientedBoundingBox3d;Ldev/ryanhcode/sable/api/math/OrientedBoundingBox3d;Lorg/joml/Vector3d;)Lorg/joml/Vector3d;"),remap=false)
    private static Vector3d starfold$ownContact(OrientedBoundingBox3d entity,OrientedBoundingBox3d block,Vector3d result,Operation<Vector3d> original,@Local SubLevel subLevel){
        var fit=NativeRiderPoseContext.FITTING.get();double inset=fit==null?0:NativeRiderPose.inset(fit.standingOrCrouching(),fit.carrying(),subLevel);
        if(inset==0)return original.call(entity,block,result);
        var size=entity.getDimensions();double x=size.x,y=size.y,z=size.z;
        size.set(x-2*inset,y-2*inset,z-2*inset);
        try{return original.call(entity,block,result);}finally{size.set(x,y,z);}
    }
}
