package dev.starfold;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeSpaceRollTest {
    @Test void directLeftWishRollsLeftWithoutReadingThrusterTorque(){
        assertEquals(1,NativeHelmCommand.roll(NativeHelmCommand.LEFT));assertEquals(-1,NativeHelmCommand.roll(NativeHelmCommand.RIGHT));assertEquals(0,NativeHelmCommand.roll(NativeHelmCommand.LEFT|NativeHelmCommand.RIGHT));assertEquals(1,NativeHelmCommand.roll(NativeHelmCommand.LEFT|NativeHelmCommand.SPRINT));
        var turn=NativeSpaceServer.attitude(NativeSpace.Frame.IDENTITY,NativeSpaceTest.tilted(),0,NativeSpace.ORIGIN,10000,0,true,90,0,NativeHelmCommand.roll(NativeHelmCommand.LEFT));
        var down=turn.transform(new Vector3d(0,-1,0));assertTrue(down.z<0);assertEquals(0,down.x,1e-12);
    }
    @Test void zeroYawUsesFullBiteWithoutTheYawAllowance(){
        for(double spin:new double[]{-.4,.4,-11,11})for(double step:new double[]{.01,.05}){
            var change=NativeThruster.level(new Quaterniond(),new Vector3d(0,spin,0),step,true);assertEquals(-spin*Math.min(1,NativeThruster.BITE*step),change.y,1e-12);assertEquals(0,change.x);assertEquals(0,change.z);
            assertNotEquals(change.y,NativeThruster.level(new Quaterniond(),new Vector3d(0,spin,0),step).y);
        }
        var q=new Quaterniond().rotateX(.4);var spin=new Vector3d(1,.7,2);var old=NativeThruster.level(q,spin,.01);var zero=NativeThruster.level(q,spin,.01,true);assertEquals(old.x,zero.x);assertEquals(old.z,zero.z);
    }
}
