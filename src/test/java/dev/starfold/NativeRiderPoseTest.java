package dev.starfold;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeRiderPoseTest {
    @Test void helmUsesPassengerDecisionOnlyWhileLive() {
        assertTrue(NativeRiderPose.passengerDecision(false,true));
        assertTrue(NativeRiderPose.passengerDecision(true,false));
        assertFalse(NativeRiderPose.passengerDecision(false,false)); // released/lost lease/deleted hull
    }
    @Test void insetIsOnlyForStandingOrCrouchingOnTheCarryingHull() {
        Object hull=new Object(), other=new Object();
        assertEquals(1e-3,NativeRiderPose.inset(true,hull,hull));
        assertEquals(0,NativeRiderPose.inset(true,hull,other));
        assertEquals(0,NativeRiderPose.inset(true,null,null));
        assertEquals(0,NativeRiderPose.inset(false,hull,hull));
    }
    @Test void passengerExemptionEndsAtLeaseExpiry() {
        assertTrue(NativeRiderPose.passengerDecision(false,NativeHelmCommand.fresh(100,105)));
        assertFalse(NativeRiderPose.passengerDecision(false,NativeHelmCommand.fresh(100,106)));
        assertFalse(NativeRiderPose.passengerDecision(false,NativeHelmCommand.fresh(-1,100)));
    }
    @Test void bothSidesHookOnlyVanillasPassengerExpression() throws Exception {
        var mixins=new com.google.gson.Gson().fromJson(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/starfold.mixins.json")),com.google.gson.JsonObject.class);
        assertTrue(mixins.getAsJsonArray("mixins").asList().stream().anyMatch(x->x.getAsString().equals("NativeHelmFitMixin")));
        String source=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/starfold/mixin/NativeHelmFitMixin.java"));
        assertTrue(source.contains("method=\"updatePlayerPose\""));assertTrue(source.contains("Player;isPassenger()Z"));
        assertFalse(source.contains("setPose("));assertFalse(source.contains("cancellable=true"));
    }
    @Test void actualSableSatKeepsCeilingButDiscardsTinyOwnFloorOverlap() {
        var sink=new dev.ryanhcode.sable.api.math.LevelReusedVectors();
        var orientation=new org.joml.Quaterniond().rotateY(.7);
        var player=new dev.ryanhcode.sable.api.math.OrientedBoundingBox3d(0,.9-3e-5,0,.5-2e-7,1.8-2e-7,.5-2e-7,orientation,sink);
        var floor=new dev.ryanhcode.sable.api.math.OrientedBoundingBox3d(0,-.5,0,4,1,4,orientation,sink);
        var ceiling=new dev.ryanhcode.sable.api.math.OrientedBoundingBox3d(0,1.75,0,4,.5,4,orientation,sink);
        assertTrue(dev.ryanhcode.sable.api.math.OrientedBoundingBox3d.sat(player,floor).lengthSquared()>0);
        player.getDimensions().sub(.002,.002,.002);
        assertEquals(0,dev.ryanhcode.sable.api.math.OrientedBoundingBox3d.sat(player,floor).lengthSquared());
        assertTrue(dev.ryanhcode.sable.api.math.OrientedBoundingBox3d.sat(player,ceiling).lengthSquared()>0);
        player.getPosition().y=.75-3e-5;player.getDimensions().y=1.5-2e-7-.002;
        assertEquals(0,dev.ryanhcode.sable.api.math.OrientedBoundingBox3d.sat(player,ceiling).lengthSquared());
    }
    @Test void floatFloorPenetrationFitsButRealCeilingStillCrouches() {
        Object hull=new Object();double inset=NativeRiderPose.inset(true,hull,hull);
        var floor=new AABB(-2,-1,-2,2,0,2);
        var standing=new AABB(-.3,-3e-5,-.3,.3,1.8-3e-5,.3).deflate(1e-7);
        assertTrue(standing.intersects(floor));
        assertFalse(standing.deflate(inset).intersects(floor));
        var ceiling=new AABB(-2,1.5,-2,2,2,2);
        assertTrue(standing.deflate(inset).intersects(ceiling));
        var crouching=new AABB(-.3,-3e-5,-.3,.3,1.5-3e-5,.3).deflate(1e-7+inset);
        assertFalse(crouching.intersects(ceiling));assertFalse(crouching.intersects(floor));
        assertTrue(standing.deflate(NativeRiderPose.inset(true,hull,new Object())).intersects(floor));
    }
}
