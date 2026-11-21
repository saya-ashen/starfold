package dev.starfold.render;

import dev.starfold.chart.GroundSpaceOptics;
import dev.starfold.chart.PlanetGap;
import dev.starfold.chart.PlanetOrbit;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Pure CPU bounds for the ground vertex shader, at every altitude. No renderer or GL state is read.
 * Construct once per face per frame, then pass {@code b -> culler.visible(face, b)} to a selector.
 * Bounds must include all mesh heights, including skirts and fluid offsets. Uncertain inputs retain
 * the node. Bounds enclose every touched 16-block repair cell, not just rectangle corners.
 */
public final class GroundQuadtreeCuller {
    /** Face-local source bounds, or view-space bounds when returned by {@link #viewBounds}. */
    public record Bounds(double minX, double minY, double minZ,
                         double maxX, double maxY, double maxZ) {
        public boolean contains(Point p) {
            return p.x() >= minX && p.x() <= maxX && p.y() >= minY && p.y() <= maxY
                    && p.z() >= minZ && p.z() <= maxZ;
        }

        /** Conservative distance to this box from its coordinate origin. */
        public double distance() {
            double x = Math.max(0, Math.max(minX, -maxX));
            double y = Math.max(0, Math.max(minY, -maxY));
            double z = Math.max(0, Math.max(minZ, -maxZ));
            return Math.sqrt(x * x + y * y + z * z);
        }

        private Bounds expand(double d) {
            return new Bounds(minX-d, minY-d, minZ-d, maxX+d, maxY+d, maxZ+d);
        }
    }

    /**
     * Values of the corresponding CPNative uniforms. Axes map chart-relative components into view
     * space; flatOrigin/U/V map this emitting face into the observer's flat chart. sourcePlot is
     * the emitting storage-chart origin minus the observer storage-chart origin. projection is the
     * actual ProjMat (copied on construction). gap and orbit must describe the same eye and planet.
     * Lens comes from GroundSpaceOptics.lens. Setting closureWalls includes the shader's radial
     * depth bias in clip bounds; use true if the mesh can contain voxel side faces.
     */
    public record Frame(PlanetGap gap, PlanetOrbit orbit, Point sourcePlot,
                        Point flatOrigin, Point flatU, Point flatV, Point viewOffset,
                        Point worldX, Point worldY, Point worldZ,
                        GroundSpaceOptics.Lens lens, Matrix4fc projection, boolean closureWalls) {}

    /** Repair-grid geometry depends on the planet, not on camera motion. Renderer-owned. */
    public static final class Geometry {
        private final java.util.Map<GeometryKey, Bounds> cubes = new java.util.HashMap<>();
        private final RepairBounds[] repaired = new RepairBounds[Face.VALUES.length];
        private final MovementGeometry[] movement = new MovementGeometry[Face.VALUES.length];
    }
    private static final class RepairBounds {
        private final GroundQuadtreeCuller anchor;
        private final java.util.Map<Bounds, Bounds> boxes = new java.util.HashMap<>();
        private RepairBounds(GroundQuadtreeCuller anchor) { this.anchor = anchor; }
    }
    private static final class MovementGeometry {
        private GroundQuadtreeCuller anchor;
        private final java.util.Map<Bounds, MovementBounds> boxes = new java.util.HashMap<>();
        private MovementGeometry(GroundQuadtreeCuller anchor) { this.anchor = anchor; }
    }
    private record MovementBounds(Bounds box, Range weights) {
        private static final MovementBounds UNCERTAIN = new MovementBounds(null, null);
        private static final MovementBounds REJECTED = new MovementBounds(null, Range.of(0));
    }
    private GroundQuadtreeCuller repairAnchor;
    private Point repairX, repairY, repairZ, repairShift;
    private record GeometryKey(int size, Face face, int x0, int z0, int x1, int z1) {}

    private final Frame frame;
    private final Geometry geometry;
    private final Matrix4f projection;
    private final boolean frameValid, boundedBob;
    private static final double BOB_LINEAR_LIMIT = .06, BOB_TRANSLATION_LIMIT = .7;

    static boolean boundedWalkingPose(Matrix4fc pose) {
        if (pose.m03() != 0 || pose.m13() != 0 || pose.m23() != 0 || pose.m33() != 1) return false;
        double linear = 0;
        for (int c = 0; c < 3; c++) for (int r = 0; r < 3; r++) {
            double delta = pose.get(c, r) - (c == r ? 1 : 0);
            linear += delta * delta;
        }
        double x = pose.m30(), y = pose.m31(), z = pose.m32();
        return linear <= BOB_LINEAR_LIMIT * BOB_LINEAR_LIMIT
                && x*x + y*y + z*z <= BOB_TRANSLATION_LIMIT * BOB_TRANSLATION_LIMIT;
    }
    private final double basisError;
    private final Point orbitalX, orbitalY, orbitalZ, orbitalOffset;

    public GroundQuadtreeCuller(Frame frame) {
        this(frame, new Geometry());
    }

    public GroundQuadtreeCuller(Frame frame, Geometry geometry) {
        this(frame, geometry, false);
    }

    GroundQuadtreeCuller(Frame frame, Geometry geometry, boolean boundedBob) {
        this.frame = frame;
        this.boundedBob = boundedBob;
        this.geometry = java.util.Objects.requireNonNull(geometry);
        projection = frame == null || frame.projection() == null
                ? null : new Matrix4f(frame.projection());
        frameValid = validFrame();
        basisError = frameValid ? gramError() : Double.NaN;
        if (frameValid) {
            Point u = frame.orbit.basisU, n = frame.orbit.normal, v = frame.orbit.basisV;
            orbitalX = u.scale(frame.worldX.x()).add(n.scale(frame.worldY.x())).add(v.scale(frame.worldZ.x()));
            orbitalY = u.scale(frame.worldX.y()).add(n.scale(frame.worldY.y())).add(v.scale(frame.worldZ.y()));
            orbitalZ = u.scale(frame.worldX.z()).add(n.scale(frame.worldY.z())).add(v.scale(frame.worldZ.z()));
            Point c = frame.orbit.offset.sub(frame.viewOffset)
                    .add(new Point(0, -frame.orbit.shape.radius() - frame.orbit.eye.y(), 0));
            orbitalOffset = frame.worldX.scale(c.x()).add(frame.worldY.scale(c.y())).add(frame.worldZ.scale(c.z()));
        } else orbitalX = orbitalY = orbitalZ = orbitalOffset = null;
    }

    /** Exact uniform identity: stationary frames can reuse bounds, never moving-camera estimates. */
    public boolean sameFrame(GroundQuadtreeCuller other) {
        return sameRepairFrame(other) && frame.viewOffset.equals(other.frame.viewOffset)
                && frame.worldX.equals(other.frame.worldX) && frame.worldY.equals(other.frame.worldY)
                && frame.worldZ.equals(other.frame.worldZ);
    }

