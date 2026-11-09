package dev.starfold.chart;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.sky.GroundEphemerisSave;

import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

class SunSettingsTest {
    private double brightness(Point p) {
        return .2126 * p.x() + .7152 * p.y() + .0722 * p.z();
    }

    @Test
    void warmWhiteEndpointsAreBrighterButRetainCellContrastWithoutClipping() {
        var low = SunSettings.SURFACE_LOW;
        var high = SunSettings.SURFACE_HIGH;
        assertEquals(new Point(1, .80, .42), low);
        assertEquals(new Point(1, .97, .82), high);
        for (int i = 0; i <= 100; i++) {
            double t = i / 100.0;
            var color = low.scale(1 - t).add(high.scale(t));
            var old = new Point(1, .52, .12).scale(1 - t).add(new Point(1, .88, .48).scale(t));
            for (double c : new double[] {color.x(), color.y(), color.z()})
                assertTrue(c >= 0 && c <= 1);
            assertTrue(brightness(color) > brightness(old));
        }
        assertTrue(brightness(high) - brightness(low) > .1);
        assertTrue(low.z() < low.y() && low.y() < low.x());
        assertTrue(high.z() < high.y() && high.y() < high.x());
    }

    @Test
    void generatedRadiusPreservesEveryOriginalDiscreteDrawIncludingHalfBlocks() {
        assertEquals(576, SunSettings.generatedRadius(0));
        assertEquals(960, SunSettings.generatedRadius(256));
        for (int i = 0; i <= 256; i++)
            assertEquals((384 + i) * 1.5, SunSettings.generatedRadius(i));
        assertEquals(577.5, SunSettings.generatedRadius(1));
    }

    @Test
    void oldAndCustomSavedRadiiAreNotRescaled() {
        for (double radius : new double[] {128, 512, 768, 913.5}) {
            var bodies = new ArrayList<>(GroundSolar.defaults().bodies());
            var sun = bodies.getFirst();
            bodies.set(
                    0, new GroundEphemeris.Body(sun.motion(), radius, true, -1, sun.appearance()));
            var state = new GroundEphemerisSave(new GroundEphemeris.Model(bodies), 3456);
            var tag = state.save(new CompoundTag(), null);
            var copy = GroundEphemerisSave.load(tag, null);
            assertEquals(radius, copy.model.primary().radius());
            assertEquals(state.model, copy.model);
            assertEquals(tag, copy.save(new CompoundTag(), null));
            assertEquals(9, tag.getInt("format"));
        }
    }

    @Test
    void shaderSurfaceUniformsMatchPureDefaultsAndOnlyReplaceTheSurfaceColor() throws Exception {
        var root = Path.of("src/main/resources/assets/starfold/shaders/core");
        var json =
                JsonParser.parseString(Files.readString(root.resolve("native_ground_sun.json")))
                        .getAsJsonObject();
        for (var entry :
                java.util.Map.of(
                                "SurfaceLow",
                                SunSettings.SURFACE_LOW,
                                "SurfaceHigh",
                                SunSettings.SURFACE_HIGH)
                        .entrySet()) {
            var uniform =
                    java.util.stream.StreamSupport.stream(
                                    json.getAsJsonArray("uniforms").spliterator(), false)
                            .map(e -> e.getAsJsonObject())
                            .filter(e -> e.get("name").getAsString().equals(entry.getKey()))
                            .findFirst()
                            .orElseThrow();
            assertEquals("float", uniform.get("type").getAsString());
            assertEquals(3, uniform.get("count").getAsInt());
            var values = uniform.getAsJsonArray("values");
            assertEquals(3, values.size());
            assertEquals(entry.getValue().x(), values.get(0).getAsDouble());
            assertEquals(entry.getValue().y(), values.get(1).getAsDouble());
            assertEquals(entry.getValue().z(), values.get(2).getAsDouble());
        }
        var shader = Files.readString(root.resolve("native_ground_sun.fsh"));
        assertTrue(shader.contains("uniform vec3 HaloColor, SurfaceLow, SurfaceHigh;"));
        assertTrue(shader.contains("vec3 color=mix(SurfaceLow,SurfaceHigh,cells)*limb;"));
        assertTrue(shader.contains("float cells=floor(grain*4.0)/3.0;"));
        assertTrue(shader.contains("float tick=floor(SunTime*0.25+hash(cell)*4.0);"));
        assertTrue(shader.contains("if(spaceFacing<0.0) discard;"));
        assertTrue(shader.contains("if(far>0.0001&&near<distance-0.0001) discard;"));
        assertTrue(shader.contains("fragColor=vec4(HaloColor,HaloStrength*glow);return;"));
    }
}
