package dev.starfold.api;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetAtmosphere;
import dev.starfold.worldgen.surface.SurfaceColor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlanetMapApiTest {
    @Test void directionsUseTheTerrainMappingAndRoundTripAllFacesSizesEdgesAndCorners() {
        for (int size : new int[]{256, 1040, 2048}) {
            var shape = new PlanetShape(size);
            for (var face : Face.VALUES) for (double u : new double[]{0, .123 * size, .5 * size, size - .01, size})
                for (double v : new double[]{0, .371 * size, .5 * size, size}) {
                    var d = PlanetMapApi.direction(shape, face.ordinal(), u, v);
                    var expected = shape.direction(face, u, v);
                    assertEquals(expected.x(), d.x(), 1e-12);
                    assertEquals(expected.y(), d.y(), 1e-12);
                    assertEquals(expected.z(), d.z(), 1e-12);
                    assertEquals(1, Math.sqrt(d.x()*d.x()+d.y()*d.y()+d.z()*d.z()), 1e-12);
                    var hit = PlanetMapApi.locate(shape, d);
                    var restored = PlanetMapApi.direction(shape, hit.face(), hit.u(), hit.v());
                    assertEquals(d.x(), restored.x(), 1e-12);
                    assertEquals(d.y(), restored.y(), 1e-12);
                    assertEquals(d.z(), restored.z(), 1e-12);
                    if (u > 0 && u < size && v > 0 && v < size) {
                        assertEquals(face.ordinal(), hit.face());
                        assertEquals(u, hit.u(), 1e-9); assertEquals(v, hit.v(), 1e-9);
                    }
                }
        }
    }
    @Test void sunlightTracksTheLocalFrameAtTheCurrentTickAndAtmospherePreservesMetadata() {
        var spin = new GroundEphemeris.Spin(true, GroundEphemeris.Y, new Quaterniond().rotationZ(.3), .4, 24000, 0);
        var model = new GroundEphemeris.Model(List.of(
                new GroundEphemeris.Body(new GroundEphemeris.Fixed(new Point(100, 30, -60)), 100, true, -1, GroundEphemeris.Appearance.LIGHT),
                new GroundEphemeris.Body(new GroundEphemeris.Orbit(0, GroundEphemeris.Y, new Point(1, 0, 0), 30000, 80000, .2, 0, spin),
                        1000, false, 0, GroundEphemeris.Appearance.SURFACE)));
        for (double tick : new double[]{0, 6000, 12000, 79000}) {
            var d = PlanetMapApi.sun(model, 1, tick);
            var local = new Point(d.x(), d.y(), d.z());
            assertEquals(1, local.length(), 1e-12);
            var frame = model.frame(1, tick);
            var expected = model.star(frame.center(), tick).position().sub(frame.center());
            expected = expected.scale(1 / expected.length());
            assertEquals(0, frame.vector(local).sub(expected).length(), 1e-12);
        }
        assertNull(PlanetMapApi.atmosphere(PlanetAtmosphere.NONE));
        assertEquals(new PlanetMapApi.Atmosphere(0xff78a7ff, 1), PlanetMapApi.atmosphere(PlanetAtmosphere.EARTH));
        assertEquals(new PlanetMapApi.Atmosphere(0xffaa3210, .3), PlanetMapApi.atmosphere(new PlanetAtmosphere(.3, 0xaa3210, false)));
    }
    @Test void loadedColumnUsesTheHighestOpaqueEditedBlockAndSkipsTransparentDecoration() {
        BlockState air = Blocks.AIR.defaultBlockState(), stone = Blocks.STONE.defaultBlockState();
        var column = new BlockState[]{stone, Blocks.RED_WOOL.defaultBlockState(),
                Blocks.GLASS.defaultBlockState(), Blocks.SHORT_GRASS.defaultBlockState(), air};
        var sample = PlanetMapApi.loadedColumn(132, 128, y -> column[y - 128]);
        assertEquals(0xff000000 | SurfaceColor.color(column[1]), sample.argb());
        assertEquals(2, sample.height()); assertEquals(-1, sample.waterDepth());
        column[1] = Blocks.BLUE_WOOL.defaultBlockState();
        assertNotEquals(sample.argb(), PlanetMapApi.loadedColumn(132, 128, y -> column[y - 128]).argb());
        assertNull(PlanetMapApi.loadedColumn(132, 128, y -> air));
    }
    @Test void waterKeepsItsSurfaceHeightAndMeasuresOpaqueBedDepthEvenBelowWorldZero() {
        for (int offset : new int[]{128, -32}) {
            var sample = PlanetMapApi.loadedColumn(offset + 5, offset,
                    y -> y > offset + 4 ? Blocks.AIR.defaultBlockState()
                            : y > offset + 1 ? Blocks.WATER.defaultBlockState() : Blocks.STONE.defaultBlockState());
            assertEquals(offset + 5 - 128, sample.height());
            assertEquals(3, sample.waterDepth());
            assertEquals(0xff000000 | SurfaceColor.color(Blocks.WATER.defaultBlockState()), sample.argb());
        }
        var bottomless = PlanetMapApi.loadedColumn(130, 128, y -> Blocks.WATER.defaultBlockState());
        assertEquals(3, bottomless.waterDepth());
    }
    @Test void loadedQueryUsesStarfoldClientChartAndNeverRequestsChunks() throws Exception {
        String api = Files.readString(Path.of("src/main/java/dev/starfold/api/PlanetMapApi.java"));
        String query = api.substring(api.indexOf("public static LoadedCell loadedSurface("), api.indexOf("static LoadedCell loadedColumn("));
        assertTrue(query.contains("GroundClient.planePoint("));
        assertTrue(query.contains("getChunkNow("));
        assertTrue(query.contains("if (chunk == null) return null"));
        assertFalse(query.contains(".getChunk("));
        assertFalse(query.contains("mc.level.getBlockState("));
    }
}