    private boolean sameRepairFrame(GroundQuadtreeCuller other) {
        if (other == null || frame == null || other.frame == null || projection == null
                || frame.gap == null || other.frame.gap == null
                || frame.orbit == null || other.frame.orbit == null
                || boundedBob != other.boundedBob || !projection.equals(other.projection)) return false;
        var a = frame; var b = other.frame;
        return a.closureWalls == b.closureWalls && java.util.Objects.equals(a.lens, b.lens)
                && a.sourcePlot.equals(b.sourcePlot) && a.flatOrigin.equals(b.flatOrigin)
                && a.flatU.equals(b.flatU) && a.flatV.equals(b.flatV)
                && a.orbit.shape.equals(b.orbit.shape) && a.orbit.eye.equals(b.orbit.eye)
                && a.orbit.pose.face() == b.orbit.pose.face()
                && a.orbit.normal.equals(b.orbit.normal)
                && a.orbit.basisU.equals(b.orbit.basisU) && a.orbit.basisV.equals(b.orbit.basisV)
                && a.orbit.offset.equals(b.orbit.offset) && a.orbit.weight == b.orbit.weight
                && a.orbit.minimumFacing == b.orbit.minimumFacing && a.orbit.clip == b.orbit.clip
                && a.gap.shape.equals(b.gap.shape) && a.gap.eye.equals(b.gap.eye)
                && a.gap.corner.equals(b.gap.corner) && a.gap.sx == b.gap.sx && a.gap.sz == b.gap.sz
                && a.gap.distance == b.gap.distance && a.gap.clip == b.gap.clip
                && a.gap.strength == b.gap.strength && a.gap.outer == b.gap.outer
                && a.gap.air.eye.equals(b.gap.air.eye) && a.gap.air.weight == b.gap.air.weight
                && a.gap.air.normal.equals(b.gap.air.normal)
                && a.gap.air.basisU.equals(b.gap.air.basisU)
                && a.gap.air.basisV.equals(b.gap.air.basisV)
                && a.gap.air.offset.equals(b.gap.air.offset);
    }

    /**
     * A fixed anchor permits about three degrees of rotation (Frobenius norm <= .075),
     * with at most .25 m of view bob. Repair, eye, lens and projection must stay identical.
     * This applies at every altitude: repair precedes the final camera rotation.
     * Never compare against the previous frame: cumulative drift must leave the window.
     */
    public boolean withinSurfaceWindow(GroundQuadtreeCuller anchor) {
        return anchor != null && frameValid && anchor.frameValid
                && sameRepairFrame(anchor)
                && squaredDelta(frame.viewOffset, anchor.frame.viewOffset) <= .25 * .25
                && squaredDelta(frame.worldX, anchor.frame.worldX)
                        + squaredDelta(frame.worldY, anchor.frame.worldY)
                        + squaredDelta(frame.worldZ, anchor.frame.worldZ) <= .075 * .075;
    }

    // A window of shader uniforms, not an assumed translation of the repaired planet.
    // Runtime membership checks every interval used below, including vertical/lens changes.
    private double normalWindow() { return 4 / frame.orbit.shape.half(); }
    private double basisWindow() { return 8 / frame.orbit.shape.half(); }
    private Range movementWeight() {
        double y = frame.orbit.eye.y();
        return new Range(dev.starfold.chart.GroundOrbit.weight(Math.max(Math.nextUp(160.0), y-1)),
                dev.starfold.chart.GroundOrbit.weight(y+1));
    }

    public boolean withinMovementWindow(GroundQuadtreeCuller anchor) {
        if (anchor == null || !frameValid || !anchor.frameValid
                || (frame.orbit.eye.y() <= 160) != (anchor.frame.orbit.eye.y() <= 160)
                || frame.orbit.pose.face() != anchor.frame.orbit.pose.face()
                || !frame.orbit.shape.equals(anchor.frame.orbit.shape)
                || frame.closureWalls != anchor.frame.closureWalls
                || boundedBob != anchor.boundedBob || !projection.equals(anchor.projection)
                || frame.lens.x() != anchor.frame.lens.x() || frame.lens.y() != anchor.frame.lens.y()
                || !frame.sourcePlot.equals(anchor.frame.sourcePlot)
                || !frame.flatOrigin.equals(anchor.frame.flatOrigin)
                || !frame.flatU.equals(anchor.frame.flatU) || !frame.flatV.equals(anchor.frame.flatV)
                || frame.lens.weight() != frame.orbit.weight
                || Math.abs(frame.orbit.normal.dot(frame.orbit.basisU)) > 1e-8
                || Math.abs(frame.orbit.normal.dot(frame.orbit.basisV)) > 1e-8) return false;
        if (frame.orbit.eye.y() <= 160) {
            var a = anchor.frame.gap.air; var b = frame.gap.air;
            if (a.weight != b.weight || frame.lens.weight() != anchor.frame.lens.weight()
                    || (a.weight != 1 && (anchor.frame.gap.strength != 0 || frame.gap.strength != 0))) return false;
            return Math.abs(b.eye.x()-a.eye.x()) <= 4 && Math.abs(b.eye.z()-a.eye.z()) <= 4
                    && Math.abs(b.eye.y()-a.eye.y()) <= 1
                    && maxAbs(b.normal.sub(a.normal)) <= anchor.normalWindow()
                    && maxAbs(b.basisU.sub(a.basisU)) <= anchor.basisWindow()
                    && maxAbs(b.basisV.sub(a.basisV)) <= anchor.basisWindow()
                    && maxAbs(b.offset.sub(a.offset)) <= .5
                    && Math.abs(b.normal.dot(b.basisU)) <= 1e-8
                    && Math.abs(b.normal.dot(b.basisV)) <= 1e-8
                    && cameraWithinWindow(anchor);
        }
        var a = anchor.frame.orbit; var b = frame.orbit;
        Range weight = anchor.movementWeight();
        return Math.abs(b.eye.x()-a.eye.x()) <= 4 && Math.abs(b.eye.z()-a.eye.z()) <= 4
                && Math.abs(b.eye.y()-a.eye.y()) <= 1
                && b.weight >= weight.low && b.weight <= weight.high
                && maxAbs(b.normal.sub(a.normal)) <= anchor.normalWindow()
                && maxAbs(b.basisU.sub(a.basisU)) <= anchor.basisWindow()
                && maxAbs(b.basisV.sub(a.basisV)) <= anchor.basisWindow()
                && maxAbs(b.offset.sub(a.offset)) <= .5
                && cameraWithinWindow(anchor);
    }

    private boolean cameraWithinWindow(GroundQuadtreeCuller anchor) {
        return maxAbs(frame.viewOffset.sub(anchor.frame.viewOffset)) <= .25
                && squaredDelta(frame.worldX, anchor.frame.worldX)
                        + squaredDelta(frame.worldY, anchor.frame.worldY)
                        + squaredDelta(frame.worldZ, anchor.frame.worldZ) <= .075*.075;
    }

