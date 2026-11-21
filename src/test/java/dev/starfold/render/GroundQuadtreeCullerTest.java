package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.GroundAir;
import dev.starfold.chart.GroundSpaceOptics;
import dev.starfold.chart.PlanetGap;
import dev.starfold.chart.PlanetOrbit;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector4d;
import org.junit.jupiter.api.Test;

import java.util.Random;

class GroundQuadtreeCullerTest {
    private static final Point ZERO = new Point(0, 0, 0), X = new Point(1, 0, 0), Y = new Point(0, 1, 0), Z = new Point(0, 0, 1);

    @Test
    void absentFlatChartFaceCannotAliasTheObserversNativeTerrain() {
        var shape = new PlanetShape(2048);
        var projection = new Matrix4f().setPerspective(1.2f, 1.5f, .05f, 20000);
        // Runtime ghost wall: TOP node 27 was drawn over BOTTOM's loaded ocean.
        var wall = new GroundQuadtreeCuller.Bounds(896, 33, 864, 928, 158, 864);
        for (Face observer : Face.VALUES) {
            for (double altitude : new double[] {120.494235, 160}) {
                var eye = new Point(914.154665, altitude, 926.777320);
                var gap = new PlanetGap(shape, observer, eye, 512, true, GroundAir.DEFAULT, true);
                var orbit = new PlanetOrbit(shape, new PlanetShape.Pose(observer, eye), eye, 512);
                var culler = new GroundQuadtreeCuller(new GroundQuadtreeCuller.Frame(
                        gap, orbit, ZERO, ZERO, X, Z, ZERO, X, Y, Z,
                        GroundSpaceOptics.lens(projection, altitude), projection, true));
                assertTrue(culler.visible(observer, wall), "the native face remains visible");
                for (Face source : Face.VALUES)
                    if (gap.chart.tile(source) == null)
                        assertFalse(culler.visible(source, wall),
                                "unmapped face must not reuse the observer's identity transform");
            }
        }
    }

    @Test
    void allFacesKeepTheirOrbitalProjectionAboveTheFlatChart() {
        var shape = new PlanetShape(2048);
        var eye = new Point(1024, 1392, 1024);
        var gap = new PlanetGap(shape, Face.BOTTOM, eye, 512, true, GroundAir.DEFAULT, true);
        var orbit = new PlanetOrbit(shape, new PlanetShape.Pose(Face.BOTTOM, eye), eye, 512);
        var projection = new Matrix4f().setPerspective(1.2f, 1.5f, .05f, 20000);
        var culler = new GroundQuadtreeCuller(new GroundQuadtreeCuller.Frame(gap, orbit,
                ZERO, ZERO, X, Z, ZERO, X, Z, Y.scale(-1),
                GroundSpaceOptics.lens(projection, eye.y()), projection, true));
        assertNull(gap.chart.tile(Face.TOP));
        for (Face face : Face.VALUES)
            assertTrue(culler.visible(face,
                    new GroundQuadtreeCuller.Bounds(896, 0, 896, 1152, 160, 1152)),
                    "orbital projection must not inherit flat-chart membership: " + face);
    }

    @Test
    void reusableBoundsRequireExactCameraProjectionAndRepairInputs() {
        var shape = new PlanetShape(2048);
        var eye = new Point(992.5, 1392, 992.5);
        var matrix = new Matrix4f().setPerspective(1.2f, 1.5f, .05f, 20000);
        java.util.function.BiFunction<Point, Matrix4f, GroundQuadtreeCuller> make = (p, projection) -> {
            var gap = new PlanetGap(shape, Face.TOP, p, 512, true, GroundAir.DEFAULT, true);
            var orbit = new PlanetOrbit(shape, new PlanetShape.Pose(Face.TOP, p), p, 512);
            return new GroundQuadtreeCuller(new GroundQuadtreeCuller.Frame(gap, orbit, ZERO,
                    ZERO, X, Z, ZERO, X, Y, Z,
                    GroundSpaceOptics.lens(projection, p.y()), projection, true));
        };
        var first = make.apply(eye, matrix);
        assertTrue(first.sameFrame(make.apply(eye, new Matrix4f(matrix))));
        assertFalse(first.sameFrame(make.apply(eye.add(new Point(.000001, 0, 0)), matrix)));
        assertFalse(first.sameFrame(make.apply(eye.add(new Point(0, .000001, 0)), matrix)));
        assertFalse(first.sameFrame(make.apply(eye, new Matrix4f(matrix).m00(matrix.m00() + .000001f))));
        assertFalse(first.sameFrame(null));
    }

