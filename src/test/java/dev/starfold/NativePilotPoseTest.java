package dev.starfold;

import dev.ryanhcode.sable.companion.math.Pose3d;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class NativePilotPoseTest {
    private static String source(String name)throws Exception{return Files.readString(Path.of("src/main/java/dev/starfold/"+name+".java"));}
    @Test void carriedFeetAndRoofMustUseTheSamePose(){
        for(double displacement:new double[]{-2,-1.5,0,1.5,2}){var before=new Pose3d();before.position().set(30,100,60);var now=new Pose3d(before);now.position().add(0,displacement,0);var local=new Vector3d(1,1,1);var carried=now.transformPosition(new Vector3d(local));var correct=now.transformPositionInverse(new Vector3d(carried));var stale=before.transformPositionInverse(new Vector3d(carried));assertEquals(1,correct.y,1e-9);assertEquals(1+displacement,stale.y,1e-9);assertTrue(correct.y+1.8<4);if(displacement>=1.5)assertTrue(stale.y+1.8>4);}
    }
    @Test void poseFitScopeCannotLeakToMovementOrOtherPlayers()throws Exception{
        var fit=source("mixin/NativeHelmFitMixin");assertTrue(fit.contains("@WrapMethod(method=\"canPlayerFitWithinBlocksAndEntitiesWhen\")"));assertTrue(fit.contains("NativeRiderPoseContext.fit((Player)(Object)this,pose)"));assertTrue(fit.contains("finally{if(previous==null)NativeRiderPoseContext.FITTING.remove()"));assertTrue(fit.contains("Player;isPassenger()Z"));
        var collision=source("mixin/NativeHelmCollisionPoseMixin");assertTrue(collision.contains("fit.currentPose()&&body==fit.carrying()?body.logicalPose():original.call(body)"));assertTrue(collision.contains("NativeRiderPose.inset(fit.standingOrCrouching(),fit.carrying(),subLevel)"));assertTrue(collision.contains("finally{size.set(x,y,z);}"));assertFalse(fit.contains("setPose"));assertFalse(fit.contains("setPos("));assertFalse(collision.contains("return true"));assertFalse(source("NativeHelmClient").contains("Pose.STANDING"));
        for(var name:new String[]{"NativeHelmFitMixin","NativeHelmCollisionPoseMixin"}){String mixin="dev.starfold.mixin."+name;assertFalse(RendererCompatibility.apply(mixin,id->false));assertTrue(RendererCompatibility.apply(mixin,id->id.equals("sable")));assertTrue(RendererCompatibility.apply(mixin,id->id.equals("sable")||id.equals("simulated")));}
    }
    @Test void exhaustUsesPointVelocityAndClientOnlyTicker()throws Exception{
        var particle=source("NativeThrusterClient");assertTrue(particle.contains("if(power==0)return"));assertTrue(particle.contains("pose.transformPosition(new Vector3d(nozzle))"));assertTrue(particle.contains("body.lastPose().transformPosition"));assertTrue(particle.contains("pose.orientation().transform"));assertTrue(particle.contains(".add(inherited)"));assertTrue(particle.contains("particle.setLifetime(boosted?4:3)"));assertTrue(particle.contains("starfold$friction(1)"));assertTrue(particle.contains("booster.getBlockState().getValue(BlockStateProperties.POWERED)"));assertTrue(source("NativeThruster").contains("level.isClientSide&&type==thrusterType?"));
    }
}
