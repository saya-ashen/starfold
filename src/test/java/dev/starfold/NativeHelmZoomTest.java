package dev.starfold;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeHelmZoomTest {
    @Test void threeInThreeOutAndBounds(){double factor=1;assertEquals(1.15,NativeHelmClient.ZOOM_STEP);for(int i=0;i<3;i++)factor=NativeHelmClient.zoomed(factor,1);assertEquals(Math.pow(1.15,-3),factor,1e-12);for(int i=0;i<3;i++)factor=NativeHelmClient.zoomed(factor,-1);assertEquals(1,factor,1e-12);assertEquals(.4,NativeHelmClient.zoomed(1,1000));assertEquals(4,NativeHelmClient.zoomed(1,-1000));assertEquals(1,NativeHelmClient.zoomed(1,Double.NaN));}
    @Test void scrollScopeAndTerrainPullInArePreserved()throws Exception{var s=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/starfold/NativeHelmClient.java"));assertTrue(s.contains("mc.screen!=null||body()==null"));assertTrue(s.contains("!scopeView()&&mc.options.getCameraType()==CameraType.THIRD_PERSON_BACK"));assertTrue(s.contains("event.setCanceled(true)"));assertTrue(s.contains("distance=Math.min(distance,"));}
}
