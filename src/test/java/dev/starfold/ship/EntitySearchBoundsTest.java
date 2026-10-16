package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.starfold.Sources;

import net.minecraft.world.phys.AABB;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.Random;

class EntitySearchBoundsTest {
    @Test
    void fullPoseCabinAndAnchorGraceFitTheCrewSearch() {
        var random = new Random(20261009);
        var plot = new AABB(20_000_000, -104, 20_000_000, 20_000_080, -90, 20_000_030);
        for (int i = 0; i < 200; i++) {
            var pose =
                    new Pose3d(
                            new Vector3d(-16384, 8192, -16384),
                            new Quaterniond()
                                    .rotationXYZ(
                                            random.nextDouble(-3, 3),
                                            random.nextDouble(-3, 3),
                                            random.nextDouble(-3, 3)),
                            new Vector3d(20_000_006, -102, 20_000_010),
                            new Vector3d(
                                    random.nextDouble(.5, 2),
                                    random.nextDouble(.5, 2),
                                    random.nextDouble(.5, 2)));
            var cabin = SableFrames.cabinBounds(pose, plot);
            var crew = SableFrames.crewBounds(pose, cabin).inflate(1e-6);
            for (int j = 0; j < 100; j++) {
                var feet =
                        pose.transformPosition(
                                new Vector3d(
                                        random.nextDouble(plot.minX - .49, plot.maxX + .49),
                                        random.nextDouble(plot.minY - .49, plot.maxY + .49),
                                        random.nextDouble(plot.minZ - .49, plot.maxZ + .49)));
                assertTrue(SableFrames.inside(pose, plot.inflate(.5), feet));
                assertTrue(crew.contains(feet.x, feet.y, feet.z));
                var offset =
                        new Vector3d(
                                        random.nextDouble(-1, 1),
                                        random.nextDouble(-1, 1),
                                        random.nextDouble(-1, 1))
                                .normalize()
                                .mul(SableFrames.REACH)
                                .add(pose.position());
                assertTrue(crew.contains(offset.x, offset.y, offset.z));
            }
        }
    }

    @Test
    void tickAndCarryLookupsAreBoundedAndRetainExitCleanupAndRememberedCrew() throws Exception {
        var space = Sources.text("SpaceServer");
        assertFalse(space.contains("level.getAllEntities()"));
        assertTrue(space.contains("Double.NEGATIVE_INFINITY"));
        assertTrue(space.contains("Double.POSITIVE_INFINITY"));
        assertTrue(space.contains("weightless.entrySet().removeIf"));
        assertTrue(space.contains("living.level()==level&&Space.in(living)"));
        assertTrue(space.contains("regionEntities(level)"));
        var frames = Sources.text("SableFrames");
        assertFalse(frames.contains("level.getAllEntities()"));
        assertTrue(frames.contains("crewBounds(before,cabinBounds(body))"));
        assertTrue(frames.contains("cabinBounds(body.lastPose(),plot.toAABB())"));
        assertTrue(frames.contains("entry.getValue().body()==body&&entry.getKey().level()==level"));
        assertTrue(frames.contains("getIndirectPassengers()"));
        var seams = Sources.text("GroundSeams");
        assertTrue(seams.contains("starfold$entities()"));
        assertTrue(seams.contains("getIgnoringSubLevels(box,found::add)"));
    }
}
