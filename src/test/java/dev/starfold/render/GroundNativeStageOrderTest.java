package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.TestArchiveResources;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.List;

/** Audits the actual game and production callbacks without a renderer or GL context. */
class GroundNativeStageOrderTest {
    private static ClassNode bytecode(String owner) throws Exception {
        try (var in = TestArchiveResources.open("/" + owner + ".class")) {
            assertNotNull(in, owner);
            var node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static List<Integer> stage(MethodNode method, String name) {
        var positions = new ArrayList<Integer>();
        for (int i = 0; i < method.instructions.size(); i++)
            if (method.instructions.get(i) instanceof FieldInsnNode field
                    && field.owner.endsWith("RenderLevelStageEvent$Stage")
                    && field.name.equals(name)) positions.add(i);
        return positions;
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        var previous = instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }

    @Test
    void actualNeoForgeRenderOrderPlacesLateNativeLayersBetweenOurDrawStages() throws Exception {
        var render = method(bytecode("net/minecraft/client/renderer/LevelRenderer"), "renderLevel");
        var entities = stage(render, "AFTER_ENTITIES");
        var particles = stage(render, "AFTER_PARTICLES");
        assertEquals(1, entities.size());
        assertEquals(2, particles.size(), "both fabulous and ordinary transparency paths");
        int opaqueCalls = 0, lateCalls = 0;
        for (int i = 0; i < render.instructions.size(); i++) {
            if (!(render.instructions.get(i) instanceof MethodInsnNode call)
                    || !call.owner.equals("net/minecraft/client/renderer/LevelRenderer")
                    || !call.name.equals("renderSectionLayer")) continue;
            // Other RenderType factories in this method draw entities/block outlines. Inspect only
            // the factory feeding a terrain-layer draw, the call wrapped by our confirmation hook.
            MethodInsnNode layer = null;
            for (var previous = call.getPrevious();
                    previous != null;
                    previous = previous.getPrevious()) {
                if (previous instanceof MethodInsnNode factory
                        && factory.owner.equals("net/minecraft/client/renderer/RenderType")) {
                    layer = factory;
                    break;
                }
            }
            assertNotNull(layer);
            if (List.of("solid", "cutoutMipped", "cutout").contains(layer.name)) {
                assertTrue(i < entities.getFirst(), layer.name);
                opaqueCalls++;
            } else {
                assertTrue(List.of("translucent", "tripwire").contains(layer.name), layer.name);
                assertTrue(i > entities.getFirst(), layer.name);
                int position = i;
                assertTrue(particles.stream().anyMatch(p -> p > position), layer.name);
                lateCalls++;
            }
        }
        assertEquals(3, opaqueCalls);
        assertEquals(4, lateCalls);
    }

    @Test
    void productionCallbacksRefreshTheMatchingPassBeforeDrawingAndFinalizing() throws Exception {
        var renderer = bytecode("dev/starfold/render/GroundRenderer");
        for (boolean late : new boolean[] {false, true}) {
            String event = late ? "AFTER_PARTICLES" : "AFTER_ENTITIES";
            var callback =
                    renderer.methods.stream()
                            .filter(m -> !stage(m, event).isEmpty())
                            .findFirst()
                            .orElseThrow();
            int refresh = -1, draw = -1, finish = -1;
            for (int i = 0; i < callback.instructions.size(); i++) {
                if (!(callback.instructions.get(i) instanceof MethodInsnNode call)
                        || !call.owner.equals("dev/starfold/render/GroundRenderer")) continue;
                if (!List.of("refreshNativeDraws", "draw", "completeNativeDraw")
                        .contains(call.name)) continue;
                assertEquals(
                        late ? Opcodes.ICONST_1 : Opcodes.ICONST_0,
                        previousCode(call).getOpcode(),
                        event + " " + call.name);
                switch (call.name) {
                    case "refreshNativeDraws" -> refresh = i;
                    case "draw" -> draw = i;
                    case "completeNativeDraw" -> finish = i;
                }
            }
            assertTrue(refresh >= 0 && refresh < draw && draw < finish, event);
        }
        var refresh = method(renderer, "refreshNativeDraws");
        var visible =
                List.of(refresh.instructions.toArray()).stream()
                        .filter(
                                i ->
                                        i instanceof MethodInsnNode call
                                                && call.owner.equals(
                                                        "dev/starfold/render/GroundNativeCoverage")
                                                && call.name.equals("visible"))
                        .map(i -> (MethodInsnNode) i)
                        .findFirst()
                        .orElseThrow();
        assertEquals("(Z)Ljava/util/Set;", visible.desc);
        assertInstanceOf(VarInsnNode.class, previousCode(visible));
        assertEquals(0, ((VarInsnNode) previousCode(visible)).var);
    }

    @Test
    void multisampleDepthSurvivesNativeCutoutUntilOpaqueLodCompletes() throws Exception {
        var nativeLayer = method(bytecode("dev/starfold/mixin/GroundNativeDrawMixin"), "starfold$sampleTerrain");
        long earlyResolveCalls = List.of(nativeLayer.instructions.toArray()).stream()
                .filter(i -> i instanceof MethodInsnNode call
                        && call.owner.equals("dev/starfold/render/GroundEdgeFilter")
                        && call.name.equals("endNative")).count();
        assertEquals(1, earlyResolveCalls, "only the failure handler may resolve a native layer");
        var opaque = bytecode("dev/starfold/render/GroundRenderer").methods.stream()
                .filter(m -> !stage(m, "AFTER_ENTITIES").isEmpty()).findFirst().orElseThrow();
        int completed = -1, resolved = -1;
        for (int i = 0; i < opaque.instructions.size(); i++) {
            if (!(opaque.instructions.get(i) instanceof MethodInsnNode call)) continue;
            if (call.name.equals("completeNativeDraw")) completed = i;
            if (call.owner.equals("dev/starfold/render/GroundEdgeFilter")
                    && call.name.equals("endNative") && resolved < 0) resolved = i;
        }
        assertTrue(completed >= 0 && resolved > completed, "resolve after native and LOD share depth");
    }

    @Test
    void confirmationFollowsActualBufferDrawAndFrameHeadResetsBothPasses() throws Exception {
        var mixin = bytecode("dev/starfold/mixin/GroundNativeDrawMixin");
        var wrapper =
                mixin.methods.stream()
                        .filter(
                                m ->
                                        List.of(m.instructions.toArray()).stream()
                                                .anyMatch(
                                                        i ->
                                                                i instanceof MethodInsnNode call
                                                                        && call.owner.equals(
                                                                                "dev/starfold/render/GroundNativeCoverage")
                                                                        && call.name.equals(
                                                                                "drawn")))
                        .findFirst()
                        .orElseThrow();
        int nativeDraw = -1, confirmation = -1;
        for (int i = 0; i < wrapper.instructions.size(); i++) {
            if (!(wrapper.instructions.get(i) instanceof MethodInsnNode call)) continue;
            if (call.owner.equals("com/llamalad7/mixinextras/injector/wrapoperation/Operation")
                    && call.name.equals("call")) nativeDraw = i;
            if (call.owner.equals("dev/starfold/render/GroundNativeCoverage")
                    && call.name.equals("drawn")) confirmation = i;
        }
        assertTrue(nativeDraw >= 0 && confirmation > nativeDraw);
        var begin = method(bytecode("dev/starfold/render/GroundNativeCoverage"), "beginFrame");
        assertTrue(
                List.of(begin.instructions.toArray()).stream()
                        .anyMatch(
                                i ->
                                        i instanceof MethodInsnNode call
                                                && call.name.equals("resetDraws")));
    }
}