    /** Camera rotation never changes the repair intervals; project them into each new angle window. */
    public Evaluation evaluateMovementWindow(Face face, Bounds source) {
        if (!valid(face, source)) return Evaluation.UNCERTAIN;
        var cached = geometry.movement[face.ordinal()];
        if (cached == null || (cached.anchor != this && !sameRepairFrame(cached.anchor)))
            geometry.movement[face.ordinal()] = cached = new MovementGeometry(this);
        else cached.anchor = this;
        var repaired = cached.boxes.get(source);
        if (repaired == null) {
            repaired = movementBounds(face, source);
            cached.boxes.put(source, repaired);
        }
        if (repaired.box == null)
            return repaired.weights == null ? Evaluation.UNCERTAIN : Evaluation.REJECTED;
        return movementEvaluation(repaired.box, repaired.weights);
    }

    /** Interval evaluation of the repair shader over an entire admitted movement window. */
    private MovementBounds movementBounds(Face face, Bounds source) {
        if (frame.orbit.eye.y() <= 160) return flatMovementBounds(face, source);
        Bounds s = source.expand(4);
        Bounds cube = orbitalCube(face, s);
        Range radius = new Range(cube.distance(), maxRadius(cube));
        if (!(radius.low > 0)) return MovementBounds.UNCERTAIN;
        Range[] direction = {new Range(cube.minX, cube.maxX).divide(radius),
                new Range(cube.minY, cube.maxY).divide(radius),
                new Range(cube.minZ, cube.maxZ).divide(radius)};
        var o = frame.orbit;
        Range[] n = intervals(o.normal, normalWindow());
        Range[] u = intervals(o.basisU, basisWindow()), v = intervals(o.basisV, basisWindow());
        Range c = dot(direction, n), k = movementWeight();
        k = new Range(Math.max(0, k.low-2e-6), Math.min(1, k.high+2e-6));
        // Only reject when even the most permissive facing threshold excludes the whole grid.
        double minimumFacing = -dev.starfold.chart.GroundGap.smooth(.1, .5, k.high);
        if (c.high < minimumFacing - 1e-5) return MovementBounds.REJECTED;
        Range oneMinusC = Range.of(1).subtract(c);
        Range d = Range.of(1).add(c).add(k.multiply(k).multiply(oneMinusC)).maximum(Range.of(1e-9));
        Range h = new Range(s.minY, s.maxY);
        Range scale = Range.of(o.shape.radius()).add(k.multiply(h)).scale(2).divide(d);
        Range tx = dot(direction, u).subtract(c.multiply(around(0, 1e-6)));
        Range tz = dot(direction, v).subtract(c.multiply(around(0, 1e-6)));
        Range rx = tx.multiply(scale).add(around(o.offset.x(), .5));
        Range ry = h.subtract(k.multiply(oneMinusC).multiply(scale))
                .add(around(o.offset.y()-o.eye.y(), 1.5));
        Range rz = tz.multiply(scale).add(around(o.offset.z(), .5));
        Range[] repaired = {rx, ry, rz};
        // The shader blends rigid and orbital positions over 96..256 m. Their union
        // contains every blend weight, including changes caused by the moving eye.
        Range[] rigid = {new Range(s.minX, s.maxX).add(around(o.offset.x()-o.eye.x(), 4.5)),
                h.add(around(o.offset.y()-o.eye.y(), 1.5)),
                new Range(s.minZ, s.maxZ).add(around(o.offset.z()-o.eye.z(), 4.5))};
        Bounds relative = translated(s, frame.sourcePlot.sub(o.eye)).expand(4.2);
        if (relative.distance() < 256) for (int i=0; i<3; i++) repaired[i] = repaired[i].union(rigid[i]);
        return movementBox(repaired, k);
    }

    private MovementBounds flatMovementBounds(Face face, Bounds source) {
        if (frame.gap.chart.tile(face) == null) return MovementBounds.REJECTED;
        Accumulator flat = new Accumulator();
        corners(source.expand(4), p -> flat.add(frame.flatOrigin.add(frame.flatU.scale(p.x()))
                .add(frame.flatV.scale(p.z())).add(new Point(0,p.y(),0))));
        Bounds s = flat.box();
        var air = frame.gap.air;
        Range[] rigid = {new Range(s.minX,s.maxX).subtract(around(air.eye.x(),4)),
                new Range(s.minY,s.maxY).subtract(around(air.eye.y(),1)),
                new Range(s.minZ,s.maxZ).subtract(around(air.eye.z(),4))};
        if (air.weight == 0) return movementBox(rigid, Range.of(frame.lens.weight()));
        Bounds cube = orbitalCube(Face.TOP, s);
        Range radius = new Range(cube.distance(),maxRadius(cube));
        if (!(radius.low>0)) return MovementBounds.UNCERTAIN;
        Range[] direction = {new Range(cube.minX,cube.maxX).divide(radius),
                new Range(cube.minY,cube.maxY).divide(radius),new Range(cube.minZ,cube.maxZ).divide(radius)};
        Range c = dot(direction,intervals(air.normal,normalWindow()));
        if (c.high < -1e-5) return MovementBounds.REJECTED;
        Range scale = Range.of(2*air.shape.radius()).divide(Range.of(1).add(c).maximum(Range.of(1e-6)));
        Range x = dot(direction,intervals(air.basisU,basisWindow())).subtract(around(0,1e-6))
                .multiply(scale).add(around(air.offset.x(),.5));
        Range z = dot(direction,intervals(air.basisV,basisWindow())).subtract(around(0,1e-6))
                .multiply(scale).add(around(air.offset.z(),.5));
        Range[] repaired = {x,rigid[1],z};
        if (air.weight != 1) for(int i=0;i<3;i++)
            repaired[i]=rigid[i].scale(1-air.weight).add(repaired[i].scale(air.weight));
        return movementBox(repaired,Range.of(frame.lens.weight()));
    }

    private static MovementBounds movementBox(Range[] repaired, Range weights) {
        return new MovementBounds(new Bounds(repaired[0].low, repaired[1].low, repaired[2].low,
                repaired[0].high, repaired[1].high, repaired[2].high), weights);
    }

