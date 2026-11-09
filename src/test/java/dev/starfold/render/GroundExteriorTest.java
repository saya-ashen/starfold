package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.chart.GroundOrbit;
import dev.starfold.chart.GroundSpace;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

/** Offline geometry and rendering contracts, not GPU/visual acceptance. */
class GroundExteriorTest {
    GroundExteriorMesh.Heights heights(double edited) {
        return new GroundExteriorMesh.Heights() {
            public double node(Face f, int x, int z) {
                return 0;
            }

            public Double edited(Face f, int x, int z) {
                return f == Face.TOP && x == 255 && z == 255 ? edited : null;
            }
        };
    }

    @Test
    void highMountainAndDeepPitAreActualGeometryEvenForOneColumn() {
        for (double h : new double[] {191, -190}) {
            var p = GroundExteriorMesh.centre(heights(h), Face.TOP, 255, 255);
            assertEquals(GroundOrbit.RADIUS + h, p.length(), 1e-9);
            assertTrue(p.finite());
        }
    }

    @Test
    void fineNodesMatchCoarseChordsWithoutTjunctionCracks() {
        var source = heights(0);
        for (int x = 0; x <= 16; x++) {
            var a = GroundExteriorMesh.position(Face.TOP, 1024, 1024, 0);
            var b = GroundExteriorMesh.position(Face.TOP, 1040, 1024, 0);
            var p = GroundExteriorMesh.node(source, Face.TOP, 1024 + x, 1024);
            assertEquals(0, p.sub(a.scale(1 - x / 16.0).add(b.scale(x / 16.0))).length(), 1e-9);
        }
    }

    @Test
    void editedColumnDisplacesBothItsSharedBoundaryNodes() {
        var source = heights(191);
        for (int z : new int[] {255, 256}) {
            var a = GroundExteriorMesh.node(source, Face.TOP, 256, z);
            assertTrue(a.length() > GroundOrbit.RADIUS + 40);
            assertEquals(
                    47.75,
                    a.sub(GroundExteriorMesh.baseline(source, Face.TOP, 256, z)).length(),
                    1e-9);
        }
    }

    @Test
    void allFaceSeamsAndCornersRemainIdenticalIncludingEdits() {
        var source =
                new GroundExteriorMesh.Heights() {
                    public double node(Face f, int x, int z) {
                        return 0;
                    }

                    public Double edited(Face f, int x, int z) {
                        return f == Face.TOP ? 150.0 : -70.0;
                    }
                };
        int compared = 0;
        for (var f : Face.values())
            for (int x : new int[] {0, 16, 1024, 2032, 2048})
                for (int z : new int[] {0, 16, 1024, 2032, 2048}) {
                    if (x != 0 && x != 2048 && z != 0 && z != 2048) continue;
                    var cube =
                            f.normal.scale(1024).add(f.u.scale(x - 1024)).add(f.v.scale(z - 1024));
                    for (var g : Face.values())
                        if (g != f && cube.dot(g.normal) == 1024) {
                            var a = GroundExteriorMesh.node(source, f, x, z);
                            var b =
                                    GroundExteriorMesh.node(
                                            source,
                                            g,
                                            (int) (1024 + cube.dot(g.u)),
                                            (int) (1024 + cube.dot(g.v)));
                            assertEquals(0, a.sub(b).length(), 1e-9);
                            compared++;
                        }
                }
        assertTrue(compared > 100);
    }

    @Test
    void bakedFullOrbitVerticesAgreeWithPreviousFullCurvatureTransform() {
        var pose = new GroundSpace.Pose(Face.EAST, new Point(750, 1700, 890));
        var eye = pose.point();
        var orbit = new GroundOrbit(pose, eye, 128);
        for (var f : Face.values())
            for (int x : new int[] {0, 256, 1024, 2048})
                for (int z : new int[] {0, 256, 1024, 2048}) {
                    var position = GroundExteriorMesh.position(f, x, z, 120);
                    var draw =
                            orbit.observerFrame
                                    .local(position)
                                    .add(new Point(eye.x(), -GroundOrbit.RADIUS, eye.z()))
                                    .add(orbit.offset);
                    assertEquals(0, draw.sub(orbit.point(f, new Point(x, 120, z))).length(), 1e-8);
                }
    }

    @Test
    void groundViewNeverHandsTheNearPlanetToTheCoarseExterior() throws Exception {
        // The exterior's 16-block cells are only for remote views; near the planet the voxel
        // view coarsens continuously with height instead of switching at one altitude.
        assertFalse(Sources.text("GroundRenderer").contains("GroundExterior.draw("));
        assertFalse(Sources.text("GroundClient").contains("replaceNative"));
    }

    @Test
    void exteriorShadersExportVanillaAndVeilForActualLinking() throws Exception {
        var root = Path.of("src/main/resources/assets/starfold/shaders/core");
        var out = Path.of("build/native-exterior/shaders");
        Files.createDirectories(out);
        for (String ext : List.of("vsh", "fsh")) {
            String text =
                    Files.readString(root.resolve("native_ground_exterior." + ext))
                            .replace(
                                    "#moj_import <starfold:ground_light.glsl>",
                                    GroundShaderSource.SOLAR_LIGHT);
            String suffix = ext.equals("vsh") ? "vert" : "frag";
            Files.writeString(out.resolve("exterior." + suffix), text);
            var parsed =
                    io.github.ocelot.glslprocessor.api.GlslParser.preprocessParse(
                            text, new HashMap<>());
            Files.writeString(out.resolve("exterior-veil." + suffix), parsed.toSourceString());
        }
    }

    @Test
    void farShaderHasNoAtlasSamplingInverseProjectionOrGroundWarp() throws Exception {
        var root = Path.of("src/main/resources/assets/starfold/shaders/core");
        var v = Files.readString(root.resolve("native_ground_exterior.vsh"));
        var f = Files.readString(root.resolve("native_ground_exterior.fsh"));
        assertFalse(v.contains("inverse("));
        assertFalse(v.contains("cpNativeOrbitRepair"));
        assertFalse(f.contains("texture"));
        assertFalse(f.contains("dFdx"));
        assertTrue(f.contains("SunBody-bodyPosition"));
        assertTrue(f.contains("1.0)"));
    }

    @Test
    void onlyCompleteExteriorTakesOverAndOldPatchStaysUntilReplacement() throws Exception {
        var s = Sources.text("GroundRenderer");
        assertTrue(s.contains("ready&&GroundExterior.ready()"));
        assertTrue(s.contains("returnfill!=null&&fill.isDone()"));
        assertTrue(s.contains("exterior.emit(q,rgb,old)"));
        assertTrue(s.contains("dirtyHalo(key)"));
        assertTrue(
                s.contains("GroundPipeline.publish(dirty.count(),!coverJobs.isEmpty(),patches,staging,p->GroundViewPolicy.exists(shape,p))"));
        var exterior = Sources.text("GroundExterior");
        assertTrue(exterior.contains("staging[patch]=mesh"));
        assertTrue(exterior.contains("patches[i]=staging[i]"));
    }
}
