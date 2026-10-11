package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import dev.starfold.CubeSurfaceTopology.Face;
import dev.starfold.PlanetSpace.Side;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CubicMeshTest {
    @Test void sharedTextureAverageUsesLinearTintedAlbedoNotMapColor(){int[] pixels={0xff000000,0xffffffff};var a=CubicColor.average(pixels,20,Side.UP);assertEquals(.5,a[0],1e-6);assertEquals(.73535698,CubicColor.encoded(a[0]),1e-6);var grass=CubicColor.average(new int[]{0xffffffff},22,Side.UP);assertEquals(CubicColor.linear(127/255.0),grass[0],1e-6);assertEquals(CubicColor.linear(179/255.0),grass[1],1e-6);assertEquals(1,CubicColor.average(new int[]{0xffffffff},22,Side.DOWN)[0]);assertEquals(1,CubicColor.average(new int[]{0x00000000,0xffffffff},20,Side.UP)[0]);assertEquals(1,CubicColor.textureWeight(0));assertEquals(0,CubicColor.textureWeight(2));assertEquals(0,CubicColor.textureWeight(8));assertTrue(CubicColor.textureWeight(1)>0&&CubicColor.textureWeight(1)<1);}
}