    private Evaluation movementEvaluation(Bounds repaired, Range weights) {
        Point off = frame.viewOffset;
        double x0 = repaired.minX - (off.x() + .25 + 1e-6);
        double y0 = repaired.minY - (off.y() + .25 + 1e-6);
        double z0 = repaired.minZ - (off.z() + .25 + 1e-6);
        double x1 = repaired.maxX - (off.x() - .25 - 1e-6);
        double y1 = repaired.maxY - (off.y() - .25 - 1e-6);
        double z1 = repaired.maxZ - (off.z() - .25 - 1e-6);
        Point a = frame.worldX, b = frame.worldY, c = frame.worldZ;
        Bounds view = new Bounds(
                lower(x0,x1,a.x()) + lower(y0,y1,b.x()) + lower(z0,z1,c.x()),
                lower(x0,x1,a.y()) + lower(y0,y1,b.y()) + lower(z0,z1,c.y()),
                lower(x0,x1,a.z()) + lower(y0,y1,b.z()) + lower(z0,z1,c.z()),
                upper(x0,x1,a.x()) + upper(y0,y1,b.x()) + upper(z0,z1,c.x()),
                upper(x0,x1,a.y()) + upper(y0,y1,b.y()) + upper(z0,z1,c.y()),
                upper(x0,x1,a.z()) + upper(y0,y1,b.z()) + upper(z0,z1,c.z()));
        if (!finite(view) || maxAbs(view) > 1e12) return Evaluation.UNCERTAIN;
        // Rotation: ||delta A r|| <= .075 ||r||. Float shader arithmetic and interval
        // endpoint rounding receive an additional radius-scaled allowance.
        double padding = .08*maxRadius(view)/Math.sqrt(1-basisError) + 4
                + 512*Math.ulp((float)maxAbs(view));
        double distance = Math.max(1, view.distance()/Math.sqrt(1+basisError)*Math.sqrt(1-1e-4)-4);
        view = view.expand(padding);
        return new Evaluation(clipVisible(view, weights), view, distance);
    }

    private static double lower(double lo, double hi, double scale) { return (scale >= 0 ? lo : hi) * scale; }
    private static double upper(double lo, double hi, double scale) { return (scale >= 0 ? hi : lo) * scale; }

    private static Range around(double center, double error) { return new Range(center-error-1e-6, center+error+1e-6); }
    private static Range[] intervals(Point p, double error) {
        return new Range[] {around(p.x(), error), around(p.y(), error), around(p.z(), error)};
    }
    private static Range dot(Range[] a, Range[] b) {
        return a[0].multiply(b[0]).add(a[1].multiply(b[1])).add(a[2].multiply(b[2]));
    }

    private static double squaredDelta(Point a, Point b) {
        double x = a.x()-b.x(), y = a.y()-b.y(), z = a.z()-b.z();
        return x*x + y*y + z*z;
    }

    /**
     * ||delta A r|| <= .075 ||r|| and ||r|| <= ||anchor view|| / sqrt(1-basisError).
     * Use .08 plus two metres for bob and float rounding. The expanded anchor bounds
     * enclose every camera admitted above, so visibility and SSE remain conservative.
     */
    public Evaluation evaluateSurfaceWindow(Face face, Bounds source) {
        if (!valid(face, source)) return Evaluation.UNCERTAIN;
        if (facingRejected(face, source)) return Evaluation.REJECTED;
        Bounds view = viewBounds(face, source);
        if (view == null) return Evaluation.UNCERTAIN;
        // Rotation preserves radius. Both admitted bases have Gram error < 1e-4;
        // only view bob can shorten radius, by at most .25 m before transformation.
        // Keep SSE independent of the angular visibility padding, which otherwise
        // makes distant boxes appear much nearer and triggers unnecessary refinement.
        double radius = view.distance() / Math.sqrt(1+basisError);
        if (frame.orbit.eye.y() > 160) {
            RepairBounds cache = geometry.repaired[face.ordinal()];
            Bounds original = cache.boxes.get(source);
            // A rotated AABB can grow; that cannot make the terrain itself move closer.
            radius = original.distance() / Math.sqrt(1+cache.anchor.basisError)
                    - frame.viewOffset.sub(cache.anchor.frame.viewOffset).length();
        }
        double distance = Math.max(1, (radius - .25) * Math.sqrt(1-1e-4) - 2);
        view = view.expand(.08 * maxRadius(view) / Math.sqrt(1-basisError) + 2);
        return new Evaluation(clipVisible(view), view, distance);
    }

    /**
     * An eight-metre enclosure, not a stale-camera approximation. At full orbit the view transform is
     * affine in the repaired unit direction. Bound its change over every compact source height by
     * |delta offset| + (R + 4096) * |delta axis|. Require at most 2 m movement, reserving the
     * remaining enclosure for float-uniform rounding. Projection/lens changes never reuse it.
     */
    public boolean withinOrbitalWindow(GroundQuadtreeCuller anchor) {
        if (anchor == null || !frameValid || !anchor.frameValid
                || frame.orbit.weight != 1 || anchor.frame.orbit.weight != 1
                || frame.orbit.minimumFacing > -1 || anchor.frame.orbit.minimumFacing > -1
                || frame.closureWalls != anchor.frame.closureWalls
                || !frame.orbit.shape.equals(anchor.frame.orbit.shape)
                || !projection.equals(anchor.projection)
                || !java.util.Objects.equals(frame.lens, anchor.frame.lens)) return false;
        double relativeY = frame.sourcePlot.y() - frame.orbit.eye.y();
        double anchorY = anchor.frame.sourcePlot.y() - anchor.frame.orbit.eye.y();
        if (Math.abs(relativeY - anchorY) > 1) return false;
        double radius = frame.orbit.shape.radius() + 4096;
        Point d = orbitalOffset.sub(anchor.orbitalOffset);
        if (Math.max(orbitalOffset.length(), anchor.orbitalOffset.length()) > 32768) return false;
        double x = Math.abs(d.x()) + radius * orbitalX.sub(anchor.orbitalX).length();
        double y = Math.abs(d.y()) + radius * orbitalY.sub(anchor.orbitalY).length();
        double z = Math.abs(d.z()) + radius * orbitalZ.sub(anchor.orbitalZ).length();
        return Math.max(x, Math.max(y, z)) <= 2;
    }

    /** Keep the entire source enclosure well beyond the 96..256 m rigid/orbital blend. */
    public boolean orbitalWindowSource(int minY, int maxY) {
        if (!frameValid || frame.orbit.weight != 1 || minY < -2048 || maxY > 2048) return false;
        double shift = frame.sourcePlot.y() - frame.orbit.eye.y();
        return maxY + shift < -512 || minY + shift > 512;
    }

    public Evaluation evaluateOrbitalWindow(Face face, Bounds source) {
        if (!valid(face, source) || !orbitalWindowSource((int) Math.floor(source.minY),
                (int) Math.ceil(source.maxY))) return Evaluation.UNCERTAIN;
        Bounds view = viewBounds(face, source);
        if (view != null) view = view.expand(8);
        return new Evaluation(view == null || clipVisible(view), view);
    }

    /** One repair-bound evaluation shared by visibility, SSE distance and draw ordering. */
    public record Evaluation(boolean visible, Bounds view, double distance) {
        public static final Evaluation UNCERTAIN = new Evaluation(true, null);
        private static final Evaluation REJECTED = new Evaluation(false, null);
        public Evaluation(boolean visible, Bounds view) {
            this(visible, view, view == null ? 1 : Math.max(1, view.distance()));
        }
    }

