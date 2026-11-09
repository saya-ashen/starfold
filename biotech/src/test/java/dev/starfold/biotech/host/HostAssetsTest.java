package dev.starfold.biotech.host;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.loading.object.BakedModelFactory;
import software.bernie.geckolib.loading.object.GeometryTree;
import software.bernie.geckolib.util.RenderUtil;
import org.junit.jupiter.api.Test;
import software.bernie.geckolib.loading.json.raw.Model;
import software.bernie.geckolib.loading.json.typeadapter.KeyFramesAdapter;
import software.bernie.geckolib.loading.object.BakedAnimations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Set;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class HostAssetsTest {
    private static String asset(String path) throws IOException {
        try (var stream = HostAssetsTest.class.getResourceAsStream("/assets/starfold_biotech/" + path)) {
            assertNotNull(stream, path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test void bakedGeometryMatchesRegisteredCollisionDimensions() throws IOException {
        assertSize("host_sac", HostEntities.SAC.get(), .15);
        // Wings are included, not just the drone's central body.
        assertSize("host_drone", HostEntities.DRONE.get(), .15);
    }

    private static void assertSize(String name, EntityType<?> type, double tolerance) throws IOException {
        var raw = KeyFramesAdapter.GEO_GSON.fromJson(asset("geo/" + name + ".geo.json"), Model.class);
        var baked = BakedModelFactory.DEFAULT_FACTORY.constructGeoModel(GeometryTree.fromModel(raw));
        AABB bounds = null;
        for (var bone : baked.topLevelBones()) {
            var next = bounds(bone, new PoseStack());
            bounds = bounds == null ? next : bounds.minmax(next);
        }
        assertNotNull(bounds);
        var size = type.getDimensions();
        assertEquals(size.height(), bounds.getYsize(), tolerance, name + " height (16 geo pixels = 1 block)");
        assertEquals(size.width(), bounds.getXsize(), tolerance, name + " width");
        assertEquals(size.width(), bounds.getZsize(), tolerance, name + " depth");
        assertEquals(0, bounds.minY, .01, name + " feet on origin");
    }

    private static AABB bounds(GeoBone bone, PoseStack poses) {
        poses.pushPose();
        RenderUtil.prepMatrixForBone(poses, bone);
        AABB bounds = null;
        for (var cube : bone.getCubes()) {
            poses.pushPose();
            RenderUtil.translateToPivotPoint(poses, cube);
            RenderUtil.rotateMatrixAroundCube(poses, cube);
            RenderUtil.translateAwayFromPivotPoint(poses, cube);
            for (var quad : cube.quads()) for (var vertex : quad.vertices()) {
                var point = poses.last().pose().transformPosition(new Vector3f(vertex.position()));
                var at = new AABB(point.x, point.y, point.z, point.x, point.y, point.z);
                bounds = bounds == null ? at : bounds.minmax(at);
            }
            poses.popPose();
        }
        for (var child : bone.getChildBones()) {
            var childBounds = bounds(child, poses);
            if (childBounds != null) bounds = bounds == null ? childBounds : bounds.minmax(childBounds);
        }
        poses.popPose();
        return bounds;
    }

    @Test void meteoriteBodyFitsSizeContractWithoutRoots() throws IOException {
        var raw = KeyFramesAdapter.GEO_GSON.fromJson(asset("geo/host.geo.json"), Model.class);
        var baked = BakedModelFactory.DEFAULT_FACTORY.constructGeoModel(GeometryTree.fromModel(raw));
        var root = baked.topLevelBones().getFirst();
        var body = root.getChildBones().stream().filter(bone -> bone.getName().equals("body")).findFirst().orElseThrow();
        var hanging = body.getChildBones().stream().filter(bone -> bone.getName().equals("hanging_roots")).findFirst().orElseThrow();
        body.getChildBones().remove(hanging);
        // Flight thrusters only unfold after takeoff, like the hanging roots.
        body.getChildBones().removeIf(bone -> bone.getName().equals("thrusters"));
        var bounds = bounds(root, new PoseStack());
        // Ground roots are separate from the collision body and extend to roughly eight blocks.
        var bodyBounds = bounds(body, new PoseStack());
        assertTrue(bodyBounds.minX >= -5 && bodyBounds.maxX <= 5, "body inside 10-block width");
        assertTrue(bodyBounds.minZ >= -5 && bodyBounds.maxZ <= 5, "body inside 10-block depth");
        assertEquals(0, bodyBounds.minY, .01, "feet on origin");
        assertTrue(bodyBounds.maxY <= 9 && bodyBounds.maxY >= 8, "meteorite inside 9-block height");
        assertTrue(bounds.getXsize() >= 14 && bounds.getXsize() <= 17, "spreading ground roots");
        var mouth = JsonParser.parseString(asset("geo/host.geo.json")).getAsJsonObject()
                .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones");
        for (var entry : mouth) if (entry.getAsJsonObject().get("name").getAsString().equals("mouth")) {
            assertEquals(64, entry.getAsJsonObject().getAsJsonArray("pivot").get(1).getAsInt());
            assertTrue(entry.getAsJsonObject().getAsJsonArray("pivot").get(2).getAsInt() < 0);
        }
    }

    @Test void meteoriteOrgansKeepTheirAnimationAndColorContracts() throws IOException {
        var raw = KeyFramesAdapter.GEO_GSON.fromJson(asset("geo/host.geo.json"), Model.class);
        var bones = new HashMap<String, software.bernie.geckolib.loading.json.raw.Bone>();
        for (var bone : raw.minecraftGeometry()[0].bones()) bones.put(bone.name(), bone);
        var animations = JsonParser.parseString(asset("animations/host.animation.json"))
                .getAsJsonObject().getAsJsonObject("animations");
        assertEquals(Set.of("idle", "fly", "roots", "vent", "spit", "shell_break", "takeoff", "dive", "egg_drop", "death"), animations.keySet());
        for (var entry : animations.entrySet()) {
            var clip = entry.getValue().getAsJsonObject();
            assertEquals(Set.of("idle", "fly").contains(entry.getKey()), clip.get("loop").getAsBoolean());
            for (String name : clip.getAsJsonObject("bones").keySet()) assertTrue(bones.containsKey(name), name);
        }
        for (int i = 0; i < 8; i++) {
            assertEquals("shell", bones.get("rock_" + i).parent());
            assertEquals("roots", bones.get("ground_" + i).parent());
            assertEquals("ground_" + i, bones.get("ground_tip_" + i).parent());
            assertEquals("hanging_roots", bones.get("hang_" + i).parent());
            assertEquals("hang_" + i, bones.get("hang_tip_" + i).parent());
            assertTrue(animations.getAsJsonObject("shell_break").getAsJsonObject("bones").has("rock_" + i));
            assertTrue(animations.getAsJsonObject("roots").getAsJsonObject("bones").has("ground_tip_" + i));
            assertTrue(animations.getAsJsonObject("fly").getAsJsonObject("bones").has("hang_tip_" + i));
        }
        assertEquals("thorax", bones.get("neck").parent());
        assertEquals("neck", bones.get("head").parent());
        assertEquals("head", bones.get("mouth").parent());
        for (String action : new String[]{"roots", "vent"}) {
            assertTrue(animations.getAsJsonObject(action).getAsJsonObject("bones").has("head"));
        }
        for (String side : new String[]{"l", "r"}) {
            assertEquals("engine", bones.get("engine_" + side).parent());
            assertEquals("engine_" + side, bones.get("blaze_" + side).parent());
        }

        try (var atlasStream = getClass().getResourceAsStream("/assets/starfold_biotech/textures/entity/host.png");
             var glowStream = getClass().getResourceAsStream("/assets/starfold_biotech/textures/entity/host_glowmask.png")) {
            var atlas = ImageIO.read(atlasStream);
            var glow = ImageIO.read(glowStream);
            assertEquals(atlas.getWidth(), glow.getWidth());
            assertEquals(atlas.getHeight(), glow.getHeight());
            for (var bone : bones.values()) for (var cube : bone.cubes()) {
                var uv = cube.uv().faceUV().north().uv();
                int x = (int) uv[0] + 16, y = (int) uv[1] + 16;
                int color = atlas.getRGB(x, y), r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
                boolean engine = bone.name().startsWith("blaze_") || bone.name().matches("thruster_[lr]");
                assertEquals(engine ? color : 0, glow.getRGB(x, y), bone.name() + " emissive mask");
                if (engine) assertTrue(r > 220 && b > g, "bright pink engine");
                if (bone.name().equals("teeth")) assertTrue(r > 140 && r > g && g > b, "ivory teeth");
                if (bone.name().startsWith("rock_")) assertTrue(Math.max(r, Math.max(g, b)) < 120, "scorched rock");
                if (bone.name().equals("eggs")) assertTrue(r > 150 && g > 100, "pale egg membranes");
                if (bone.name().startsWith("hang")) assertTrue(r > 90 && g > 45, "flesh-coloured hanging tendons");
            }
        }
    }

    @Test void pinnedGeckoLibParsesEveryModelAndEveryAnimation() throws IOException {
        for (String name : new String[]{"host", "host_sac", "host_drone"}) {
            var model = KeyFramesAdapter.GEO_GSON.fromJson(asset("geo/" + name + ".geo.json"), Model.class);
            assertEquals(1, model.minecraftGeometry().length);
            var source = JsonParser.parseString(asset("animations/" + name + ".animation.json"))
                    .getAsJsonObject().getAsJsonObject("animations");
            var baked = KeyFramesAdapter.GEO_GSON.fromJson(source, BakedAnimations.class);
            assertEquals(source.size(), baked.animations().size());
            for (String clip : source.keySet()) assertNotNull(baked.getAnimation(clip), name + ":" + clip);
        }
    }
}
