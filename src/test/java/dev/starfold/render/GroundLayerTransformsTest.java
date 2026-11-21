package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.ground.ObserverFrame;
import dev.starfold.planet.PlanetAccess;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class GroundLayerTransformsTest {
    // GroundClient.place's arithmetic with explicit inputs, avoiding a live Minecraft client.
    private static Matrix4f placement(
            Matrix4f modelView, Matrix4f pose, ObserverFrame frame, Vec3 camera, int face) {
        var origin = frame.toClient(PlanetAccess.SHARED.origin(frame.planet(), face));
        return new Matrix4f(modelView)
                .mul(pose)
                .translate(
                        (float) (origin.x() - camera.x),
                        (float) (origin.y() - camera.y),
                        (float) (origin.z() - camera.z))
                .rotateY((float) (-frame.turns() * Math.PI / 2));
    }

    @Test
    void lazyCopiesMatchUncachedPlacementForAllFacesAndTurnsAndRemainIndependent() {
        var modelView = new Matrix4f().rotateXYZ(.31f, -.72f, .19f).translate(11, -7, 23);
        var pose = new Matrix4f().translate(-19, 41, 13).rotateY(.27f).scale(.8f, 1.1f, 1.3f);
        for (int turns = 0; turns < 4; turns++) {
            var frame = new ObserverFrame(0, 2, turns, 432, -768);
            for (var camera : List.of(new Vec3(0, 0, 0), new Vec3(1024.125, 83.75, -912.5))) {
                int[] calls = new int[6];
                var transforms =
                        new GroundLayerTransforms(
                                face -> {
                                    calls[face]++;
                                    return placement(modelView, pose, frame, camera, face);
                                });
                assertArrayEquals(new int[6], calls, "empty scopes perform no placement");
                for (int face = 0; face < 6; face++) {
                    var expected = placement(modelView, pose, frame, camera, face);
                    var first = transforms.copy(face);
                    var second = transforms.copy(face);
                    assertEquals(expected, first);
                    assertEquals(expected, second);
                    assertNotSame(first, second);
                    first.zero();
                    assertEquals(expected, second, "an earlier consumer cannot alter another draw");
                    second.translate(97, -53, 29).rotateX(.91f);
                    assertEquals(
                            expected, transforms.copy(face), "consumers cannot alter the cache");
                }
                assertArrayEquals(new int[] {1, 1, 1, 1, 1, 1}, calls);
            }
        }
    }

}