    @Test
    void orbitalWindowEnclosesMovingCameraWitnessesAndRejectsUnprovedReuse() {
        var random = new Random(974332);
        var matrix = new Matrix4f().setPerspective(1.2f, 1.5f, .05f, 20000);
        var geometry = new GroundQuadtreeCuller.Geometry();
        for (int size : new int[] {272, 2048}) for (double altitude : new double[] {1025, 1392, 4096}) {
            var shape = new PlanetShape(size);
            var eye = new Point(size * .37, altitude, size * .73);
            java.util.function.BiFunction<Point, Double, GroundQuadtreeCuller> make = (p, yaw) -> {
                var gap = new PlanetGap(shape, Face.TOP, p, 512, true, GroundAir.DEFAULT, true);
                var orbit = new PlanetOrbit(shape, new PlanetShape.Pose(Face.TOP, p), p, 512);
                return new GroundQuadtreeCuller(new GroundQuadtreeCuller.Frame(gap, orbit, ZERO,
                        ZERO, X, Z, ZERO, new Point(Math.cos(yaw), 0, Math.sin(yaw)), Y,
                        new Point(-Math.sin(yaw), 0, Math.cos(yaw)),
                        GroundSpaceOptics.lens(matrix, p.y()), matrix, true), geometry);
            };
            var anchor = make.apply(eye, 0.0);
            assertTrue(anchor.withinOrbitalWindow(anchor));
            assertFalse(make.apply(eye.add(new Point(0, 2, 0)), 0.0).withinOrbitalWindow(anchor));
            assertFalse(make.apply(eye, .001).withinOrbitalWindow(anchor));
            assertFalse(anchor.orbitalWindowSource(-20, (int) altitude));
            for (int motion = 0; motion < 12; motion++) {
                var currentEye = eye.add(new Point(random.nextDouble() * .02 - .01,
                        random.nextDouble() * .02 - .01, random.nextDouble() * .02 - .01));
                double yaw = random.nextDouble() * .0004 - .0002;
                var current = make.apply(currentEye, yaw);
                assertTrue(current.withinOrbitalWindow(anchor));
                var orbit = new PlanetOrbit(shape, new PlanetShape.Pose(Face.TOP, currentEye), currentEye, 512);
                Point worldX = new Point(Math.cos(yaw), 0, Math.sin(yaw));
                Point worldZ = new Point(-Math.sin(yaw), 0, Math.cos(yaw));
                for (Face face : Face.VALUES) for (int sample = 0; sample < 20; sample++) {
                    var local = new Point(random.nextDouble() * size,
                            random.nextDouble() * 1218 - 1026, random.nextDouble() * size);
                    var r = orbit.point(face, local).sub(currentEye);
                    var actualView = worldX.scale(r.x()).add(Y.scale(r.y())).add(worldZ.scale(r.z()));
                    for (int depth = 0; depth < 5; depth++) {
                        int width = 256 >> depth;
                        int x = (int) local.x() / width * width, z = (int) local.z() / width * width;
                        var source = new GroundQuadtreeCuller.Bounds(x, -1030, z,
                                Math.min(size, x + width), 192, Math.min(size, z + width));
                        var result = anchor.evaluateOrbitalWindow(face, source);
                        assertNotNull(result.view());
                        assertTrue(result.view().contains(actualView), "moving camera escaped anchor enclosure");
                        if (inside(project(actualView, matrix, GroundSpaceOptics.lens(matrix, altitude))))
                            assertTrue(result.visible(), "window rejected a current-frame projection witness");
                    }
                }
            }
        }
    }

    private static GroundQuadtreeCuller surface(PlanetShape shape, Face observer, Point eye,
            double yaw, double pitch, Point offset, Matrix4f projection,
            GroundQuadtreeCuller.Geometry geometry, Face source) {
        var gap = new PlanetGap(shape, observer, eye, 512, true, GroundAir.DEFAULT, true);
        var orbit = new PlanetOrbit(shape, new PlanetShape.Pose(observer, eye), eye, 512);
        var tile = gap.chart.tile(source);
        Point origin = tile == null ? ZERO : tile.point(ZERO);
        Point u = tile == null ? X : tile.point(X).sub(origin);
        Point v = tile == null ? Z : tile.point(Z).sub(origin);
        Point right = new Point(Math.cos(yaw), 0, -Math.sin(yaw));
        Point back = new Point(Math.sin(yaw)*Math.cos(pitch), Math.sin(pitch), Math.cos(yaw)*Math.cos(pitch));
        Point up = back.cross(right);
        return new GroundQuadtreeCuller(new GroundQuadtreeCuller.Frame(gap, orbit, ZERO, origin, u, v,
                offset, new Point(right.x(), up.x(), back.x()), new Point(right.y(), up.y(), back.y()),
                new Point(right.z(), up.z(), back.z()), GroundSpaceOptics.lens(projection, eye.y()), projection, true), geometry);
    }

    @Test
    void boundedWalkingProjectionReusesSmallMovementAndKeepsBobbedCorners() throws Exception {
        var shape = new PlanetShape(2048);
        var projection = new Matrix4f().setPerspective(1.2f, 1.5f, .05f, 20000);
        var geometry = new GroundQuadtreeCuller.Geometry();
        var frameField = GroundQuadtreeCuller.class.getDeclaredField("frame");
        frameField.setAccessible(true);
        var eye = new Point(715, 90, 638);
        var start = surface(shape, Face.BOTTOM, eye, 0, 0, ZERO, projection, geometry, Face.BOTTOM);
        var moved = surface(shape, Face.BOTTOM, eye.add(new Point(.5, 0, 0)), 0, 0, ZERO,
                projection, geometry, Face.BOTTOM);
        var anchor = new GroundQuadtreeCuller((GroundQuadtreeCuller.Frame) frameField.get(start), geometry, true);
        var next = new GroundQuadtreeCuller((GroundQuadtreeCuller.Frame) frameField.get(moved), geometry, true);
        assertTrue(next.withinMovementWindow(anchor));
        assertFalse(moved.withinMovementWindow(anchor));
        var clip = GroundQuadtreeCuller.class.getDeclaredMethod("clipVisible", GroundQuadtreeCuller.Bounds.class);
        clip.setAccessible(true);
        var pose = new Matrix4f().translate(.12f, -.4f, 0).rotateZ(.018f).rotateX(.027f);
        assertTrue(GroundQuadtreeCuller.boundedWalkingPose(pose));
        assertFalse(GroundQuadtreeCuller.boundedWalkingPose(new Matrix4f().rotateX(.1f)));
        assertFalse(GroundQuadtreeCuller.boundedWalkingPose(new Matrix4f().translate(0, -1, 0)));
        var rendered = new Matrix4f(projection).mul(pose);
        int visible = 0, outside = 0;
        for (int depth : new int[] {16, 64, 256, 1024}) {
            for (int ix = -14; ix <= 14; ix++) for (int iy = -14; iy <= 14; iy++) {
                double x = ix * depth / 12., y = iy * depth / 12.;
                var p = rendered.transform(new org.joml.Vector4f((float) x, (float) y, -depth, 1));
                boolean selected = (boolean) clip.invoke(anchor,
                        new GroundQuadtreeCuller.Bounds(x, y, -depth, x, y, -depth));
                if (Math.abs(p.x) <= p.w && Math.abs(p.y) <= p.w && Math.abs(p.z) <= p.w) {
                    visible++;
                    assertTrue(selected);
                } else if (!selected) outside++;
            }
        }
        assertTrue(visible > 0 && outside > 0);
    }

