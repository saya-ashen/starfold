package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.TestArchiveResources;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Offline lifecycle contracts against the actual vanilla/Veil classes; no GPU is bootstrapped. */
class AtmosphereProgramStateTest {
    private ClassNode type(String name) throws Exception {
        try (var in = TestArchiveResources.open("/" + name + ".class")) {
            assertNotNull(in, name);
            var node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    private MethodNode method(String owner, String name) throws Exception {
        return type(owner).methods.stream()
                .filter(m -> m.name.equals(name))
                .findFirst()
                .orElseThrow();
    }

    private List<MethodInsnNode> calls(MethodNode method) {
        var out = new ArrayList<MethodInsnNode>();
        for (var i : method.instructions) if (i instanceof MethodInsnNode c) out.add(c);
        return out;
    }

    @Test
    void nativeAndRegionPrepareTheSameManagedSurfaceColorsBeforeDrawing() throws Exception {
        String owner = "dev/starfold/render/GroundSunRenderer";
        for (String entry : List.of("draw", "drawRegion")) {
            var names = calls(method(owner, entry)).stream().map(c -> c.name).toList();
            assertEquals(1, Collections.frequency(names, "surface"));
            assertTrue(names.indexOf("surface") < names.indexOf("drawWithShader"));
        }
        var surface = method(owner, "surface");
        var fields = new TreeSet<String>();
        var uniforms = new TreeSet<String>();
        for (var i : surface.instructions) {
            if (i instanceof FieldInsnNode f && f.owner.equals("dev/starfold/chart/SunSettings"))
                fields.add(f.name);
            if (i instanceof LdcInsnNode c && c.cst instanceof String s) uniforms.add(s);
        }
        assertEquals(Set.of("SURFACE_LOW", "SURFACE_HIGH"), fields);
        assertEquals(Set.of("SurfaceLow", "SurfaceHigh"), uniforms);
        assertEquals(2, calls(surface).stream().filter(c -> c.name.equals("vector")).count());
        var setters = calls(method(owner, "vector")).stream().map(c -> c.name).toList();
        assertTrue(setters.indexOf("getUniform") < setters.indexOf("set"));
        for (var m : type(owner).methods)
            assertFalse(
                    calls(m).stream()
                            .anyMatch(c -> c.name.equals("upload") && c.owner.endsWith("Uniform")),
                    m.name);
    }

    @Test
    void sunHaloUsesPerDiscUniversePositionsAndExplicitRegionVacuum() throws Exception {
        var nativeDraw = calls(method("dev/starfold/render/GroundSunRenderer", "draw"));
        var names = nativeDraw.stream().map(c -> c.name).toList();
        assertTrue(names.indexOf("suns") < names.indexOf("forSun"));
        assertTrue(names.indexOf("forSun") < names.indexOf("halo"));
        assertTrue(names.indexOf("halo") < names.indexOf("drawWithShader"));
        assertTrue(names.contains("universeEye"));
        assertTrue(names.contains("center"));
        assertFalse(names.contains("elevation"));
        var region = calls(method("dev/starfold/render/GroundSunRenderer", "drawRegion"));
        assertTrue(
                region.stream()
                        .anyMatch(c -> c.owner.endsWith("SunHalo") && c.name.equals("vacuum")));
        assertFalse(
                region.stream()
                        .anyMatch(
                                c ->
                                        c.owner.endsWith("GroundAtmosphere")
                                                || c.owner.endsWith("GroundClient")));
        var halo = calls(method("dev/starfold/render/GroundSunRenderer", "halo"));
        assertTrue(halo.stream().anyMatch(c -> c.name.equals("set")));
        assertFalse(halo.stream().anyMatch(c -> c.name.equals("upload")));
    }

    @Test
    void selectingShaderDoesNotBindAndImmediateDrawClearsItsProgram() throws Exception {
        var select = calls(method("com/mojang/blaze3d/systems/RenderSystem", "setShader"));
        assertFalse(
                select.stream()
                        .anyMatch(c -> c.name.equals("apply") || c.name.contains("UseProgram")));
        var draw = calls(method("com/mojang/blaze3d/vertex/VertexBuffer", "_drawWithShader"));
        var names = draw.stream().map(c -> c.name).toList();
        assertTrue(names.indexOf("setDefaultUniforms") < names.indexOf("apply"));
        assertTrue(names.indexOf("apply") < names.indexOf("draw"));
        assertTrue(names.indexOf("draw") < names.indexOf("clear"));
        var clear = method("net/minecraft/client/renderer/ShaderInstance", "clear");
        assertTrue(calls(clear).stream().anyMatch(c -> c.name.equals("glUseProgram")));
        assertTrue(
                Arrays.stream(clear.instructions.toArray())
                        .anyMatch(
                                i ->
                                        i instanceof FieldInsnNode f
                                                && f.getOpcode() == Opcodes.PUTSTATIC
                                                && f.name.equals("lastProgramId")));
        var apply = calls(method("net/minecraft/client/renderer/ShaderInstance", "apply"));
        var order = apply.stream().map(c -> c.name).toList();
        assertTrue(order.indexOf("glUseProgram") < order.indexOf("upload"));
        // Veil discovers extra managed values; its apply callback also uploads, not binds.
        var veil = calls(method("foundry/veil/mixin/shader/client/ShaderInstanceMixin", "apply"));
        assertTrue(veil.stream().anyMatch(c -> c.name.equals("upload")));
        assertFalse(veil.stream().anyMatch(c -> c.name.contains("UseProgram")));
    }

    @Test
    void bodyGradePreparationOnlySetsManagedValuesBeforeDraw() throws Exception {
        var owner = "dev/starfold/render/PlanetRemoteRenderer";
        for (var m : type(owner).methods) {
            assertFalse(
                    calls(m).stream().anyMatch(c -> c.owner.endsWith("ShaderUniforms")), m.name);
            assertFalse(
                    calls(m).stream()
                            .anyMatch(c -> c.name.equals("upload") && c.owner.endsWith("Uniform")),
                    m.name);
        }
        var body = calls(method(owner, "drawBody"));
        assertTrue(body.stream().anyMatch(c -> c.name.equals("vector")));
        var vector = calls(method(owner, "vector")).stream().map(c -> c.name).toList();
        assertTrue(vector.indexOf("getUniform") < vector.indexOf("set"));
        var source =
                Files.readString(
                        Path.of("src/main/java/dev/starfold/render/PlanetRemoteRenderer.java"));
        assertTrue(source.contains("vector(shader, \"AtmosphereGrade\", grade)"));
        assertTrue(source.contains("shader.getUniform(\"AtmosphereGrade\").set(1f, 1f, 1f)"));
    }

    @Test
    void immediateAtmosphereRestoresBindingAfterInvalidatingApplyCache() throws Exception {
        var draw = method("dev/starfold/render/GroundAtmosphere", "draw");
        var calls = calls(draw);
        var names = calls.stream().map(c -> c.name).toList();
        assertTrue(names.indexOf("glGetInteger") < names.indexOf("drawWithShader"));
        assertTrue(names.indexOf("drawWithShader") < names.indexOf("clear"));
        int restores = 0;
        for (int i = 0; i < names.size(); i++)
            if (names.get(i).equals("glUseProgram")) {
                // javac duplicates finally for normal and exceptional exits; check both copies.
                assertTrue(names.indexOf("clear") < i);
                assertEquals("close", names.get(i - 1));
                restores++;
            }
        assertTrue(restores >= 2);
        assertTrue(calls(method("dev/starfold/render/RenderPassState", "close")).stream()
                .anyMatch(c -> c.name.equals("setShader")));
        assertTrue(
                draw.tryCatchBlocks.stream().anyMatch(b -> b.type == null), "finally restoration");
        var source =
                Files.readString(
                        Path.of("src/main/java/dev/starfold/render/GroundAtmosphere.java"));
        assertTrue(source.contains("GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)"));
        assertTrue(source.contains("new RenderPassState(true, true, false)"));
        assertTrue(Files.readString(Path.of("src/main/java/dev/starfold/render/RenderPassState.java"))
                .contains("GlStateManager._backupGlState(saved)"));
        assertFalse(source.contains("glIsEnabled("));
        assertFalse(source.contains("glGetBoolean("));
        assertFalse(source.contains("GL_BLEND_SRC_RGB"));
        assertTrue(source.contains("pass.close()"));
        assertTrue(source.contains("ProgramManager.glUseProgram(program)"));
        assertFalse(
                source.contains("if (previous != null)"), "null selection must also be restored");
    }

    @Test
    void atmosphereEncodesOnlyAtVertexAndFogBoundariesNotStarVisibility() throws Exception {
        String owner = "dev/starfold/render/GroundAtmosphere";
        for (String name : List.of("drawSky", "rim", "fog")) {
            var names = calls(method(owner, name)).stream().map(c -> c.name).toList();
            assertEquals(1, Collections.frequency(names, "display"), name);
            assertTrue(
                    names.indexOf(name.equals("fog") ? "fog" : "sky") < names.indexOf("display"),
                    name);
        }
        var stars = calls(method(owner, "stars")).stream().map(c -> c.name).toList();
        assertTrue(stars.indexOf("sky") < stars.indexOf("stars"));
        assertFalse(stars.contains("display"));
        assertFalse(calls(method(owner, "grade")).stream().anyMatch(c -> c.name.equals("display")));
        var colorShader =
                Files.readString(
                        Path.of(
                                "src/main/resources/assets/starfold/shaders/core/atmosphere_color.fsh"));
        assertTrue(colorShader.contains("fragColor=vertexColor*ColorModulator"));
        assertFalse(colorShader.contains("pow("), "no second encoding in the fragment shader");
    }

    @Test
    void newSkyMatchesVanillaBlindnessAndDarknessGateBeforeIntegration() throws Exception {
        var vanilla =
                method("net/minecraft/client/renderer/LevelRenderer", "doesMobEffectBlockSky");
        var sky = method("dev/starfold/render/GroundAtmosphere", "drawSky");
        for (var m : List.of(vanilla, sky)) {
            var effects = new TreeSet<String>();
            for (var i : m.instructions)
                if (i instanceof FieldInsnNode f && f.owner.endsWith("MobEffects"))
                    effects.add(f.name);
            assertEquals(Set.of("BLINDNESS", "DARKNESS"), effects, m.name);
            assertTrue(calls(m).stream().anyMatch(c -> c.name.equals("getEntity")));
            assertEquals(2, calls(m).stream().filter(c -> c.name.equals("hasEffect")).count());
        }
        var names = calls(sky).stream().map(c -> c.name).toList();
        assertTrue(names.lastIndexOf("hasEffect") < names.indexOf("sky"));
        var lastEffect =
                calls(sky).stream().filter(c -> c.name.equals("hasEffect")).toList().getLast();
        boolean earlyReturn = false;
        for (var i = lastEffect.getNext(); i != null; i = i.getNext()) {
            if (i instanceof MethodInsnNode c && c.name.equals("sky")) break;
            if (i.getOpcode() == Opcodes.RETURN) earlyReturn = true;
        }
        assertTrue(earlyReturn, "effect blocks this pass before integration");
    }

    @Test
    void atmosphereSnapshotBeginsBeforeFogAndIsLazyWithinProjectedFrameOnly() throws Exception {
        String owner = "dev/starfold/render/GroundAtmosphere";
        var begin = calls(method("dev/starfold/render/GroundClient", "begin"));
        assertEquals(owner, begin.getFirst().owner);
        assertEquals("beginFrame", begin.getFirst().name);
        var context = calls(method(owner, "context")).stream().map(c -> c.name).toList();
        assertEquals("projectedFrame", context.getFirst());
        assertTrue(context.indexOf("beginFrame") < context.indexOf("air"));
        assertEquals(1, Collections.frequency(context, "sunlight"));
        var reset = method(owner, "beginFrame");
        var resetFields = new TreeSet<String>();
        for (var i : reset.instructions)
            if (i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTSTATIC)
                resetFields.add(f.name);
        assertEquals(Set.of("frameAir", "frameFog", "frameStars"), resetFields);
        for (var name : List.of("fog", "stars")) {
            var sample = method(owner, name);
            assertEquals("context", calls(sample).getFirst().name);
            boolean guarded = false;
            for (var i : sample.instructions) {
                if (i instanceof JumpInsnNode) guarded = true;
                if (i instanceof MethodInsnNode c && c.owner.endsWith("AtmosphereScattering")) {
                    assertTrue(guarded, "repeat " + name + " bypasses integration");
                    break;
                }
            }
            assertFalse(calls(sample).stream().anyMatch(c -> c.name.equals("sunlight")));
        }
        var source = Files.readString(Path.of("src/main/java/dev/starfold/render/GroundAtmosphere.java"));
        assertTrue(source.contains("air.density() == 0 || highAltitude == 1 || gasTransmission == 0"));
        assertTrue(source.contains("var color = black ? BLACK :"));
        assertTrue(source.contains("draw(null, skyMatrix(c), true)"), "keep the black background draw");
    }

    @Test
    void immediateUniformWriterOnlyRunsInPostApplyHookOrBoundLodBatch() throws Exception {
        var projection = calls(method("dev/starfold/render/GroundClient", "apply"));
        assertEquals(List.of("dev/starfold/render/GroundProjectionUniforms"),
                projection.stream().map(c -> c.owner).toList());
        assertTrue(calls(method("dev/starfold/render/GroundProjectionUniforms", "apply"))
                .stream().anyMatch(c -> c.owner.equals("dev/starfold/render/ShaderUniforms")
                        && c.name.equals("set")));
        var batch = calls(method("dev/starfold/render/GroundRenderer", "drawLods"));
        var batchNames = batch.stream().map(c -> c.name).toList();
        assertTrue(batchNames.indexOf("apply") < batchNames.indexOf("set"));
        assertTrue(batchNames.indexOf("apply") < batchNames.indexOf("drawLodMesh"));
        assertTrue(batchNames.lastIndexOf("drawLodMesh") < batchNames.indexOf("clear"));
        var draw = calls(method("dev/starfold/render/GroundRenderer", "drawLodMesh"));
        assertTrue(
                draw.stream().map(c -> c.name).toList().indexOf("set")
                        < draw.stream().map(c -> c.name).toList().indexOf("draw"));
        var wrapper =
                calls(method("dev/starfold/mixin/CoreShaderShaderMixin", "starfold$capUniforms"));
        assertEquals(List.of("call", "apply", "apply"), wrapper.stream().map(c -> c.name).toList());
        assertEquals("dev/starfold/render/GroundClient", wrapper.get(1).owner);
        assertEquals("dev/starfold/api/PlanetSurfaceRender", wrapper.get(2).owner,
                "optional exterior appearance uploads only after the program is bound");
        assertTrue(calls(method("dev/starfold/render/GroundProjectionUniforms", "apply")).stream()
                .anyMatch(c -> c.owner.equals("dev/starfold/api/PlanetSurfaceRender") && c.name.equals("nearUniforms")));
        var writer = type("dev/starfold/render/ShaderUniforms");
        int setters = 0;
        for (var m : writer.methods)
            if (m.name.equals("set") || m.name.equals("setInt")) {
                var names = calls(m).stream().map(c -> c.name).toList();
                assertTrue(names.indexOf("getUniform") < names.indexOf("set"));
                assertTrue(names.indexOf("set") < names.indexOf("upload"));
                assertTrue(names.stream().anyMatch(n -> n.startsWith("glUniform")));
                setters++;
            }
        assertEquals(5, setters, "managed and raw paths, including integer sampler");
    }
}
