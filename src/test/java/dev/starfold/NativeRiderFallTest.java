package dev.starfold;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeRiderFallTest {
    @Test void transportedDescentDoesNotAccumulateAtAnySpeed(){
        for(double speed:new double[]{-.1,-2,-20}){
            var rule=new NativeRiderFall();Object hull=new Object();
            rule.step(hull,4,0);
            for(int i=0;i<100;i++)assertEquals(0,rule.step(hull,4,speed),1e-9);
        }
    }
    @Test void relativeFallFromMastStillCounts(){
        var rule=new NativeRiderFall();Object hull=new Object();rule.step(hull,14,0);
        assertEquals(-10,rule.step(hull,4,-30),1e-9);
    }
    @Test void leavingHullImmediatelyRestoresVanillaEvenDuringAboardGrace(){
        var rule=new NativeRiderFall();Object hull=new Object();rule.step(hull,4,0);
        assertEquals(-2,rule.step(null,0,-2));
        assertEquals(-8,rule.step(null,0,-8));
    }
    @Test void boardingOrChangingHullNeverErasesAnExistingFall(){
        var rule=new NativeRiderFall();Object a=new Object(),b=new Object();
        assertEquals(-5,rule.step(a,4,-5));
        assertEquals(-3,rule.step(b,4,-3));
    }
    @Test void jumpIsMeasuredRelativeToDeck(){
        var rule=new NativeRiderFall();Object hull=new Object();rule.step(hull,4,0);
        assertEquals(1,rule.step(hull,5,-9));
        assertEquals(-1,rule.step(hull,4,-11));
    }
    @Test void roundoffDoesNotBecomeFallDistance(){
        var rule=new NativeRiderFall();Object hull=new Object();rule.step(hull,4,0);
        assertEquals(0,rule.step(hull,3.99999,-2));
    }
}