    @Test
    void ordinaryClipFastPathMatchesIndependentProjectedCornerExtrema() throws Exception {
        var shape = new PlanetShape(2048);
        var projection = new Matrix4f().setPerspective(1.2f, 1.5f, .05f, 20000);
        var seed = surface(shape,Face.BOTTOM,new Point(715,64,638),.3,.2,ZERO,projection,
                new GroundQuadtreeCuller.Geometry(),Face.BOTTOM);
        var field = GroundQuadtreeCuller.class.getDeclaredField("frame");field.setAccessible(true);
        var f=(GroundQuadtreeCuller.Frame)field.get(seed);
        var clip=GroundQuadtreeCuller.class.getDeclaredMethod("clipVisible",GroundQuadtreeCuller.Bounds.class);
        clip.setAccessible(true);
        var random=new Random(45028);
        for(int sample=0;sample<2000;sample++) {
            var matrix=new Matrix4f(projection);
            if(sample%2==0) for(int col=0;col<4;col++) for(int row=0;row<4;row++)
                matrix.set(col,row,(float)(random.nextDouble()*4-2));
            var culler=new GroundQuadtreeCuller(new GroundQuadtreeCuller.Frame(f.gap(),f.orbit(),f.sourcePlot(),
                    f.flatOrigin(),f.flatU(),f.flatV(),f.viewOffset(),f.worldX(),f.worldY(),f.worldZ(),
                    new GroundSpaceOptics.Lens(0,1,1),matrix,false));
            double x=random.nextDouble()*20000-10000,y=random.nextDouble()*20000-10000,
                    z=random.nextDouble()*20000-10000,width=random.nextDouble()*2000;
            var box=new GroundQuadtreeCuller.Bounds(x,y,z,x+width,y+width,z+width);
            double[] lo={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY};
            double[] hi={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
            for(int i=0;i<8;i++) for(int row=0;row<4;row++) {
                double p=((i&1)==0?box.minX():box.maxX())*matrix.get(0,row)
                        +((i&2)==0?box.minY():box.maxY())*matrix.get(1,row)
                        +((i&4)==0?box.minZ():box.maxZ())*matrix.get(2,row)+matrix.get(3,row);
                lo[row]=Math.min(lo[row],p);hi[row]=Math.max(hi[row],p);
            }
            boolean expected=true;
            for(int axis=0;axis<3;axis++) for(double high:new double[]{hi[3]+hi[axis],hi[3]-lo[axis]})
                if(Double.isFinite(high)&&high < -1-256*Math.ulp((float)Math.abs(high))) expected=false;
            assertEquals(expected,clip.invoke(culler,box));
        }
    }

    @Test
    void closureExpansionMatchesIndependentCornerUnionAcrossLensWeights() throws Exception {
        var shape = new PlanetShape(2048);
        var projection = new Matrix4f().setPerspective(1.2f, 1.5f, .05f, 20000);
        var frameField = GroundQuadtreeCuller.class.getDeclaredField("frame");
        frameField.setAccessible(true);
        var clip = GroundQuadtreeCuller.class.getDeclaredMethod("clipVisible", GroundQuadtreeCuller.Bounds.class);
        clip.setAccessible(true);
        var random = new Random(450281);
        for (double altitude : new double[] {64, 160.62, 672, 1392}) {
            var withClosure = surface(shape, Face.BOTTOM, new Point(715,altitude,638), .3, .2,
                    ZERO, projection, new GroundQuadtreeCuller.Geometry(), Face.BOTTOM);
            var f = (GroundQuadtreeCuller.Frame) frameField.get(withClosure);
            var ordinary = new GroundQuadtreeCuller(new GroundQuadtreeCuller.Frame(f.gap(),f.orbit(),f.sourcePlot(),
                    f.flatOrigin(),f.flatU(),f.flatV(),f.viewOffset(),f.worldX(),f.worldY(),f.worldZ(),
                    f.lens(),f.projection(),false));
            for (int sample = 0; sample < 1000; sample++) {
                double x=random.nextDouble()*20000-10000,y=random.nextDouble()*20000-10000,
                        z=random.nextDouble()*20000-10000,w=random.nextDouble()*2000;
                var box = new GroundQuadtreeCuller.Bounds(x,y,z,x+w,y+w,z+w);
                double radius=0;
                for(int i=0;i<8;i++) radius=Math.max(radius,new Point((i&1)==0?box.minX():box.maxX(),
                        (i&2)==0?box.minY():box.maxY(),(i&4)==0?box.minZ():box.maxZ()).length());
                double scale=1+Math.max(.002,3e-6*radius);
                double[] lo={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY};
                double[] hi={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
                for(int i=0;i<8;i++) for(double factor:new double[]{1,scale}) {
                    double[] p={((i&1)==0?box.minX():box.maxX())*factor,
                            ((i&2)==0?box.minY():box.maxY())*factor,((i&4)==0?box.minZ():box.maxZ())*factor};
                    for(int j=0;j<3;j++){lo[j]=Math.min(lo[j],p[j]);hi[j]=Math.max(hi[j],p[j]);}
                }
                var expanded=new GroundQuadtreeCuller.Bounds(lo[0],lo[1],lo[2],hi[0],hi[1],hi[2]);
                assertEquals(clip.invoke(ordinary,expanded),clip.invoke(withClosure,box));
            }
        }
    }

    @Test
    void movementRepairCacheAcrossAnglesMatchesFreshEvaluationAndInvalidatesOnInputChanges() {
        var shape = new PlanetShape(2048);
        var projection = new Matrix4f().setPerspective(1.2f, 1.5f, .05f, 20000);
        for (double altitude : new double[] {64, 158.62, 160.62, 672}) {
            var shared = new GroundQuadtreeCuller.Geometry();
            Point eye = new Point(715, altitude, 638);
            for (Face source : Face.VALUES) {
                for (int sample = 0; sample < 18; sample++) {
                    double yaw = sample * .37, pitch = Math.sin(sample) * .6;
                    Point changedEye = sample < 12 ? eye : eye.add(new Point(.1, .02, 0));
                    var lens = sample < 15 ? projection : new Matrix4f(projection).m00(2);
                    Point bob = new Point(.02 * sample, -.03, .01);
                    var cached = surface(shape, Face.BOTTOM, changedEye, yaw, pitch, bob, lens, shared, source);
                    var fresh = surface(shape, Face.BOTTOM, changedEye, yaw, pitch, bob, lens,
                            new GroundQuadtreeCuller.Geometry(), source);
                    var bounds = new GroundQuadtreeCuller.Bounds(464, -220, 624, 720,
                            sample % 2 == 0 ? 192 : 430, 880);
                    assertEquals(fresh.evaluateMovementWindow(source, bounds), cached.evaluateMovementWindow(source, bounds),
                            "rotation reuse must preserve visibility, SSE and bounds, including changed heights/repair/lens");
                }
            }
        }
    }

    @Test
    void movementWindowRejectsUnprovedStateChangesAndCumulativeDrift() {
        var shape = new PlanetShape(2048); var eye = new Point(715, 160.62, 638);
        var projection = new Matrix4f().setPerspective(1.2f, 1.5f, .05f, 20000);
        var geometry = new GroundQuadtreeCuller.Geometry();
        var anchor = surface(shape, Face.BOTTOM, eye, 0, 0, ZERO, projection, geometry, Face.BOTTOM);
        assertTrue(anchor.withinMovementWindow(anchor));
        assertTrue(surface(shape, Face.BOTTOM, eye.add(new Point(2, 0, 0)), .01, 0, ZERO, projection, geometry, Face.BOTTOM).withinMovementWindow(anchor));
        assertFalse(surface(shape, Face.BOTTOM, eye.add(new Point(4.01, 0, 0)), 0, 0, ZERO, projection, geometry, Face.BOTTOM).withinMovementWindow(anchor));
        assertFalse(surface(shape, Face.BOTTOM, eye.add(new Point(0, 1.01, 0)), 0, 0, ZERO, projection, geometry, Face.BOTTOM).withinMovementWindow(anchor));
        assertFalse(surface(shape, Face.BOTTOM, eye.add(new Point(0, -1, 0)), 0, 0, ZERO, projection, geometry, Face.BOTTOM).withinMovementWindow(anchor));
        assertFalse(surface(shape, Face.TOP, eye, 0, 0, ZERO, projection, geometry, Face.BOTTOM).withinMovementWindow(anchor));
        assertFalse(surface(shape, Face.BOTTOM, eye, 0, 0, ZERO, new Matrix4f(projection).m00(2), geometry, Face.BOTTOM).withinMovementWindow(anchor));
        assertFalse(anchor.withinMovementWindow(null));
    }

    @Test
    void movementWindowEnclosesActualMovingShaderWitnessesIncludingRigidBlendAndPoles() {
        var random = new Random(841924); int checked=0, visible=0, rejected=0;
        var projection = new Matrix4f().setPerspective(1.2f, 1.5f, .05f, 20000);
        var geometry = new GroundQuadtreeCuller.Geometry();
        for (int size : new int[] {272, 2048}) for (double altitude : new double[] {160.01,160.62,256,672,1023.5,1392})
            for (Face observer : Face.VALUES) {
            var shape = new PlanetShape(size); var eye = new Point(size*.37, altitude, size*.63);
            double yaw=random.nextDouble()*6, pitch=random.nextDouble()*1.2-.6;
            Point currentEye=eye.add(new Point(.08, altitude>161 ? .03 : .001, -.06));
            var orbit=new PlanetOrbit(shape, new PlanetShape.Pose(observer,currentEye),currentEye,512);
            double cy=yaw+.012, cp=pitch-.008;
            Point right=new Point(Math.cos(cy),0,-Math.sin(cy));
            Point back=new Point(Math.sin(cy)*Math.cos(cp),Math.sin(cp),Math.cos(cy)*Math.cos(cp));
            Point up=back.cross(right), offset=new Point(.02,-.03,.01);
            for (Face source : Face.VALUES) {
                var anchor=surface(shape,observer,eye,yaw,pitch,ZERO,projection,geometry,source);
                var current=surface(shape,observer,currentEye,cy,cp,offset,projection,geometry,source);
                assertTrue(current.withinMovementWindow(anchor));
                for(int sample=0;sample<16;sample++) {
                    Point local=sample<4 ? new Point(eye.x()+sample*32, sample%2==0 ? altitude : -1030, eye.z())
                            : new Point(random.nextDouble()*size,random.nextDouble()*1222-1030,random.nextDouble()*size);
                    double blend=smooth(96,256,local.sub(currentEye).length());
                    Point warped=local.add(orbit.offset).scale(1-blend).add(orbit.point(source,local).scale(blend));
                    Point r=warped.sub(currentEye).sub(offset), view=new Point(right.dot(r),up.dot(r),back.dot(r));
                    boolean inClip=inside(project(view,projection,GroundSpaceOptics.lens(projection,currentEye.y())))
                            && shape.direction(source,local.x(),local.z()).dot(orbit.normal)>=orbit.minimumFacing;
                    for(int width : new int[] {16,64,256}) {
                        int x=(int)Math.floor(local.x()/width)*width,z=(int)Math.floor(local.z()/width)*width;
                        var result=anchor.evaluateMovementWindow(source,new GroundQuadtreeCuller.Bounds(x,-1034,z,x+width,Math.max(192,altitude+4),z+width));
                        if(result.view()!=null) {
                            assertTrue(result.view().contains(view),"moving shader point escaped uniform enclosure");
                            assertTrue(result.distance()<=Math.max(1,view.length()),"moving SSE distance overestimates a witness");checked++;
                        }
                        if(inClip) { assertTrue(result.visible(),"visible moving witness rejected");visible++; }
                        if(!result.visible())rejected++;
                    }
                }
            }
        }
        assertTrue(checked>1000);assertTrue(visible>100);assertTrue(rejected>100);
    }

    @Test
    void flatMovementWindowEnclosesActualAirAndRigidShaderWitnesses() {
        var random = new Random(718243);
        var projection = new Matrix4f().setPerspective(1.2f, 1.5f, .05f, 20000);
        int checked = 0, visible = 0;
        for (int size : new int[] {272, 2048}) for (double altitude : new double[] {2, 64, 128, 159.9})
            for (Face observer : Face.VALUES) {
                var shape = new PlanetShape(size);
                var eye = new Point(size*.5, altitude, size*.5);
                Point moved = eye.add(new Point(1.9, 0, -1.9));
                var gap = new PlanetGap(shape, observer, moved, 512, true, GroundAir.DEFAULT, true);
                double yaw = .4, pitch = .2;
                Point right = new Point(Math.cos(yaw), 0, -Math.sin(yaw));
                Point back = new Point(Math.sin(yaw)*Math.cos(pitch), Math.sin(pitch), Math.cos(yaw)*Math.cos(pitch));
                Point up = back.cross(right), offset = new Point(.02, -.03, .01);
                for (Face source : Face.VALUES) {
                    var geometry = new GroundQuadtreeCuller.Geometry();
                    var anchor = surface(shape, observer, eye, yaw, pitch, ZERO, projection, geometry, source);
                    var current = surface(shape, observer, moved, yaw, pitch, offset, projection, geometry, source);
                    if (!current.withinMovementWindow(anchor)) continue;
                    var tile = gap.chart.tile(source);
                    if (tile == null) continue;
                    for (int i=0; i<60; i++) {
                        var local = new Point(random.nextDouble()*size, random.nextDouble()*1200-1030, random.nextDouble()*size);
                        Point flat = tile.point(local), r = gap.flat(flat).sub(moved).sub(offset);
                        Point view = new Point(right.dot(r), up.dot(r), back.dot(r));
                        int x=(int)local.x()/16*16, z=(int)local.z()/16*16;
                        var result=anchor.evaluateMovementWindow(source,new GroundQuadtreeCuller.Bounds(x,-1034,z,x+16,192,z+16));
                        if (result.view()!=null) {
                            assertTrue(result.view().contains(view), "flat movement escaped enclosure");
                            assertTrue(result.distance()<=Math.max(1,view.length()));
                            checked++;
                        }
                        if (inside(project(view,projection,GroundSpaceOptics.lens(projection,altitude)))
                                && (gap.air.weight == 0 || gap.air.facing(flat)>=0)) {
                            assertTrue(result.visible()); visible++;
                        }
                    }
                }
            }
        assertTrue(checked>1000); assertTrue(visible>100);
        var shape=new PlanetShape(2048); var geometry=new GroundQuadtreeCuller.Geometry();
        var anchor=surface(shape,Face.TOP,new Point(1024,64,1024),0,0,ZERO,projection,geometry,Face.TOP);
        assertFalse(surface(shape,Face.TOP,new Point(1024,64.01,1024),0,0,ZERO,projection,geometry,Face.TOP)
                .withinMovementWindow(anchor), "partial air weight changes require a fresh window");
    }

    @Test
    void surfaceWindowRejectsCumulativeRotationAndEveryRepairOrProjectionChange() {
        var shape = new PlanetShape(2048); var eye = new Point(481.34, 157.62, 854.3);
        var projection = new Matrix4f().setPerspective(1.2f, 1.5f, .05f, 20000);
        var geometry = new GroundQuadtreeCuller.Geometry();
        var anchor = surface(shape, Face.BOTTOM, eye, 0, 0, ZERO, projection, geometry, Face.BOTTOM);
        assertTrue(anchor.withinSurfaceWindow(anchor));
        assertTrue(surface(shape, Face.BOTTOM, eye, .04, .01, new Point(.1, 0, 0), projection, geometry, Face.BOTTOM).withinSurfaceWindow(anchor));
        assertFalse(surface(shape, Face.BOTTOM, eye, .06, 0, ZERO, projection, geometry, Face.BOTTOM).withinSurfaceWindow(anchor));
        assertFalse(surface(shape, Face.BOTTOM, eye, 0, 0, new Point(.251, 0, 0), projection, geometry, Face.BOTTOM).withinSurfaceWindow(anchor));
        assertFalse(surface(shape, Face.BOTTOM, eye.add(new Point(.000001, 0, 0)), 0, 0, ZERO, projection, geometry, Face.BOTTOM).withinSurfaceWindow(anchor));
        assertFalse(surface(shape, Face.BOTTOM, eye, 0, 0, ZERO, new Matrix4f(projection).m00(2), geometry, Face.BOTTOM).withinSurfaceWindow(anchor));
        assertFalse(surface(shape, Face.TOP, eye, 0, 0, ZERO, projection, geometry, Face.BOTTOM).withinSurfaceWindow(anchor));
        var orbital = surface(shape, Face.BOTTOM, new Point(481, 900, 854), 0, 0, ZERO, projection, geometry, Face.BOTTOM);
        assertTrue(orbital.withinSurfaceWindow(orbital));
        assertTrue(surface(shape, Face.BOTTOM, new Point(481, 900, 854), .04, .01, ZERO, projection, geometry, Face.BOTTOM).withinSurfaceWindow(orbital));
        assertFalse(surface(shape, Face.BOTTOM, new Point(481, 900, 854), .06, 0, ZERO, projection, geometry, Face.BOTTOM).withinSurfaceWindow(orbital));
        assertFalse(anchor.withinSurfaceWindow(null));
    }

    @Test
    void surfaceWindowEnclosesActualRotatingRepairWitnessesOnEveryFaceAndHeight() {
        var random = new Random(87261); int bounded = 0, visible = 0, rejected = 0;
        var geometry = new GroundQuadtreeCuller.Geometry();
        var projection = new Matrix4f().setPerspective(1.2f, 1.5f, .05f, 20000);
        for (double altitude : new double[] {2, 25, 64, 128, 157.62, 160, 161, 256, 450, 672, 900, 1392, 4096, 5000}) for (Face observer : Face.VALUES) {
            var shape = new PlanetShape(2048); var eye = new Point(481.34, altitude, 854.3);
            var gap = new PlanetGap(shape, observer, eye, 512, true, GroundAir.DEFAULT, true);
            double yaw = random.nextDouble()*6, pitch = random.nextDouble()*2-1;
            for (Face source : Face.VALUES) {
                var anchor = surface(shape, observer, eye, yaw, pitch, ZERO, projection, geometry, source);
                double cy = yaw+.03, cp = pitch-.025; Point offset = new Point(.1, -.05, .02);
                assertTrue(surface(shape, observer, eye, cy, cp, offset, projection, geometry, source).withinSurfaceWindow(anchor));
                var tile = gap.chart.tile(source);
                if (altitude <= 160 && tile == null) continue;
                var orbit = new PlanetOrbit(shape, new PlanetShape.Pose(observer, eye), eye, 512);
                Point right = new Point(Math.cos(cy), 0, -Math.sin(cy));
                Point back = new Point(Math.sin(cy)*Math.cos(cp), Math.sin(cp), Math.cos(cy)*Math.cos(cp));
                Point up = back.cross(right);
                for (int sample = 0; sample < 12; sample++) {
                    Point local = new Point(random.nextDouble()*2048, random.nextDouble()*600-220, random.nextDouble()*2048);
                    Point warped;
                    double facing;
                    if (altitude <= 160) {
                        Point flat = tile.point(local);
                        warped = gap.flat(flat);
                        facing = gap.air.weight == 0 ? 1 : gap.air.facing(flat);
                    } else {
                        double t = smooth(96, 256, local.sub(eye).length());
                        warped = local.add(orbit.offset).scale(1-t).add(orbit.point(source, local).scale(t));
                        facing = shape.direction(source, local.x(), local.z()).dot(orbit.normal) - orbit.minimumFacing;
                    }
                    Point r = warped.sub(eye).sub(offset);
                    Point view = new Point(right.dot(r), up.dot(r), back.dot(r));
                    boolean inClip = inside(project(view, projection, GroundSpaceOptics.lens(projection, altitude)))
                            && facing >= 0;
                    for (int depth = 0; depth < 5; depth++) {
                        int width = 256 >> depth, x = (int)local.x()/width*width, z = (int)local.z()/width*width;
                        var result = anchor.evaluateSurfaceWindow(source,
                                new GroundQuadtreeCuller.Bounds(x, -220, z, x+width, 430, z+width));
                        if (result.view() != null) {
                            assertTrue(result.view().contains(view));
                            assertTrue(result.distance() <= Math.max(1, view.length()),
                                    "reused SSE distance must never exceed an actual witness radius");
                            bounded++;
                        }
                        if (inClip) { assertTrue(result.visible(), "current visible witness escaped surface window"); visible++; }
                        if (!result.visible()) rejected++;
                    }
                }
            }
        }
        assertTrue(bounded > 1000); assertTrue(visible > 10); assertTrue(rejected > 10);
    }

    @Test
    void highAltitudeRepairBoundsSurviveLargeRotationsWithoutAccumulatingPadding() {
        var shape = new PlanetShape(2048);
        var projection = new Matrix4f().setPerspective(1.2f, 1.5f, .05f, 20000);
        int bounded = 0;
        for (double altitude : new double[] {161, 256, 450, 672, 900, 4096}) {
            var eye = new Point(481.34, altitude, 854.3);
            for (Face observer : Face.VALUES) for (Face source : Face.VALUES) {
                var geometry = new GroundQuadtreeCuller.Geometry();
                var orbit = new PlanetOrbit(shape, new PlanetShape.Pose(observer, eye), eye, 512);
                var anchor = surface(shape, observer, eye, 0, 0, ZERO, projection, geometry, source);
                var bounds = new GroundQuadtreeCuller.Bounds(464, -220, 848, 480, 430, 864);
                var initial = anchor.viewBounds(source, bounds);
                double initialDistance = anchor.evaluateSurfaceWindow(source, bounds).distance();
                for (double yaw : new double[] {.04, .5, 1.5, 3, -2, .5, 0}) {
                    double pitch = .4;
                    var offset = new Point(.1, -.05, .02);
                    var rotated = surface(shape, observer, eye, yaw, pitch, offset, projection, geometry, source);
                    var box = rotated.viewBounds(source, bounds);
                    var withoutBob = surface(shape, observer, eye, yaw, pitch, ZERO, projection, geometry, source);
                    assertEquals(initialDistance, withoutBob.evaluateSurfaceWindow(source, bounds).distance(), 1e-9,
                            "rotation of a conservative box must not change SSE distance");
                    Point right = new Point(Math.cos(yaw), 0, -Math.sin(yaw));
                    Point back = new Point(Math.sin(yaw)*Math.cos(pitch), Math.sin(pitch), Math.cos(yaw)*Math.cos(pitch));
                    Point up = back.cross(right);
                    for (double height : new double[] {-220, 0, 430}) {
                        Point local = new Point(472, height, 856);
                        double t = smooth(96, 256, local.sub(eye).length());
                        Point repaired = local.add(orbit.offset).scale(1-t).add(orbit.point(source, local).scale(t));
                        Point r = repaired.sub(eye).sub(offset);
                        Point view = new Point(right.dot(r), up.dot(r), back.dot(r));
                        if (box != null) {
                            assertTrue(box.contains(view), "rotated cached repair must enclose actual terrain");
                            bounded++;
                        }
                    }
                }
                assertSame(initial, anchor.viewBounds(source, bounds), "returning to anchor must reuse the original box");
                var movedEye = eye.add(new Point(.001, 0, 0));
                var moved = surface(shape, observer, movedEye, .5, .4, ZERO, projection, geometry, source);
                var fresh = surface(shape, observer, movedEye, .5, .4, ZERO, projection,
                        new GroundQuadtreeCuller.Geometry(), source);
                assertEquals(fresh.viewBounds(source, bounds), moved.viewBounds(source, bounds),
                        "eye movement must discard the previous repair cache");
            }
        }
        assertTrue(bounded > 1000);
    }

    @Test
    void surfaceRotationPaddingDoesNotForceArtificiallyNearSseDistances() {
        var shape = new PlanetShape(2048); var eye = new Point(481.34, 128, 854.3);
        var projection = new Matrix4f().setPerspective(1.2f, 1.5f, .05f, 20000);
        var anchor = surface(shape, Face.BOTTOM, eye, 0, 0, ZERO, projection,
                new GroundQuadtreeCuller.Geometry(), Face.BOTTOM);
        var source = new GroundQuadtreeCuller.Bounds(1456, -20, 1456, 1472, 100, 1472);
        var exact = anchor.evaluate(Face.BOTTOM, source);
        var window = anchor.evaluateSurfaceWindow(Face.BOTTOM, source);
        assertNotNull(exact.view()); assertNotNull(window.view());
        assertTrue(window.distance() > exact.distance() - 3,
                "angular visibility guard must not lower SSE distance by hundreds of metres");
        assertTrue(window.distance() < exact.distance(), "bob and basis uncertainty remain conservative");
        assertTrue(window.distance() > window.view().distance() + 20);
    }

    @Test
    void sampledActualRepairAndProjectionRemainInsideEveryAncestorEnvelope() {
        var random = new Random(6234527);
        var geometry = new GroundQuadtreeCuller.Geometry();
        int projectedVisible = 0, bounded = 0, rejected = 0;
        for (int size : new int[] {272, 2048})
            for (double altitude : new double[] {2, 25, 64, 159, 160, 161, 450, 900, 1025, 1392, 4096, 5000}) {
                var shape = new PlanetShape(size);
                var eye = new Point(size * .37, altitude, size * .73);
                Face observer = Face.VALUES[random.nextInt(6)];
                var gap = new PlanetGap(shape, observer, eye, 512, true, GroundAir.DEFAULT, true);
                var orbit = new PlanetOrbit(shape, new PlanetShape.Pose(observer, eye), eye, 512);
                var projection = new Matrix4f().setPerspective((float) Math.toRadians(70), 16f / 9, .05f, 20000);
                var lens = GroundSpaceOptics.lens(projection, altitude);
                // Random yaw/pitch orthonormal view frame, including high downward views.
                double yaw = random.nextDouble() * Math.PI * 2, pitch = random.nextDouble() * Math.PI - Math.PI / 2;
                Point right = new Point(Math.cos(yaw), 0, -Math.sin(yaw));
                Point back = new Point(Math.sin(yaw) * Math.cos(pitch), Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
                Point up = back.cross(right);
                Point worldX = new Point(right.x(), up.x(), back.x());
                Point worldY = new Point(right.y(), up.y(), back.y());
                Point worldZ = new Point(right.z(), up.z(), back.z());
                for (int sample = 0; sample < 70; sample++) {
                    Face face = Face.VALUES[random.nextInt(6)];
                    var tile = gap.chart.tile(face);
                    if (altitude <= 160 && tile == null) continue;
                    Point origin = tile == null ? ZERO : tile.point(ZERO);
                    Point flatU = tile == null ? X : tile.point(X).sub(origin);
                    Point flatV = tile == null ? Z : tile.point(Z).sub(origin);
                    var culler = new GroundQuadtreeCuller(new GroundQuadtreeCuller.Frame(gap, orbit, ZERO,
                            origin, flatU, flatV, ZERO, worldX, worldY, worldZ, lens, projection, true), geometry);
                    var local = new Point(random.nextDouble() * size, random.nextDouble() * 650 - 220, random.nextDouble() * size);
                    Point warped;
                    double facing;
                    if (altitude <= 160) {
                        var flat = tile.point(local);
                        warped = gap.flat(flat);
                        facing = gap.air.weight > 0 ? gap.air.facing(flat) : 1;
                    } else {
                        double t = smooth(96, 256, local.sub(eye).length());
                        warped = local.add(orbit.offset).scale(1 - t).add(orbit.point(face, local).scale(t));
                        facing = shape.direction(face, local.x(), local.z()).dot(orbit.normal) - orbit.minimumFacing;
                    }
                    var r = warped.sub(eye);
                    var view = worldX.scale(r.x()).add(worldY.scale(r.y())).add(worldZ.scale(r.z()));
                    boolean inClip = inside(project(view, projection, lens)) && facing >= 0;
                    if (inClip) projectedVisible++;
                    for (int depth = 0; depth < 5; depth++) {
                        int width = 256 >> depth;
                        int x = (int) local.x() / width * width, z = (int) local.z() / width * width;
                        var source = new GroundQuadtreeCuller.Bounds(x, -220, z, Math.min(size, x + width), 430, Math.min(size, z + width));
                        var envelope = culler.viewBounds(face, source);
                        if (envelope != null) { assertTrue(envelope.contains(view), "actual repair escaped view envelope at altitude " + altitude); bounded++; }
                        if (inClip) assertTrue(culler.visible(face, source), "visible projected point culled at altitude " + altitude);
                        else if (!culler.visible(face, source)) rejected++;
                    }
                }
            }
        assertTrue(bounded > 1000, "test must exercise proved bounds rather than only uncertain fallback");
        assertTrue(projectedVisible > 10, "test must exercise positive projection witnesses");
        assertTrue(rejected > 10, "test must exercise actual rejection rather than an always-visible stub");
    }

    @Test
    void repairInteriorExtremaAndHeightRangeAreIncluded() {
        var shape = new PlanetShape(512);
        for (double altitude : new double[] {0, 159, 161, 450, 900, 1392, 5000}) {
            var eye = new Point(194.3, altitude, 375.7);
            var gap = new PlanetGap(shape, Face.TOP, eye, 512, true, GroundAir.DEFAULT, true);
            var orbit = new PlanetOrbit(shape, new PlanetShape.Pose(Face.TOP, eye), eye, 512);
            var matrix = new Matrix4f().setPerspective(1.2f, 1.5f, .05f, 20000);
            var culler = new GroundQuadtreeCuller(new GroundQuadtreeCuller.Frame(gap, orbit, ZERO,
                    ZERO, X, Z, ZERO, X, Y, Z, GroundSpaceOptics.lens(matrix, altitude), matrix, true));
            var source = new GroundQuadtreeCuller.Bounds(0, -400, 0, 512, 800, 512);
            var envelope = culler.viewBounds(Face.TOP, source);
            assertNotNull(envelope);
            for (int x = 0; x <= 512; x += 16) for (int z = 0; z <= 512; z += 16)
                for (double y : new double[] {-400, 13, 800}) {
                    var p = new Point(x, y, z);
                    var warped = altitude <= 160 ? gap.flat(p)
                            : p.add(orbit.offset).scale(1 - smooth(96, 256, p.sub(eye).length()))
                            .add(orbit.point(Face.TOP, p).scale(smooth(96, 256, p.sub(eye).length())));
                    assertTrue(envelope.contains(warped.sub(eye)));
                }
        }
    }

    @Test
    void missingNonFiniteAndNonRigidInputsAreConservative() {
        var bounds = new GroundQuadtreeCuller.Bounds(0, 0, 0, 16, 20, 16);
        assertTrue(new GroundQuadtreeCuller(null).visible(Face.TOP, bounds));
        var shape = new PlanetShape(256); var eye = new Point(128, 1000, 128);
        var gap = new PlanetGap(shape, Face.TOP, eye, 512, true, GroundAir.DEFAULT, true);
        var orbit = new PlanetOrbit(shape, new PlanetShape.Pose(Face.TOP, eye), eye, 512);
        var p = new Matrix4f().setPerspective(1.2f, 1.5f, .05f, 20000);
        var culler = new GroundQuadtreeCuller(new GroundQuadtreeCuller.Frame(gap, orbit, ZERO, ZERO, X, Z,
                ZERO, X.scale(2), Y, Z, GroundSpaceOptics.lens(p, 1000), p, true));
        assertNull(culler.viewBounds(Face.TOP, bounds)); assertTrue(culler.visible(Face.TOP, bounds));
        assertTrue(culler.visible(Face.TOP, new GroundQuadtreeCuller.Bounds(Double.NaN, 0, 0, 1, 1, 1)));
    }

    private static double smooth(double low, double high, double value) {
        double t = Math.max(0, Math.min(1, (value - low) / (high - low))); return t * t * (3 - 2 * t);
    }
    // Independent evaluation of cpNativeProject, using the supplied ProjMat and Lens uniforms.
    private static Vector4d project(Point view, Matrix4f projection, GroundSpaceOptics.Lens lens) {
        var ordinary = new Matrix4d(projection).transform(new Vector4d(view.x(), view.y(), view.z(), 1));
        if (lens.weight() == 0) return ordinary;
        double r = Math.max(1e-6, view.length()), rho2 = 1 / (lens.x() * lens.x()) + 1 / (lens.y() * lens.y());
        double cosine = (4 - rho2) / (4 + rho2), d = Math.max(r * (1 + cosine), r - view.z());
        var stereo = new Vector4d(2 * lens.x() * view.x() * r / d, 2 * lens.y() * view.y() * r / d,
                -projection.m22() * r + projection.m32(), r);
        return ordinary.mul(1 - lens.weight()).add(stereo.mul(lens.weight()));
    }
    private static boolean inside(Vector4d c) {
        return c.w > 0 && Math.abs(c.x) <= c.w && Math.abs(c.y) <= c.w && Math.abs(c.z) <= c.w;
    }
}
