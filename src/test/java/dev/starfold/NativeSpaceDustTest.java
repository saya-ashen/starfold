package dev.starfold;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeSpaceDustTest {
    @Test void streaksGrowToCapAndRemainThin(){assertEquals(0,NativeSpaceDust.length(0));assertEquals(9,NativeSpaceDust.length(150));assertEquals(18,NativeSpaceDust.length(300));assertEquals(48,NativeSpaceDust.length(800));assertEquals(72,NativeSpaceDust.length(1200),1e-9);assertEquals(144,NativeSpaceDust.length(10000),1e-9);assertEquals(.10,NativeSpaceDust.WIDTH);assertTrue(NativeSpaceDust.length(300)/NativeSpaceDust.WIDTH>100);}
    @Test void visibleRateAndWidthGrowFromSixty(){
        for(int v=-10;v<=60;v++){assertEquals(0,NativeSpaceDust.rate(v));assertEquals(0,NativeSpaceDust.alpha(v,2,5));assertEquals(0,NativeSpaceDust.width(v));}
        assertEquals(.10,NativeSpaceDust.width(150));assertEquals(.10,NativeSpaceDust.width(800));assertEquals(.15,NativeSpaceDust.width(1200),1e-9);
        assertEquals(8,NativeSpaceDust.rate(300),1e-9);assertEquals(28,NativeSpaceDust.rate(800));assertEquals(28,NativeSpaceDust.rate(10000));
        assertTrue(NativeSpaceDust.rate(150)>2&&NativeSpaceDust.rate(150)<4);
        assertEquals(.45,NativeSpaceDust.alpha(150,2,5),1e-6);assertEquals(.45+.45*150/650,NativeSpaceDust.alpha(300,2,5),1e-6);
        for(double speed:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}){assertEquals(0,NativeSpaceDust.rate(speed));assertEquals(0,NativeSpaceDust.width(speed));assertEquals(0,NativeSpaceDust.alpha(speed,2,5));}
    }
    @Test void centreFadeIsConfinedToCentralQuarterOfHalfWidth(){assertEquals(0,NativeSpaceDust.peripheral(0,0));assertEquals(0,NativeSpaceDust.peripheral(.125,0));assertEquals(.5,NativeSpaceDust.peripheral(.1875,0),1e-9);assertEquals(1,NativeSpaceDust.peripheral(.25,0));assertEquals(1,NativeSpaceDust.peripheral(0,-.25));assertEquals(1,NativeSpaceDust.peripheral(.5,0));}
    @Test void alphaFadesInAndOutAndSuppressesRest(){assertEquals(0,NativeSpaceDust.alpha(20,2,5));assertEquals(0,NativeSpaceDust.alpha(800,0,5));assertEquals(.45,NativeSpaceDust.alpha(800,1,5),1e-6);assertEquals(.9,NativeSpaceDust.alpha(800,2,5),1e-6);assertEquals(.45,NativeSpaceDust.alpha(800,4,5),1e-6);assertEquals(0,NativeSpaceDust.alpha(800,5,5));for(double t=-1;t<=6;t+=.1){float a=NativeSpaceDust.alpha(800,t,5);assertTrue(a>=0&&a<=.9f);}}
}
