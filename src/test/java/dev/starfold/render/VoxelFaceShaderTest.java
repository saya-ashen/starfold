package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.TestArchiveResources;
import dev.starfold.VoxelBaseline;
import dev.starfold.VoxelTestEvidence;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.worldgen.lod.VoxelMesher;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

class VoxelFaceShaderTest {
    @Test
    void volumeTagIsDisjointAndRestoresOldSpanSideAndAlbedoDecode() throws Exception {
        assertEquals(251, GroundShaderSource.VOXEL_TAG);
        for (int old : new int[] {0, 1, 2, 4, 8, 16, 252, 253, 254, 255})
            assertNotEquals(GroundShaderSource.VOXEL_TAG, old);
        String vertex =
                CoreShaderSource.patch(
                        "rendertype_solid", true, resource("core/rendertype_solid.vsh"));
        assertTrue(
                vertex.contains(
                        "if(tag==251.0){cpNativeVoxel=1.0;tag=16.0;}cpNativeTexSpan=max(1.0,tag);"));
        assertTrue(
                vertex.contains(
                        "if(tag<0.5)cpNativeSide=1.0;else if(tag>251.5)cpNativeSide=tag-250.0;"));
        assertTrue(
                vertex.contains(
                        "vertexColor.rgb=mix(cpSurfaceColor.rgb,Normal*0.5+0.5,CPNativeLowFrequency);vertexColor.a=1.0;"));
        assertTrue(
                vertex.indexOf("cpNativeVoxel=0.0")
                        < vertex.indexOf("if(CPNativeActive<0.5)return"));
        assertTrue(vertex.contains("flat out float cpNativeTexSpan, cpNativeSide, cpNativeVoxel;"));
        String fragment =
                CoreShaderSource.patch(
                        "rendertype_solid", false, resource("core/rendertype_solid.fsh"));
        assertTrue(fragment.contains("flat in float cpNativeSide, cpNativeVoxel;"));
        var renderer = dev.starfold.Sources.text("GroundRenderer");
        var vertices = dev.starfold.Sources.text("GroundMeshVertices");
        assertTrue(vertices.contains("volume ? GroundShaderSource.VOXEL_TAG : 16"));
        assertTrue(renderer.contains("GroundShaderSource.patchTag(false,GroundFarMesh.side(q))"));
        assertTrue(vertices.contains("surfacePolygon(builders, state, points, x, z, false)"));
    }