    public Evaluation evaluate(Face face, Bounds source) {
        if (!valid(face, source)) return Evaluation.UNCERTAIN;
        // Facing is an inexpensive proof; do not enumerate repair cells for a rejected node.
        if (facingRejected(face, source)) return Evaluation.REJECTED;
        Bounds view = viewBounds(face, source);
        return new Evaluation(view == null || clipVisible(view), view);
    }

    /** True also means uncertain: false is a proof that no shader-visible geometry can contribute. */
    public boolean visible(Face face, Bounds source) {
        return evaluate(face, source).visible();
    }

    /** Convenience overload using the topology's face ordinal. Invalid ordinals remain visible. */
    public boolean visible(int face, Bounds source) {
        return face < 0 || face >= Face.VALUES.length || visible(Face.VALUES[face], source);
    }

    /**
     * Conservative view-space convex bound before closure-wall depth bias. Null means uncertain.
     * Its distance() is a lower bound usable for SSE. The rigid/orbit smoothstep is enclosed by
     * the union of its endpoints whenever the source box can intersect that transition.
     */
    public Bounds viewBounds(Face face, Bounds source) {
        if (!valid(face, source)) return null;
        if (frame.orbit.eye.y() <= 160) return computeViewBounds(face, source);
        int f = face.ordinal();
        RepairBounds cache = geometry.repaired[f];
        if (cache == null || !sameRepairFrame(cache.anchor)) {
            cache = new RepairBounds(this);
            geometry.repaired[f] = cache;
        }
        Bounds box = cache.boxes.get(source);
        if (box == null) {
            box = cache.anchor.computeViewBounds(face, source);
            if (box == null) return null;
            if (cache.boxes.size() >= GroundQuadtree.NODE_COUNT) cache.boxes.clear();
            cache.boxes.put(source, box);
        }
        if (sameFrame(cache.anchor)) return box;
        return rotateRepairBounds(cache.anchor, box);
    }

    private Bounds rotateRepairBounds(GroundQuadtreeCuller anchor, Bounds box) {
        if (repairAnchor != anchor) {
            // Invert the admitted basis, rather than assuming a perfectly orthonormal matrix.
            Point a = anchor.frame.worldY.cross(anchor.frame.worldZ);
            double determinant = anchor.frame.worldX.dot(a);
            a = a.scale(1/determinant);
            Point b = anchor.frame.worldZ.cross(anchor.frame.worldX).scale(1/determinant);
            Point c = anchor.frame.worldX.cross(anchor.frame.worldY).scale(1/determinant);
            repairX = frame.worldX.scale(a.x()).add(frame.worldY.scale(b.x())).add(frame.worldZ.scale(c.x()));
            repairY = frame.worldX.scale(a.y()).add(frame.worldY.scale(b.y())).add(frame.worldZ.scale(c.y()));
            repairZ = frame.worldX.scale(a.z()).add(frame.worldY.scale(b.z())).add(frame.worldZ.scale(c.z()));
            Point offset = anchor.frame.viewOffset.sub(frame.viewOffset);
            repairShift = frame.worldX.scale(offset.x()).add(frame.worldY.scale(offset.y())).add(frame.worldZ.scale(offset.z()));
            repairAnchor = anchor;
        }
        double x = (box.minX+box.maxX)*.5, y = (box.minY+box.maxY)*.5, z = (box.minZ+box.maxZ)*.5;
        double rx = (box.maxX-box.minX)*.5, ry = (box.maxY-box.minY)*.5, rz = (box.maxZ-box.minZ)*.5;
        double cx = repairX.x()*x + repairY.x()*y + repairZ.x()*z + repairShift.x();
        double cy = repairX.y()*x + repairY.y()*y + repairZ.y()*z + repairShift.y();
        double cz = repairX.z()*x + repairY.z()*y + repairZ.z()*z + repairShift.z();
        double ex = Math.abs(repairX.x())*rx + Math.abs(repairY.x())*ry + Math.abs(repairZ.x())*rz;
        double ey = Math.abs(repairX.y())*rx + Math.abs(repairY.y())*ry + Math.abs(repairZ.y())*rz;
        double ez = Math.abs(repairX.z())*rx + Math.abs(repairY.z())*ry + Math.abs(repairZ.z())*rz;
        Bounds result = new Bounds(cx-ex, cy-ey, cz-ez, cx+ex, cy+ey, cz+ez);
        // Always rotate the original box; reserve rounding for the new float camera uniforms.
        double error = 2 + 256.0 * Math.ulp((float) maxAbs(result));
        return finite(result) && maxAbs(result) < 1e12 ? result.expand(error) : null;
    }

