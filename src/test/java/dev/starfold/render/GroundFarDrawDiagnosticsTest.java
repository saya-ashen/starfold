package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.*;

import java.util.HashMap;
import java.util.Map;

class GroundFarDrawDiagnosticsTest {
    @Test
    void inactiveEpochDoesNotClaimOrResetTheOneShotBudget() {
        var gate = new GroundFarDrawDiagnostics.Gate();
        assertFalse(gate.claim(0, 0));
        assertTrue(gate.claim(41, 0));
        for (int frame = 0; frame < 1000; frame++) {
            assertFalse(gate.claim(0, 0));
            assertFalse(gate.claim(41, 0));
        }
    }

    @Test
    void allPassLayerSourceSlotsAreBoundedAndOnlyANewEpochRearmsThem() {
        var gate = new GroundFarDrawDiagnostics.Gate();
        int slots = GroundFarDrawDiagnostics.Gate.SLOTS;
        assertEquals(dev.starfold.api.FarViewDiagnostics.MAX_DRAW_RECORDS, slots);
        for (int slot = 0; slot < slots; slot++) assertTrue(gate.claim(41, slot));
        for (int frame = 0; frame < 1000; frame++)
            for (int slot = 0; slot < slots; slot++) assertFalse(gate.claim(41, slot));
        assertFalse(gate.claim(41, -1));
        assertFalse(gate.claim(41, slots));
        for (int slot = 0; slot < slots; slot++) assertTrue(gate.claim(42, slot));
    }

    @Test
    void unarmedShaderApplyNeedsNoGlContext() {
        assertDoesNotThrow(() -> GroundFarDrawDiagnostics.applied(null));
    }

    @Test
    void readsTheBoundProgramEffectiveSamplerAndActualBindingAndRestoresActiveUnit() {
        var gl = new Fake();
        var gpu = GroundFarDrawDiagnostics.readGpu(gl);
        assertEquals(17, gpu.get("currentProgram"));
        assertEquals(2, gpu.get("CPMaterials"));
        assertEquals(Map.of("unit", 6, "texture2D", 123, "samplerObject", 0), gpu.get("coverage"));
        var uniforms = (Map<?, ?>) gpu.get("uniforms");
        assertEquals(5f, uniforms.get("CPNativeFace"));
        assertFalse(uniforms.containsKey("CPNativeStitch"));
        assertFalse(uniforms.containsKey("CPNativeCover"));
        assertEquals(GL13.GL_TEXTURE7, gl.active);
        assertEquals(2, gl.switches);
        assertTrue(gpu.get("textureContents").toString().startsWith("UNOBSERVED"));
    }

    @Test
    void failedBindingQueryStillRestoresTheActualActiveUnit() {
        var gl = new Fake();
        gl.failBinding = true;
        assertThrows(IllegalStateException.class, () -> GroundFarDrawDiagnostics.readGpu(gl));
        assertEquals(GL13.GL_TEXTURE7, gl.active);
        assertEquals(2, gl.switches);
    }

    @Test
    void inactiveCoverageUniformNeverSelectsOrQueriesATextureUnit() {
        var gl = new Fake();
        gl.locations.remove(GroundFaceArenas.MASK_UNIFORM);
        assertEquals(
                Map.of("status", "INACTIVE"), GroundFarDrawDiagnostics.readGpu(gl).get("coverage"));
        assertEquals(0, gl.switches);
        assertEquals(0, gl.bindingQueries);
    }

    @Test
    void noActiveProgramIsUnavailableAndNeverQueriesUniformsOrBindings() {
        var gl = new Fake();
        gl.program = 0;
        var gpu = GroundFarDrawDiagnostics.readGpu(gl);
        assertEquals("UNOBSERVED: no active program", gpu.get("status"));
        assertEquals(0, gl.uniformQueries);
        assertEquals(0, gl.switches);
        assertEquals(0, gl.bindingQueries);
    }

    @Test
    void invalidSamplerUnitDoesNotChangeActiveTexture() {
        var gl = new Fake();
        gl.unit = 99;
        assertEquals(
                Map.of("unit", 99, "status", "INVALID_UNIT"),
                GroundFarDrawDiagnostics.readGpu(gl).get("coverage"));
        assertEquals(0, gl.switches);
        assertEquals(0, gl.bindingQueries);
    }

    private static final class Fake implements GroundFarDrawDiagnostics.Gpu {
        final Map<String, Integer> locations =
                new HashMap<>(
                        Map.of(
                                "CPNativeFace",
                                1,
                                "CPNativeCover",
                                2,
                                GroundFaceArenas.MASK_UNIFORM,
                                3,
                                "CPMaterials",
                                4));
        int active = GL13.GL_TEXTURE7, program = 17, unit = 6;
        int switches, bindingQueries, uniformQueries;
        boolean failBinding;

        public int integer(int name) {
            return switch (name) {
                case GL20.GL_CURRENT_PROGRAM -> program;
                case GL13.GL_ACTIVE_TEXTURE -> active;
                case GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS -> 16;
                case GL11.GL_TEXTURE_BINDING_2D -> {
                    bindingQueries++;
                    assertEquals(GL13.GL_TEXTURE0 + unit, active);
                    if (failBinding) throw new IllegalStateException("fixture failure");
                    yield 123;
                }
                case GL33.GL_SAMPLER_BINDING -> 0;
                default -> throw new AssertionError("Unexpected GL query " + name);
            };
        }

        public void activeTexture(int selected) {
            switches++;
            active = selected;
        }

        public int location(int selectedProgram, String name) {
            assertEquals(17, selectedProgram);
            uniformQueries++;
            return locations.getOrDefault(name, -1);
        }

        public int uniformInt(int selectedProgram, int location) {
            assertEquals(17, selectedProgram);
            assertTrue(location == 3 || location == 4);
            return location == 3 ? unit : 2;
        }

        public float uniformFloat(int selectedProgram, int location) {
            assertEquals(17, selectedProgram);
            assertTrue(location == 1 || location == 2);
            return location == 1 ? 5 : 1;
        }

        public boolean samplerObjects() {
            return true;
        }
    }
}
