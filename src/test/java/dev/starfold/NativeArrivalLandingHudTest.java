package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeArrivalLandingHudTest {
    @Test void roundedClearanceNeverPrintsNegativeZero(){for(double h:new double[]{-.49,-.001,-0.0,0,.49})assertEquals("0",NativeHudLayout.number(h));assertEquals("-1",NativeHudLayout.number(-1));assertEquals("—",NativeHudLayout.number(Double.NaN));}
    @Test void displayedArrivalCapsAreTheActualServerCaps(){for(double height:new double[]{0,NativeSpace.IN,2200,5000,10000}){var toward=new Point(1,0,0);var velocity=new Point(10000,0,0);var result=NativeSpaceServer.arrival(velocity,toward,velocity,height);assertEquals(NativeSpaceServer.arrivalLimit(height),result.x()*20,1e-8);var tangent=new Point(0,10000,0);var near=NativeSpaceServer.near(tangent,toward,tangent,height);assertEquals(NativeSpaceServer.nearLimit(height),near.y()*20,1e-8);}}
    @Test void widgetsOnlyUseSnapshotsAndOldChartTextIsGone()throws Exception{var root=Path.of("src/main/java/dev/starfold");var widgets=Files.readString(root.resolve("NativeFlightWidgets.java"));for(String name:new String[]{"NativeLandingClient","NativeWaypointClient","NativeSpaceClient","NativeHelmClient"})assertFalse(widgets.contains(name),name);assertTrue(widgets.contains("s.tier()>=3?-limit:Double.NaN"));assertTrue(widgets.contains("if(s.tier()<3||a==null)return"));assertTrue(widgets.contains("h<.5"));assertTrue(widgets.contains("home.distance()<=16"));assertTrue(widgets.contains("disabled(g"));assertTrue(widgets.contains("DARK"));assertFalse(Files.readString(root.resolve("NativeWaypointClient.java")).contains("renderHelm"));assertFalse(Files.readString(root.resolve("NativeLandingClient.java")).contains("drawString"));}
}