    private Bounds computeViewBounds(Face face, Bounds source) {
        double magnitude = Math.max(maxAbs(source), Math.max(maxAbs(frame.orbit.eye),
                Math.max(maxAbs(frame.sourcePlot), maxAbs(frame.viewOffset))));
        double margin = 2 + 128.0 * Math.ulp((float) magnitude)
                + 8 * magnitude * basisError;
        if (!Double.isFinite(margin) || margin > 64) return null;
        Bounds s = source.expand(margin);
        Accumulator points = new Accumulator();
        double[] arithmeticError = {0};
        if (frame.orbit.eye.y() <= 160) {
            // flat axes are rigid signed permutations in production, but affine corner bounds
            // also enclose a more general supplied flat transform.
            Accumulator flat = new Accumulator();
            corners(s, p -> flat.add(frame.flatOrigin.add(frame.flatU.scale(p.x()))
                    .add(frame.flatV.scale(p.z())).add(new Point(0, p.y(), 0))));
            Bounds b = flat.box();
            if (!gridReasonable(b)) return null;
            java.util.function.Consumer<Point> sample = p -> {
                // These are exact repair-grid nodes: avoid evaluating four zero-weight neighbors.
                Point ground = frame.gap.raw(p);
                Point air = frame.gap.air.raw(p).add(frame.gap.air.offset);
                points.add(ground.scale(1 - frame.gap.air.weight)
                        .add(air.scale(frame.gap.air.weight)));
                double denominator = 1 + frame.gap.air.direction(p).dot(frame.gap.air.normal);
                arithmeticError[0] = Math.max(arithmeticError[0], frame.gap.air.weight
                        * nodeError(denominator, 1e-6, p.y(), frame.gap.air.basisU,
                                frame.gap.air.basisV));
            };
            if (frame.gap.air.weight == 0 && frame.gap.strength == 0) corners(b, points::add);
            else lattice(b, sample);
        } else {
            if (!gridReasonable(s)) return null;
            Bounds relative = translated(s, frame.sourcePlot.sub(frame.orbit.eye));
            if (frame.orbit.weight == 1 && relative.distance() >= 256) {
                Bounds result = orbitalViewBounds(face, s);
                double error = margin + 3 * nodeError(2, 1e-9,
                        Math.max(Math.abs(s.minY), Math.abs(s.maxY)),
                        frame.orbit.basisU, frame.orbit.basisV)
                        + 256.0 * Math.ulp((float) maxAbs(result));
                return finite(result) && maxAbs(result) <= 1e12
                        && Double.isFinite(error) && error < 1e12 ? result.expand(error) : null;
            }
            // relative is PRE-warp, with ViewOffset cancelled, exactly as in the shader.
            if (relative.distance() < 256) {
                corners(s, p -> points.add(p.add(frame.orbit.offset)));
            }
            if (maxRadius(relative) > 96 && frame.orbit.weight == 1) {
                // At full orbit d=2 exactly. Bound all repair-lattice directions analytically:
                // affine cube coordinates / positive radius, then affine in terrain height.
                // The grid rectangle includes the outer interpolation neighbors and face folds.
                Bounds orbitBound = orbitalBounds(face, s);
                corners(orbitBound, points::add);
                arithmeticError[0] = nodeError(2, 1e-9,
                        Math.max(Math.abs(s.minY), Math.abs(s.maxY)),
                        frame.orbit.basisU, frame.orbit.basisV);
            } else if (maxRadius(relative) > 96) {
                lattice(s, p -> {
                    points.add(orbitPoint(face, p));
                    double c = direction(face, p.x(), p.z(), frame.orbit.shape.size())
                            .dot(frame.orbit.normal), k = frame.orbit.weight;
                    arithmeticError[0] = Math.max(arithmeticError[0],
                            nodeError(1+c+k*k*(1-c), 1e-9, p.y(), frame.orbit.basisU,
                                    frame.orbit.basisV));
                });
            }
        }
        Bounds warped = points.box();
        if (!finite(warped)) return null;
        Accumulator view = new Accumulator();
        corners(warped, p -> {
            Point r = p.sub(frame.orbit.eye).sub(frame.viewOffset);
            view.add(frame.worldX.scale(r.x()).add(frame.worldY.scale(r.y()))
                    .add(frame.worldZ.scale(r.z())));
        });
        Bounds result = view.box();
        if (!finite(result) || maxAbs(result) > 1e12) return null;
        // Float uniform rounding and arithmetic, including the repair denominator near its pole.
        double error = margin + 3*arithmeticError[0]
                + 256.0 * Math.ulp((float) maxAbs(result));
        return Double.isFinite(error) && error < 1e12 ? result.expand(error) : null;
    }

    // Reserve float rounding through normalization, uniform basis products and division. Near
    // the stereographic pole the denominator is ill-conditioned: enlarge or retain, never infer
    // visibility from a tight double-precision bound there.
    private double nodeError(double denominator, double floor, double height, Point u, Point v) {
        double d = Math.max(floor, denominator-2e-6);
        return 1e-5*(frame.orbit.shape.radius()+Math.abs(height)+1)
                *(1+u.length()+v.length())/(d*d);
    }

    /**
     * Only the shader's facing cutoff is an occlusion proof here. A physical tangent horizon
     * alone is NOT sufficient: OrbitLimits.w reaches -1 and admits the opposite hemisphere.
     * For rectangles crossing face folds we retain the node rather than assume a cone.
     */
    private boolean facingRejected(Face face, Bounds source) {
        // The opposite face has no flat-chart placement; identity would alias the native face.
        if (frame.orbit.eye.y() <= 160 && frame.gap.chart.tile(face) == null) return true;
        if (frame.orbit.eye.y() > 160 && frame.orbit.minimumFacing <= -1) return false;
        double pad = 2 + 128*Math.ulp((float)Math.max(maxAbs(source),maxAbs(frame.orbit.eye)));
        Bounds s = source.expand(pad);
        Point normal;
        double cutoff;
        if (frame.orbit.eye.y() > 160) {
            normal = new Point(frame.orbit.normal.dot(face.u),
                    frame.orbit.normal.dot(face.normal), frame.orbit.normal.dot(face.v));
            cutoff = frame.orbit.minimumFacing;
        } else {
            if (frame.gap.air.weight == 0) return false;
            Accumulator flat = new Accumulator();
            corners(s, p -> flat.add(frame.flatOrigin.add(frame.flatU.scale(p.x()))
                    .add(frame.flatV.scale(p.z()))));
            s = flat.box();
            normal = frame.gap.air.normal;
            cutoff = 0;
        }
        double size = frame.orbit.shape.size(), half = size*.5;
        if (s.minX < 0 || s.maxX > size || s.minZ < 0 || s.maxZ > size) return false;
        Bounds cube = new Bounds(s.minX-half,half,s.minZ-half,
                s.maxX-half,half,s.maxZ-half);
        Range numerator = new Range(cube.minX,cube.maxX).scale(normal.x())
                .add(Range.of(half*normal.y()))
                .add(new Range(cube.minZ,cube.maxZ).scale(normal.z()));
        double upper = numerator.high/(numerator.high>=0?cube.distance():maxRadius(cube));
        return upper < cutoff-1e-5;
    }

    private Bounds orbitalCube(Face face, Bounds source) {
        int size = frame.orbit.shape.size();
        double x0 = Math.floor(source.minX / 16) * 16;
        double x1 = (Math.floor(source.maxX / 16) + 1) * 16;
        double z0 = Math.floor(source.minZ / 16) * 16;
        double z1 = (Math.floor(source.maxZ / 16) + 1) * 16;
        var key = new GeometryKey(size, face, (int) x0, (int) z0, (int) x1, (int) z1);
        Bounds cached = geometry.cubes.get(key);
        if (cached != null) return cached;
        Accumulator cube = new Accumulator();
        double[] xs = {x0, x1, Math.max(x0, Math.min(x1, 0)), Math.max(x0, Math.min(x1, size))};
        double[] zs = {z0, z1, Math.max(z0, Math.min(z1, 0)), Math.max(z0, Math.min(z1, size))};
        if (x0 >= 0 && x1 <= size && z0 >= 0 && z1 <= size) {
            // Interior cube-face coordinates are affine signed permutations. Opposite
            // rectangle corners already bound their hull; folds need the full path below.
            cube.add(cubePoint(face, x0, z0, size));
            cube.add(cubePoint(face, x1, z1, size));
        } else for (double x : xs) for (double z : zs) {
            cube.add(cubePoint(face, x, z, size));
            // Both one-sided fold limits at a cube corner belong to the conservative hull.
            if (x == 0 || x == size) {
                cube.add(cubePoint(face, Math.nextDown(x), z, size));
                cube.add(cubePoint(face, Math.nextUp(x), z, size));
            }
            if (z == 0 || z == size) {
                cube.add(cubePoint(face, x, Math.nextDown(z), size));
                cube.add(cubePoint(face, x, Math.nextUp(z), size));
            }
        }
        Bounds box = cube.box();
        if (geometry.cubes.size() >= GroundQuadtree.NODE_COUNT) geometry.cubes.clear();
        geometry.cubes.put(key, box);
        return box;
    }

