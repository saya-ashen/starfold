package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import dev.starfold.CubeSurfaceTopology.Face;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeSpaceTest {
    static final double EPS=1e-6;
    static final Point ZERO=new Point(0,0,0);
    static final NativePlanetShape[] SHAPES={new NativePlanetShape(2048),new NativePlanetShape(1024)};
    static void point(Point expected,Point actual) {
        assertEquals(expected.x(),actual.x(),EPS);assertEquals(expected.y(),actual.y(),EPS);assertEquals(expected.z(),actual.z(),EPS);
    }
    static Vector3d vector(Point p){return new Vector3d(p.x(),p.y(),p.z());}
    static void vector(Vector3d expected,Vector3d actual) {
        assertEquals(expected.x,actual.x,EPS);assertEquals(expected.y,actual.y,EPS);assertEquals(expected.z,actual.z,EPS);
    }
    static void aim(Quaterniond expected,Quaterniond actual) {
        assertEquals(1,Math.abs(expected.dot(actual)),EPS);
        for(var axis:new Vector3d[]{new Vector3d(1,0,0),new Vector3d(0,1,0),new Vector3d(0,0,1)})
            vector(expected.transform(new Vector3d(axis)),actual.transform(new Vector3d(axis)));
    }
    static void motion(NativeSpace.Motion expected,NativeSpace.Motion actual) {
        point(expected.position(),actual.position());point(expected.velocity(),actual.velocity());
        aim(expected.orientation(),actual.orientation());vector(expected.spin(),actual.spin());
    }
    static GroundEphemeris.Model tilted() {
        var a=new GroundEphemeris.Spin(true,new Point(.6,.8,0),new Quaterniond().rotationXYZ(.2,-.4,.6),.7,48000,12);
        var b=new GroundEphemeris.Spin(true,new Point(0,.8,.6),new Quaterniond().rotationXYZ(-.3,.5,-.2),-.4,72000,12);
        return new GroundEphemeris.Model(new Point(100,-200,300),new Point(0,1,0),new Point(1,0,0),7000,30000,.3,12,a,b);
    }
    static NativeSpace.Motion chart(Point p) {
        return new NativeSpace.Motion(p,new Quaterniond().rotationXYZ(.3,-.5,.7),new Point(2,-3,4),new Vector3d(.01,-.02,.03));
    }
    static NativeSpace.Motion resting(Point p){return new NativeSpace.Motion(p,new Quaterniond(),ZERO,new Vector3d());}

    @Test void identityTranslatesFromTheReferenceCentre() {
        var model=tilted();assertEquals(new Point(-16384,8192,-16384),NativeSpace.level(model.at(0,0).center(),model,0));
        for(double tick:new double[]{0,1234,9876})for(var p:new Point[]{ZERO,new Point(123.25,-456.5,789.75),new Point(-100000,32768,100000)}) {
            point(p,NativeSpace.universe(NativeSpace.level(p,model,tick),model,tick));point(p,NativeSpace.level(NativeSpace.universe(p,model,tick),model,tick));
        }
        var a=new Point(12,34,56);var b=new Point(-1,-2,-3);
        point(a.sub(b),NativeSpace.level(a,model,0).sub(NativeSpace.level(b,model,0)));
    }
    @Test void regionFrameRoundTripsAndRotatesMotionWithoutShiftingVectors() {
        var model=tilted();for(double tick:new double[]{0,1234,9876})for(int planet=0;planet<2;planet++)for(var q:new Quaterniond[]{new Quaterniond(),new Quaterniond().rotationXYZ(.4,-.9,1.2)}){
            var frame=new NativeSpace.Frame(q,new Point(-123456,45123,-456789),planet);var m=chart(new Point(12,-34,56));
            motion(m,frame.universe(frame.level(m,model,tick),model,tick));motion(m,frame.level(frame.universe(m,model,tick),model,tick));
            point(m.position(),frame.universe(frame.level(m.position(),model,tick),model,tick));point(m.velocity(),frame.universeVector(frame.levelVector(m.velocity())));
            var rotated=q.transform(vector(m.velocity().sub(model.at(planet,tick).velocity())));vector(rotated,vector(frame.level(m,model,tick).velocity()));vector(q.transform(m.spin()),frame.level(m,model,tick).spin());
            aim(new Quaterniond(q).mul(m.orientation()),frame.level(m,model,tick).orientation());
            point(frame.levelVector(m.position().sub(model.at(planet,tick).center())),frame.level(m.position(),model,tick).sub(frame.shift()));
        }
    }
    @Test void emptyRegionKeepsEveryDepartureOrientationAndPlacesTheAnchorAtOrigin() {
        var model=tilted();for(int id=0;id<2;id++)for(var shape:SHAPES)for(var face:Face.values()){
            var pose=new NativeGroundTopology.Pose(face,new Point(.2*shape.size(),2049,.7*shape.size()));var chart=chart(pose.point());
            var universe=NativeSpace.out(model,id,shape,pose,chart,1234);var frame=NativeSpace.Frame.keeping(universe,chart.orientation(),model,id,1234);var placed=frame.level(universe,model,1234);
            point(ZERO,placed.velocity());point(new Point(NativeSpace.X,NativeSpace.Y,NativeSpace.Z),placed.position());aim(chart.orientation(),placed.orientation());motion(universe,frame.universe(placed,model,1234));
        }
    }
    @Test void framesValidateAndCopyTheirMutableRotation() {
        var q=new Quaterniond().rotationX(.4);var frame=new NativeSpace.Frame(q,ZERO,0);q.identity();frame.rotation().identity();aim(new Quaterniond().rotationX(.4),frame.rotation());
        assertThrows(IllegalArgumentException.class,()->new NativeSpace.Frame(new Quaterniond(0,0,0,0),ZERO,0));
        assertThrows(IllegalArgumentException.class,()->new NativeSpace.Frame(new Quaterniond(),new Point(Double.NaN,0,0),0));
        assertThrows(IllegalArgumentException.class,()->frame.levelOrientation(new Quaterniond(0,0,0,0)));
        assertThrows(IllegalArgumentException.class,()->frame.universeVector(new Point(0,Double.POSITIVE_INFINITY,0)));
        assertThrows(IllegalArgumentException.class,()->NativeSpace.Frame.keeping(chart(ZERO),new Quaterniond(0,0,0,0),tilted(),0,0));
        NativeSpace.Frame.IDENTITY.rotation().rotateX(1);aim(new Quaterniond(),NativeSpace.Frame.IDENTITY.rotation());
    }
    @Test void levelRestFollowsOnlyTheCentreNotTheSpin() {
        var model=tilted();for(int planet=0;planet<2;planet++){
            var frame=new NativeSpace.Frame(new Quaterniond().rotationXYZ(.4,-.9,1.2),NativeSpace.ORIGIN,planet);var still=resting(NativeSpace.ORIGIN.add(new Point(100,200,300)));Point offset=null;
            for(double tick:new double[]{0,1234,9876}){var universe=frame.universe(still,model,tick);point(model.at(planet,tick).velocity(),universe.velocity());vector(new Vector3d(),universe.spin());var relative=universe.position().sub(model.at(planet,tick).center());if(offset!=null)point(offset,relative);offset=relative;}
        }
    }
    @Test void changingReferencePreservesPositionsAndUniverseMotionAtEachEpoch() {
        var model=tilted();for(double tick:new double[]{0,1234,9876})for(int planet=0;planet<2;planet++){
            var old=new NativeSpace.Frame(new Quaterniond().rotationXYZ(.4,-.9,1.2),NativeSpace.ORIGIN,planet);var next=old.toward(1-planet,model,tick);
            assertEquals(1-planet,next.planet());aim(old.rotation(),next.rotation());
            for(var universe:new NativeSpace.Motion[]{chart(new Point(12,34,56)),chart(new Point(-9876,5432,1234))}){
                var level=old.level(universe,model,tick);point(level.position(),next.level(universe.position(),model,tick));
                motion(level,next.level(universe,model,tick));motion(universe,next.universe(level,model,tick));
            }
        }
    }
    @Test void shiftedMovesOnlyLevelPositions() {
        var model=tilted();var delta=new Point(123,-456,789);for(double tick:new double[]{0,1234,9876}){
            var old=new NativeSpace.Frame(new Quaterniond().rotationXYZ(.4,-.9,1.2),NativeSpace.ORIGIN,1);var next=old.shifted(delta);var universe=chart(new Point(12,34,56));var a=old.level(universe,model,tick);var b=next.level(universe,model,tick);
            point(a.position().add(delta),b.position());point(a.velocity(),b.velocity());aim(a.orientation(),b.orientation());vector(a.spin(),b.spin());assertEquals(old.planet(),next.planet());motion(universe,next.universe(b,model,tick));
        }
    }
    @Test void bodyAnchorIsAChartPointNotFeetAndAgreesWithOut() {
        NativePlanetShape.assign(1,SHAPES[1]);try{
            var model=tilted();for(var access:NativePlanetAccess.values())for(int id=0;id<(access==NativePlanetAccess.SHARED?2:1);id++)for(var face:Face.values()){
                var shape=access==NativePlanetAccess.SHARED?SHAPES[id]:NativePlanetShape.STANDARD;
                var pose=new NativeGroundTopology.Pose(face,new Point(.2*shape.size(),2049,.7*shape.size()));var anchor=access.bodyAnchor(id,pose);
                point(pose.point(),access.chart(anchor));point(access.feet(id,pose).add(new Point(0,.9,0)),anchor);
                var recovered=new NativeGroundTopology.Pose(face,access.chart(anchor));var out=NativeSpace.out(model,id,shape,recovered,chart(recovered.point()),1234);point(model.at(id,1234).world(shape.position(pose)),out.position());
                var arrival=NativeSpace.in(model,id,shape,out,1234);point(anchor,access.bodyAnchor(id,arrival.pose()));
            }
        }finally{NativePlanetShape.reset();}
    }
    @Test void remoteSquareUsesHalfOpenBoundaries() {
        assertEquals(-16384,NativeSpace.X);assertEquals(-16384,NativeSpace.Z);assertEquals(13312,NativeSpace.REACH);
        assertEquals(-29696,NativeSpace.X-NativeSpace.REACH);assertEquals(-3072,NativeSpace.X+NativeSpace.REACH);
        double left=NativeSpace.X-NativeSpace.REACH,right=NativeSpace.X+NativeSpace.REACH;
        double near=NativeSpace.Z-NativeSpace.REACH,far=NativeSpace.Z+NativeSpace.REACH;
        assertTrue(NativeSpace.holds(NativeSpace.X,NativeSpace.Z));assertTrue(NativeSpace.holds(left,near));
        assertTrue(NativeSpace.holds(Math.nextDown(right),Math.nextDown(far)));
        assertFalse(NativeSpace.holds(Math.nextDown(left),near));assertFalse(NativeSpace.holds(left,Math.nextDown(near)));
        assertFalse(NativeSpace.holds(right,near));assertFalse(NativeSpace.holds(left,far));assertFalse(NativeSpace.holds(0,0));
        assertFalse(NativeSpace.holds(Double.NaN,near));assertFalse(NativeSpace.holds(left,Double.POSITIVE_INFINITY));
    }
    @Test void noPossibleStorageChartOrHaloMeetsSpace() {
        // Every column/row is nonnegative. The largest face's halo extends one face width below its origin.
        double halo=NativePlanetShape.MAX;
        assertEquals(-2048,-halo);
        for(int id=0;id<NativePlanetStorage.MAX_PLANETS;id++) {
            double lowestX=NativePlanetStorage.column(id)*(double)NativePlanetStorage.PLANET_STRIDE-halo;
            double lowestZ=NativePlanetStorage.row(id)*(double)NativePlanetStorage.ROW_STRIDE-halo;
            assertTrue(NativeSpace.X+NativeSpace.REACH<lowestX);
            assertTrue(NativeSpace.Z+NativeSpace.REACH<lowestZ);
        }
        assertEquals(NativePlanetStorage.LAYOUT,"six_face_square_v2");
        assertEquals(0,NativePlanetStorage.find(-halo,-halo));
        for(double x:new double[]{NativeSpace.X-NativeSpace.REACH,NativeSpace.X,Math.nextDown((double)NativeSpace.X+NativeSpace.REACH)})
            for(double z:new double[]{NativeSpace.Z-NativeSpace.REACH,NativeSpace.Z,Math.nextDown((double)NativeSpace.Z+NativeSpace.REACH)})
                assertEquals(-1,NativePlanetStorage.find(x,z));
    }
    @Test void allSizesFacesHeightsAndMovingSpinningTicksRoundTrip() {
        for(var model:new GroundEphemeris.Model[]{GroundEphemeris.defaults(),tilted()})
            for(int id=0;id<model.count();id++)for(var shape:SHAPES)
                for(double tick:new double[]{0,1234.5,24000,98765})for(var face:Face.values())
                    for(double h:new double[]{1536,1600,2048,3000,4000})
                        for(var p:new Point[]{new Point(shape.half(),h,shape.half()),
                                new Point(.07*shape.size(),h,.96*shape.size()),new Point(shape.size()-.125,h,17.5)}) {
                            var pose=new NativeGroundTopology.Pose(face,p);var input=chart(p);
                            var universe=NativeSpace.out(model,id,shape,pose,input,tick);
                            var arrival=NativeSpace.in(model,id,shape,universe,tick);
                            assertEquals(face,arrival.pose().face());point(pose.point(),arrival.pose().point());
                            motion(input,arrival.motion());
                            motion(universe,NativeSpace.out(model,id,shape,arrival.pose(),arrival.motion(),tick));
                            // Independent position assertion prevents mutually inverse fixed-size functions passing.
                            point(model.at(id,tick).world(shape.position(pose)),universe.position());
                            assertEquals(h,NativeSpace.height(model,id,shape,universe.position(),tick),EPS);
                        }
    }
    @Test void restingBodyHasTheMovingPointsVelocityAndPlanetSpin() {
        var model=tilted();double tick=5432.5;
        for(int id=0;id<2;id++)for(var shape:SHAPES)for(var face:Face.values()) {
            var pose=new NativeGroundTopology.Pose(face,new Point(.3*shape.size(),1800,.6*shape.size()));
            var body=resting(pose.point());var out=NativeSpace.out(model,id,shape,pose,body,tick);
            var f=model.at(id,tick);var offset=out.position().sub(f.center());
            point(NativeSpace.drift(model,id,tick).add(f.angular().cross(offset)),out.velocity());
            vector(vector(f.angular()),out.spin());
            // Centred one-tick difference approximates the instantaneous analytic velocity to EPS.
            var before=NativeSpace.out(model,id,shape,pose,body,tick-.5).position();
            var after=NativeSpace.out(model,id,shape,pose,body,tick+.5).position();
            point(after.sub(before),out.velocity());
            point(model.at(id,tick+.5).center().sub(model.at(id,tick-.5).center()),NativeSpace.drift(model,id,tick));
        }
    }
    @Test void uprightBodyAndOneBlockPerTickAscentPointRadiallyUp() {
        var model=tilted();double tick=2468;
        for(int id=0;id<2;id++)for(var shape:SHAPES)for(var face:Face.values()) {
            var pose=new NativeGroundTopology.Pose(face,new Point(.1*shape.size(),1800,.83*shape.size()));
            var rest=NativeSpace.out(model,id,shape,pose,resting(pose.point()),tick);
            var rising=NativeSpace.out(model,id,shape,pose,
                    new NativeSpace.Motion(pose.point(),new Quaterniond(),new Point(0,1,0),new Vector3d()),tick);
            var up=NativeSpace.up(model,id,rest.position(),tick);
            assertEquals(1,up.length(),EPS);vector(up,vector(rising.velocity().sub(rest.velocity())));
            vector(up,rest.orientation().transform(new Vector3d(0,1,0)));
            // Horizontal chart units require the Jacobian rather than the orientation rotation.
            double dt=.0001;var delta=new Point(dt,0,0);
            var rightPose=new NativeGroundTopology.Pose(face,pose.point().add(delta));
            var leftPose=new NativeGroundTopology.Pose(face,pose.point().sub(delta));
            var right=NativeSpace.out(model,id,shape,rightPose,resting(rightPose.point()),tick);
            var left=NativeSpace.out(model,id,shape,leftPose,resting(leftPose.point()),tick);
            var moving=NativeSpace.out(model,id,shape,pose,
                    new NativeSpace.Motion(pose.point(),new Quaterniond(),new Point(1,0,0),new Vector3d()),tick);
            point(right.position().sub(left.position()).scale(1/(2*dt)),moving.velocity().sub(rest.velocity()));
        }
    }
    @Test void nearestUsesMovingCentresAndHeightUsesTheSuppliedShape() {
        var model=tilted();
        for(double tick:new double[]{0,1000.5,30000}) {
            var a=model.at(0,tick).center();var b=model.at(1,tick).center();var mid=a.add(b).scale(.5);
            assertEquals(0,NativeSpace.nearest(model,mid.add(a.sub(b).scale(.001)),tick));
            assertEquals(1,NativeSpace.nearest(model,mid.add(b.sub(a).scale(.001)),tick));
            for(int id=0;id<2;id++)for(var shape:SHAPES) {
                var f=model.at(id,tick);assertEquals(id,NativeSpace.nearest(model,f.center(),tick));
                assertEquals(-shape.radius(),NativeSpace.height(model,id,shape,f.center(),tick),EPS);
                for(double h:new double[]{-100,0,1536,4000}) {
                    var local=new Point(0,shape.radius()+h,0);var world=f.world(local);
                    assertEquals(NativeGroundTopology.locate(shape,local).point().y(),NativeSpace.height(model,id,shape,world,tick),EPS);
                }
            }
        }
        // Exact equidistance (identical centres), not a floating-point almost-midpoint.
        var extra=new GroundEphemeris.Orbit(model.at(0,0).center(),new Point(0,1,0),new Point(1,0,0),0,30000,0,0,model.a());
        var tied=new GroundEphemeris.Model(model.center(),model.axis(),model.radial(),model.radius(),model.period(),model.phase(),
                model.epoch(),model.a(),model.b(),model.sun(),true,java.util.List.of(extra));
        assertEquals(0,NativeSpace.nearest(tied,tied.at(0,0).center(),0));
        var single=GroundSolar.defaults();assertEquals(0,NativeSpace.nearest(single,single.at(1,0).center(),0));
        assertThrows(IllegalArgumentException.class,()->NativeSpace.drift(single,1,0));
        assertThrows(IllegalArgumentException.class,()->NativeSpace.up(model,0,model.at(0,0).center(),0));
    }
    @Test void convenienceOverloadsUseInstalledNonstandardShapeAndResetIt() {
        NativePlanetShape.assign(1,SHAPES[1]);
        try {
            var model=tilted();var pose=new NativeGroundTopology.Pose(Face.WEST,new Point(123,2000,789));var body=chart(pose.point());
            var explicit=NativeSpace.out(model,1,SHAPES[1],pose,body,1234);
            motion(explicit,NativeSpace.out(model,1,pose,body,1234));
            var arrival=NativeSpace.in(model,1,explicit,1234);assertEquals(pose.face(),arrival.pose().face());motion(body,arrival.motion());
            assertEquals(2000,NativeSpace.height(model,1,explicit.position(),1234),EPS);
        } finally {NativePlanetShape.reset();}
    }
    @Test void canonicalSeamsAndArbitraryUniverseMotionPreserveTheBody() {
        var model=tilted();double tick=6543.25;
        for(var shape:SHAPES)for(var face:Face.values()) {
            var pose=new NativeGroundTopology.Pose(face,new Point(0,1800,.3*shape.size()));
            var original=NativeSpace.out(model,0,shape,pose,chart(pose.point()),tick);
            var arrival=NativeSpace.in(model,0,shape,original,tick);
            motion(original,NativeSpace.out(model,0,shape,arrival.pose(),arrival.motion(),tick));
        }
        for(int id=0;id<2;id++)for(var shape:SHAPES) {
            var p=model.at(id,tick).world(new Point(800,2900,-600));
            var input=new NativeSpace.Motion(p,new Quaterniond().rotationXYZ(-.8,.2,1.1),new Point(-5,7,9),new Vector3d(.1,.2,-.3));
            var arrival=NativeSpace.in(model,id,shape,input,tick);
            motion(input,NativeSpace.out(model,id,shape,arrival.pose(),arrival.motion(),tick));
        }
    }
    @Test void ownershipTransitionsAreStrictAndHysteretic() {
        assertEquals(2048,NativeSpace.OUT);assertEquals(1536,NativeSpace.IN);assertTrue(NativeSpace.IN<NativeSpace.OUT);
        assertFalse(NativeSpace.leaves(NativeSpace.OUT));assertTrue(NativeSpace.leaves(Math.nextUp(NativeSpace.OUT)));
        assertFalse(NativeSpace.leaves(Math.nextDown(NativeSpace.OUT)));assertFalse(NativeSpace.enters(NativeSpace.IN));
        assertTrue(NativeSpace.enters(Math.nextDown(NativeSpace.IN)));assertFalse(NativeSpace.enters(Math.nextUp(NativeSpace.IN)));
        for(double h:new double[]{1536,1800,2048}){assertFalse(NativeSpace.leaves(h));assertFalse(NativeSpace.enters(h));}
        assertTrue(NativeSpace.enters(-100));assertTrue(NativeSpace.leaves(10000));
    }
    @Test void mutableJomlValuesAreDefensivelyCopied() {
        var q=new Quaterniond().rotationX(.4);var spin=new Vector3d(1,2,3);var m=new NativeSpace.Motion(ZERO,q,ZERO,spin);
        q.identity();spin.zero();aim(new Quaterniond().rotationX(.4),m.orientation());vector(new Vector3d(1,2,3),m.spin());
        m.orientation().identity();m.spin().zero();aim(new Quaterniond().rotationX(.4),m.orientation());vector(new Vector3d(1,2,3),m.spin());
    }
    @Test void malformedMotionsAndUnavailableChartsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new NativeSpace.Motion(new Point(Double.NaN,0,0),new Quaterniond(),ZERO,new Vector3d()));
        assertThrows(IllegalArgumentException.class,()->new NativeSpace.Motion(ZERO,new Quaterniond(0,0,0,0),ZERO,new Vector3d()));
        assertThrows(IllegalArgumentException.class,()->new NativeSpace.Motion(ZERO,new Quaterniond(),ZERO,new Vector3d(Double.POSITIVE_INFINITY,0,0)));
        var model=tilted();var shape=SHAPES[1];var pose=new NativeGroundTopology.Pose(Face.TOP,new Point(500,1800,500));
        assertThrows(IllegalArgumentException.class,()->NativeSpace.in(model,0,shape,chart(model.at(0,0).center()),0));
        assertThrows(IllegalArgumentException.class,()->NativeSpace.nearest(model,ZERO,-1));
        assertThrows(IllegalArgumentException.class,()->NativeSpace.out(model,2,shape,pose,chart(pose.point()),0));
        assertThrows(IllegalArgumentException.class,()->NativeSpace.out(model,0,shape,pose,chart(pose.point().add(new Point(1,0,0))),0));
        var outside=new NativeGroundTopology.Pose(Face.TOP,new Point(1500,1800,500));
        assertThrows(IllegalArgumentException.class,()->NativeSpace.out(model,0,shape,outside,chart(outside.point()),0));
    }
}
