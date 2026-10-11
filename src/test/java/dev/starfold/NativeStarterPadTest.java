package dev.starfold;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeStarterPadTest {
    @Test void radiusFitsEveryHeadingIncludingCellCorners() {
        double r=NativeStarterSite.landingRadius(41,23);
        assertEquals(Math.hypot(41,23)/2+4,r,1e-9);
        for(int angle=0;angle<360;angle++)for(int a:new int[]{-1,1})for(int b:new int[]{-1,1}) {
            double t=Math.toRadians(angle),x=a*20.5,z=b*11.5;
            assertTrue(NativeStarterSite.inside(x*Math.cos(t)-z*Math.sin(t),x*Math.sin(t)+z*Math.cos(t),r));
        }
    }
    @Test void rasterAndForecastUseTheSameBoundaryAtEveryHeading() {
        double r=NativeStarterSite.landingRadius(41,23);
        assertTrue(NativeStarterSite.padCell(24,14,r)); // outside ideal circle, covered boundary block
        assertFalse(NativeStarterSite.padCell(29,0,r));
        for(int angle=0;angle<360;angle++)for(int a:new int[]{-1,1})for(int b:new int[]{-1,1}){
            double t=Math.toRadians(angle),x=a*20.5-3.94,z=b*11.5;
            int dx=(int)Math.floor(.5+x*Math.cos(t)-z*Math.sin(t)-.38);
            int dz=(int)Math.floor(.5+x*Math.sin(t)+z*Math.cos(t));
            assertTrue(NativeStarterSite.padCell(dx,dz,r),"heading "+angle);
        }
    }
    @Test void wholeCircleAndEightBlockWaterBufferAreRequired() {
        var clear=NativeStarterSite.inspect(0,0,10,(x,z)->new NativeStarterSite.Column(130,false,false));
        assertTrue(clear.qualified());
        var wet=NativeStarterSite.inspect(0,0,10,(x,z)->new NativeStarterSite.Column(130,x==17&&z==0,false));
        assertFalse(wet.qualified());assertEquals(0,wet.wet());assertTrue(wet.nearWater()>0);
        var tree=NativeStarterSite.inspect(0,0,10,(x,z)->new NativeStarterSite.Column(130,false,x==9&&z==0));
        assertFalse(tree.qualified());
        assertTrue(NativeStarterSite.inspect(0,0,10,(x,z)->new NativeStarterSite.Column(x<0?130:133,false,false)).qualified());
        assertFalse(NativeStarterSite.inspect(0,0,10,(x,z)->new NativeStarterSite.Column(x<0?130:134,false,false)).qualified());
    }
    @Test void outwardSearchReaches320AndRanksDryThenLevelThenDistance() {
        var sites=NativeStarterSite.rank(0,0,10,320,(x,z)->new NativeStarterSite.Column(130,x<275,false));
        assertTrue(sites.getFirst().qualified());assertTrue(sites.getFirst().x()>280);
        assertTrue(sites.stream().allMatch(s->Math.hypot(s.x(),s.z())<=320));
        assertTrue(sites.size()<=1681);
        var flat=NativeStarterSite.rank(0,0,10,32,(x,z)->new NativeStarterSite.Column(130,false,false));
        assertEquals(0,flat.getFirst().x());assertEquals(0,flat.getFirst().z());
    }
    @Test void fallbackIsExplicitAndDeepWaterCannotBeFilled() {
        var site=NativeStarterSite.inspect(0,0,10,(x,z)->new NativeStarterSite.Column(120,true,false));
        assertFalse(site.qualified());assertTrue(site.wet()>0);
        assertTrue(NativeStarterSite.foundationAllowed(2));assertFalse(NativeStarterSite.foundationAllowed(3));
    }
    @Test void fallbackRankingIsLexicographicNotWeighted() {
        var dry=new NativeStarterSite.Circle(0,130,0,0,0,0,30,320L*320);
        var wet=new NativeStarterSite.Circle(0,130,0,1,1,0,0,0);
        assertTrue(NativeStarterSite.order().compare(dry,wet)<0);
        var level=new NativeStarterSite.Circle(0,130,0,0,0,0,0,256);
        var near=new NativeStarterSite.Circle(0,130,0,0,0,0,1,0);
        assertTrue(NativeStarterSite.order().compare(level,near)<0);
        assertTrue(NativeLanding.safeExit(.5,true,NativeLanding.Kind.PAD));
        assertFalse(NativeLanding.safeExit(.5,false,NativeLanding.Kind.PAD));
        assertFalse(NativeLanding.safeExit(5,true,NativeLanding.Kind.PAD));
    }
    @Test void bilingualBooksExplainTheCrossAndBoostedDescent() {
        var zh=String.join("\n",NativeStarterBooks.pages("zh_cn"));
        assertTrue(zh.contains("回到母星时对准停机坪中心的十字；船体整体在坪内即可放下舷梯"));
        assertTrue(String.join("\n",NativeStarterBooks.pages("zh_cn")).contains("Shift 下降，按住 Ctrl 更快"));
        assertTrue(String.join("\n",NativeStarterBooks.pages("en_us")).contains("Shift descends; hold Ctrl to go faster"));
        assertTrue(String.join("\n",NativeStarterBooks.pages("zh_cn")).contains("锁定目标后，只有目标星球会自动进入抵达；刚离开的星球不会。"));
        assertTrue(String.join("\n",NativeStarterBooks.pages("en_us")).contains("With a target locked, only that planet starts automatic arrival; the planet just left does not."));
    }
    @Test void padContainmentRejectsOneCornerOutside() {
        assertTrue(NativeStarterSite.containsRectangle(0,0,28,-20,-12,20,12));
        assertFalse(NativeStarterSite.containsRectangle(0,0,28,-20,-22,20,22));
        assertFalse(NativeStarterSite.containsRectangle(0,0,28,Double.NaN,0,1,1));
    }
}