    private Bounds orbitalBounds(Face face, Bounds source) {
        Bounds box = orbitalCube(face, source);
        Range radius = new Range(box.distance(), maxRadius(box));
        Range height = new Range(frame.orbit.shape.radius() + source.minY,
                frame.orbit.shape.radius() + source.maxY);
        Range x = directionDot(box, frame.orbit.basisU).divide(radius).multiply(height)
                .add(Range.of(frame.orbit.eye.x() + frame.orbit.offset.x()));
        Range y = directionDot(box, frame.orbit.normal).divide(radius).multiply(height)
                .add(Range.of(-frame.orbit.shape.radius() + frame.orbit.offset.y()));
        Range z = directionDot(box, frame.orbit.basisV).divide(radius).multiply(height)
                .add(Range.of(frame.orbit.eye.z() + frame.orbit.offset.z()));
        return new Bounds(x.low, y.low, z.low, x.high, y.high, z.high);
    }

    private Bounds orbitalViewBounds(Face face, Bounds source) {
        Bounds cube = orbitalCube(face, source);
        Range radius = new Range(cube.distance(), maxRadius(cube));
        Range height = new Range(frame.orbit.shape.radius() + source.minY,
                frame.orbit.shape.radius() + source.maxY);
        Range rx = directionDot(cube, orbitalX).divide(radius).multiply(height).add(Range.of(orbitalOffset.x()));
        Range ry = directionDot(cube, orbitalY).divide(radius).multiply(height).add(Range.of(orbitalOffset.y()));
        Range rz = directionDot(cube, orbitalZ).divide(radius).multiply(height).add(Range.of(orbitalOffset.z()));
        return new Bounds(rx.low, ry.low, rz.low, rx.high, ry.high, rz.high);
    }

    private static Range directionDot(Bounds box, Point axis) {
        double ax = box.minX * axis.x(), bx = box.maxX * axis.x();
        double ay = box.minY * axis.y(), by = box.maxY * axis.y();
        double az = box.minZ * axis.z(), bz = box.maxZ * axis.z();
        return new Range(Math.min(ax, bx) + Math.min(ay, by) + Math.min(az, bz),
                Math.max(ax, bx) + Math.max(ay, by) + Math.max(az, bz));
    }

    private Point orbitPoint(Face face, Point p) {
        int size = frame.orbit.shape.size();
        // Called only at repair-grid nodes; their bilinear interpolation has unit weight here.
        // Evaluate a single raw node, including GLSL's X-before-Z folding at face overhangs.
        Point s = direction(face, p.x(), p.z(), size);
        PlanetOrbit o = frame.orbit;
        double c = s.dot(o.normal), k = o.weight;
        double d = Math.max(1e-9, 1 + c + k*k*(1-c));
        Point t = s.sub(o.normal.scale(c));
        double scale = 2 * (o.shape.radius() + k*p.y()) / d;
        return new Point(o.eye.x()+t.dot(o.basisU)*scale,
                p.y()*(1-2*k*k*(1-c)/d)-2*o.shape.radius()*k*(1-c)/d,
                o.eye.z()+t.dot(o.basisV)*scale).add(o.offset);
    }

    private boolean clipVisible(Bounds original) {
        return clipVisible(original, Range.of(frame.lens.weight()));
    }

    private boolean clipVisible(Bounds original, Range weights) {
        Bounds b = original;
        if (frame.closureWalls) {
            double scale = 1 + Math.max(.002, 3e-6 * maxRadius(b));
            b = new Bounds(Math.min(b.minX, b.minX * scale), Math.min(b.minY, b.minY * scale),
                    Math.min(b.minZ, b.minZ * scale), Math.max(b.maxX, b.maxX * scale),
                    Math.max(b.maxY, b.maxY * scale), Math.max(b.maxZ, b.maxZ * scale));
        }
        // For checked affine bob T, ||T(p)-p|| <= ||T.linear-I||_F ||p|| + ||T.translation||.
        // The ordinary projection clips against this box while rendering uses the full matrix.
        if (boundedBob) b = b.expand(BOB_LINEAR_LIMIT * maxRadius(b) + BOB_TRANSLATION_LIMIT);
        // Ordinary projection needs only the same row extrema, without temporary interval chains.
        if (weights.high == 0) {
            double w = upper(b.minX,b.maxX,projection.m03()) + upper(b.minY,b.maxY,projection.m13())
                    + upper(b.minZ,b.maxZ,projection.m23()) + projection.m33();
            // Reversed depth can rewrite the depth row differently after bob is applied.
            // For this envelope, only reject against the four side planes.
            for (int axis = 0; axis < (boundedBob ? 2 : 3); axis++) {
                double lo = lower(b.minX,b.maxX,projection.get(0,axis))
                        + lower(b.minY,b.maxY,projection.get(1,axis))
                        + lower(b.minZ,b.maxZ,projection.get(2,axis)) + projection.get(3,axis);
                double hi = upper(b.minX,b.maxX,projection.get(0,axis))
                        + upper(b.minY,b.maxY,projection.get(1,axis))
                        + upper(b.minZ,b.maxZ,projection.get(2,axis)) + projection.get(3,axis);
                double plus = w + hi, minus = w - lo;
                if ((Double.isFinite(plus) && plus < -1 - 256*Math.ulp((float)Math.abs(plus)))
                        || (Double.isFinite(minus) && minus < -1 - 256*Math.ulp((float)Math.abs(minus))))
                    return false;
            }
            return true;
        }
        Range x = new Range(b.minX, b.maxX), y = new Range(b.minY, b.maxY),
                z = new Range(b.minZ, b.maxZ);
        Range[] ordinary = new Range[4];
        for (int row=0; row<4; row++) {
            ordinary[row] = x.scale(projection.get(0,row)).add(y.scale(projection.get(1,row)))
                    .add(z.scale(projection.get(2,row))).add(Range.of(projection.get(3,row)));
        }
        double weight = weights.high;
        Range[] clip = ordinary;
        if (weight > 0) {
            Range r = new Range(Math.max(1e-6, b.distance()), Math.max(1e-6, maxRadius(b)));
            double lx = frame.lens.x(), ly = frame.lens.y();
            double rho2 = 1/(lx*lx) + 1/(ly*ly), cosine = (4-rho2)/(4+rho2);
            Range d = r.scale(1+cosine).maximum(r.subtract(z));
            if (!(d.low > 1e-12)) return true;
            Range[] stereo = {x.multiply(r).divide(d).scale(2*lx),
                    y.multiply(r).divide(d).scale(2*ly),
                    r.scale(-projection.m22()).add(Range.of(projection.m32())), r};
            clip = new Range[4];
            for (int i=0; i<4; i++) clip[i] = ordinary[i].multiply(Range.of(1).subtract(weights)).add(stereo[i].multiply(weights));
        }
        for (int axis=0; axis<3; axis++) {
            if (negative(clip[3].add(clip[axis])) || negative(clip[3].subtract(clip[axis])))
                return false;
        }
        return true;
    }