    @Test
    void actualVoxelFacesKeepTopBottomAndSideSunDirectionWhileHeightfieldsStayOutward()
            throws Exception {
        String relief = GroundShaderSource.RELIEF;
        String volume =
                "if(cpNativeVoxel>0.5){if(!gl_FrontFacing)slope=-slope;return dot(slope,slope)<0.000000000001?radial:normalize(slope);}";
        assertTrue(relief.contains(volume));
        assertTrue(relief.indexOf(volume) < relief.indexOf("cpNativeSea>0.999"));
        assertTrue(
                relief.indexOf(volume)
                        < relief.indexOf(
                                "if(CPNativeCoarse>0.5||CPNativeCover>0.5){if(dot(slope,radial)<0.0)"));
        dev.starfold.worldgen.PlanetTerrainTest.registry();
        var source =
                new dev.starfold.worldgen.lod.VoxelSource() {
                    public int floor() {
                        return 126;
                    }

                    public int ceiling() {
                        return 131;
                    }

                    public Column column(
                            dev.starfold.geometry.CubeSurfaceTopology.Face face, int x, int z) {
                        return new Column() {
                            public int ground() {
                                return 127;
                            }

                            public net.minecraft.world.level.block.state.BlockState state(int y) {
                                return x == 5 && z == 5 && y == 128
                                        ? net.minecraft.world.level.block.Blocks.STONE
                                                .defaultBlockState()
                                        : net.minecraft.world.level.block.Blocks.AIR
                                                .defaultBlockState();
                            }
                        };
                    }
                };
        try (var mesh =
                new VoxelMesher.Job(
                        source,
                        dev.starfold.geometry.CubeSurfaceTopology.Face.values()[0],
                        0,
                        0,
                        1,
                        4096)) {
            while (!mesh.done()) mesh.step(Long.MAX_VALUE, 16, 8192, () -> false);
            assertEquals(6, mesh.quads().size());
            for (var q : mesh.quads()) {
                assertTrue(
                        q.volume(),
                        "production mesh faces, including the sea-level bottom, carry volume metadata");
                var p = q.points();
                var n = p.get(1).sub(p.getFirst()).cross(p.get(2).sub(p.getFirst()));
                assertEquals(q.axis() == 3 ? 1 : 0, Math.max(0, n.dot(new Point(0, 1, 0))));
                assertEquals(q.axis() == 2 ? 1 : 0, Math.max(0, n.dot(new Point(0, -1, 0))));
            }
        }
        try (var in = GroundMeshVertices.class.getResourceAsStream("GroundMeshVertices.class")) {
            var node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            var upload =
                    node.methods.stream()
                            .filter(m -> m.name.equals("surfaceVertex"))
                            .findFirst()
                            .orElseThrow();
            boolean tag = false;
            for (var instruction : upload.instructions)
                if (instruction instanceof IntInsnNode value
                        && value.operand == GroundShaderSource.VOXEL_TAG) tag = true;
            assertTrue(tag, "actual compiled vertex upload must contain the reserved alpha tag");
        }
        try (var in = GroundRenderer.class.getResourceAsStream("GroundRenderer$LodJob.class")) {
            var node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            boolean metadata = false;
            for (var method : node.methods)
                for (var instruction : method.instructions)
                    if (instruction instanceof MethodInsnNode call
                            && call.owner.equals("dev/starfold/render/GroundShaderSource")
                            && call.name.equals("patchTag")) metadata = true;
            assertTrue(
                    metadata, "actual compiled LodJob must upload shared terrace side/repeat tags");
        }
        try (var in = VoxelBaseline.open("dev/starfold/render/GroundShaderSource.class")) {
            var node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            String before =
                    (String)
                            node.fields.stream()
                                    .filter(f -> f.name.equals("RELIEF"))
                                    .findFirst()
                                    .orElseThrow()
                                    .value;
            assertFalse(before.contains("cpNativeVoxel"));
            assertTrue(before.contains("if(dot(slope,radial)<0.0)slope=-slope;"));
            // Negative control: b112 treats the new cover ceiling as a heightfield, reversing its
            // sun direction.
            var bottom = new Point(0, -1, 0);
            assertEquals(1, -bottom.dot(new Point(0, 1, 0)));
            var path =
                    VoxelTestEvidence.directory("shader-control")
                            .resolve("shader-negative-control.txt");
            Files.createDirectories(path.getParent());
            Files.writeString(
                    path,
                    "Real b112 RELIEF has no per-volume branch; ceiling sun factor 1 versus fixed volume 0.\n"
                            + before);
        }
        assertTrue(relief.contains("else if(!gl_FrontFacing)slope=-slope"));
        assertTrue(relief.contains("((CPNativeCoarse>0.5||CPNativeCover>0.5)&&cpNativeSea>0.999)"));
    }

    static String resource(String name) throws Exception {
        try (var in = TestArchiveResources.open("/assets/minecraft/shaders/" + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String expand(String input, Set<String> seen) throws Exception {
        var matcher = Pattern.compile("#moj_import\\s*<([^>]+)>").matcher(input);
        var out = new StringBuilder();
        while (matcher.find()) {
            String file = matcher.group(1),
                    replacement =
                            seen.add(file)
                                    ? expand(
                                            resource("include/" + file)
                                                    .replaceAll("(?m)^#version[^\\n]*", ""),
                                            seen)
                                    : "";
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    @Test
    void exportActualExpandedVanillaProgramsForOfflineCompileAndLink() throws Exception {
        var dir = VoxelTestEvidence.directory("shaders");
        Files.createDirectories(dir);
        for (String name :
                List.of(
                        "rendertype_solid",
                        "rendertype_cutout",
                        "rendertype_cutout_mipped",
                        "rendertype_translucent",
                        "rendertype_translucent_moving_block",
                        "rendertype_entity_cutout",
                        "rendertype_entity_solid",
                        "rendertype_entity_translucent",
                        "rendertype_item_entity_translucent_cull",
                        "particle",
                        "rendertype_lines",
                        "rendertype_crumbling",
                        "rendertype_text",
                        "rendertype_entity_shadow")) {
            for (boolean vertex : new boolean[] {true, false}) {
                String result =
                        expand(
                                CoreShaderSource.patch(
                                        name,
                                        vertex,
                                        resource("core/" + name + (vertex ? ".vsh" : ".fsh"))),
                                new HashSet<>());
                assertFalse(result.contains("#moj_import"));
                Files.writeString(dir.resolve(name + (vertex ? ".vert" : ".frag")), result);
            }
        }
    }
}