    private static boolean negative(Range r) {
        return Double.isFinite(r.high) && r.high < -1 - 256*Math.ulp((float)Math.abs(r.high));
    }

    private boolean valid(Face face, Bounds s) {
        return frameValid && face != null && finite(s);
    }

    private boolean validFrame() {
        if (frame == null || projection == null
                || frame.gap == null || frame.orbit == null || frame.lens == null) return false;
        if (!frame.gap.shape.equals(frame.orbit.shape) || !frame.gap.eye.equals(frame.orbit.eye)) return false;
        for (Point p : new Point[]{frame.sourcePlot, frame.flatOrigin, frame.flatU, frame.flatV,
                frame.viewOffset, frame.worldX, frame.worldY, frame.worldZ,
                frame.orbit.eye, frame.orbit.offset}) if (p == null || !p.finite()) return false;
        for (int c=0;c<4;c++) for (int r=0;r<4;r++)
            if (!Float.isFinite(projection.get(c,r))) return false;
        double w = frame.lens.weight();
        return gramError() < 1e-4 && w >= 0 && w <= 1
                && frame.lens.x() > 0 && frame.lens.y() > 0
                && Double.isFinite(frame.lens.x()) && Double.isFinite(frame.lens.y());
    }

    private double gramError() {
        Point[] axes = {frame.worldX, frame.worldY, frame.worldZ};
        double error=0;
        for (int i=0;i<3;i++) {
            double row=0;
            for (int j=0;j<3;j++) row+=Math.abs(axes[i].dot(axes[j])-(i==j?1:0));
            error=Math.max(error,row);
        }
        return error;
    }

    private static boolean gridReasonable(Bounds b) {
        return finite(b) && Math.abs(b.minX)<16384 && Math.abs(b.maxX)<16384
                && Math.abs(b.minZ)<16384 && Math.abs(b.maxZ)<16384
                && (Math.ceil((b.maxX-b.minX)/16)+2)*(Math.ceil((b.maxZ-b.minZ)/16)+2)<=20000;
    }

    private static void lattice(Bounds b, java.util.function.Consumer<Point> out) {
        int x0=(int)Math.floor(b.minX/16), x1=(int)Math.floor(b.maxX/16)+1;
        int z0=(int)Math.floor(b.minZ/16), z1=(int)Math.floor(b.maxZ/16)+1;
        for(int x=x0;x<=x1;x++) for(int z=z0;z<=z1;z++) {
            out.accept(new Point(x*16,b.minY,z*16));
            out.accept(new Point(x*16,b.maxY,z*16));
        }
    }

    private static void corners(Bounds b, java.util.function.Consumer<Point> out) {
        for(int i=0;i<8;i++) out.accept(new Point((i&1)==0?b.minX:b.maxX,
                (i&2)==0?b.minY:b.maxY,(i&4)==0?b.minZ:b.maxZ));
    }

    private static Point direction(Face f, double x, double z, int size) {
        Point q = cubePoint(f, x, z, size);
        return q.scale(1 / q.length());
    }

    private static Point cubePoint(Face f, double x, double z, int size) {
        double h=size*.5;
        Point q;
        if(x<0) q=f.u.scale(-h).add(f.normal.scale(h+x)).add(f.v.scale(z-h));
        else if(x>size) q=f.u.scale(h).add(f.normal.scale(3*h-x)).add(f.v.scale(z-h));
        else if(z<0) q=f.v.scale(-h).add(f.normal.scale(h+z)).add(f.u.scale(x-h));
        else if(z>size) q=f.v.scale(h).add(f.normal.scale(3*h-z)).add(f.u.scale(x-h));
        else q=f.normal.scale(h).add(f.u.scale(x-h)).add(f.v.scale(z-h));
        return q;
    }

    private static Bounds translated(Bounds b, Point p) {
        return new Bounds(b.minX+p.x(),b.minY+p.y(),b.minZ+p.z(),
                b.maxX+p.x(),b.maxY+p.y(),b.maxZ+p.z());
    }
    private static double maxAbs(Point p) {return Math.max(Math.abs(p.x()),Math.max(Math.abs(p.y()),Math.abs(p.z())));}
    private static double maxAbs(Bounds b) {return Math.max(maxAbs(new Point(b.minX,b.minY,b.minZ)),maxAbs(new Point(b.maxX,b.maxY,b.maxZ)));}
    private static double maxRadius(Bounds b) {
        double x=Math.max(Math.abs(b.minX),Math.abs(b.maxX)), y=Math.max(Math.abs(b.minY),Math.abs(b.maxY)), z=Math.max(Math.abs(b.minZ),Math.abs(b.maxZ));
        return Math.sqrt(x*x+y*y+z*z);
    }
    private static boolean finite(Bounds b) {
        return b!=null && Double.isFinite(b.minX) && Double.isFinite(b.minY) && Double.isFinite(b.minZ)
                && Double.isFinite(b.maxX) && Double.isFinite(b.maxY) && Double.isFinite(b.maxZ)
                && b.minX<=b.maxX && b.minY<=b.maxY && b.minZ<=b.maxZ;
    }
    private static final class Accumulator {
        double x0=Double.POSITIVE_INFINITY,y0=x0,z0=x0,x1=Double.NEGATIVE_INFINITY,y1=x1,z1=x1;
        void add(Point p) {x0=Math.min(x0,p.x());y0=Math.min(y0,p.y());z0=Math.min(z0,p.z());x1=Math.max(x1,p.x());y1=Math.max(y1,p.y());z1=Math.max(z1,p.z());}
        Bounds box() {return new Bounds(x0,y0,z0,x1,y1,z1);}
    }
    private record Range(double low, double high) {
        static Range of(double v) {return new Range(v,v);}
        Range union(Range r) {return new Range(Math.min(low,r.low),Math.max(high,r.high));}
        Range add(Range r) {return new Range(low+r.low,high+r.high);}
        Range subtract(Range r) {return new Range(low-r.high,high-r.low);}
        Range scale(double s) {return s>=0?new Range(low*s,high*s):new Range(high*s,low*s);}
        Range maximum(Range r) {return new Range(Math.max(low,r.low),Math.max(high,r.high));}
        Range multiply(Range r) {double a=low*r.low,b=low*r.high,c=high*r.low,d=high*r.high;return new Range(Math.min(Math.min(a,b),Math.min(c,d)),Math.max(Math.max(a,b),Math.max(c,d)));}
        Range divide(Range r) {return multiply(new Range(1/r.high,1/r.low));}
    }
}
