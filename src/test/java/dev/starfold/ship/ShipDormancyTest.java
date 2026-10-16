package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.util.SableNBTUtils;
import dev.starfold.Sources;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.sky.Space;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.UUID;

class ShipDormancyTest {
    @Test
    void copiedLoadTagPreservesPlotAndUnknownFieldsAndTransformsEveryCorner() {
        var anchor = new Point(20000003, -99, 20000002);
        var old =
                new Pose3d(
                        new Vector3d(-16300, 8200, -16400),
                        new Quaterniond().rotateXYZ(.2, .3, .1),
                        new Vector3d(anchor.x(), anchor.y(), anchor.z()),
                        new Vector3d(1));
        var box = new BoundingBox3d(-16320, 8190, -16410, -16270, 8210, -16370);
        var tag = new CompoundTag();
        var uuid = UUID.randomUUID();
        tag.putUUID("uuid", uuid);
        tag.put("pose", SableNBTUtils.writePose3d(old));
        tag.put("world_bounds", SableNBTUtils.writeBoundingBox(box));
        var plot = new CompoundTag();
        plot.putInt("plot_x", 1250000);
        plot.putString("chunks", "unchanged payload sentinel");
        tag.put("plot", plot);
        tag.putString("unknown", "keep");
        tag.putString("display_name", "Hull");
        tag.put("linear_velocity", SableNBTUtils.writeVector3d(new Vector3d(3, 5, 7)));
        tag.put("angular_velocity", SableNBTUtils.writeVector3d(new Vector3d(1, 2, 3)));
        var original = SubLevelSerializer.fromData(tag);
        original.setOriginLoadedChunk(new ChunkPos(-1024, -1024));
        var motion =
                new Space.Motion(
                        new Point(-14000, 9000, -18000),
                        new Quaterniond().rotateXYZ(.7, 1.3, -.2),
                        new Point(1, 2, 3),
                        new Vector3d(.1));
        var place = new ShipRecord.Place(1, anchor, new Point(1, 2, 3), new Quaterniond(), 100);
        var before = tag.copy();
        var result = ShipDormancy.repose(original, place, motion);
        assertEquals(before, tag);
        assertNotSame(tag, result.fullTag());
        assertEquals(uuid, result.uuid());
        assertEquals(plot, result.fullTag().getCompound("plot"));
        assertEquals("keep", result.fullTag().getString("unknown"));
        assertEquals("Hull", result.fullTag().getString("display_name"));
        assertEquals(original.getOriginLoadedChunk(), result.getOriginLoadedChunk());
        assertFalse(result.fullTag().contains("linear_velocity"));
        assertFalse(result.fullTag().contains("angular_velocity"));
        var pose = ShipRecords.serializedPose(result);
        assertEquals(result.pose().position(), pose.position());
        assertEquals(motion.orientation(), new Quaterniond(pose.orientation()));
        assertEquals(
                new Vector3d(motion.position().x(), motion.position().y(), motion.position().z()),
                pose.transformPosition(new Vector3d(anchor.x(), anchor.y(), anchor.z())));
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int i = 0; i < 8; i++) {
            var p =
                    pose.transformPosition(
                            old.transformPositionInverse(
                                    new Vector3d(
                                            (i & 1) == 0 ? box.minX : box.maxX,
                                            (i & 2) == 0 ? box.minY : box.maxY,
                                            (i & 4) == 0 ? box.minZ : box.maxZ)));
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
            maxZ = Math.max(maxZ, p.z);
        }
        assertEquals(
                new net.minecraft.world.phys.AABB(minX, minY, minZ, maxX, maxY, maxZ),
                new net.minecraft.world.phys.AABB(
                        result.bounds().minX,
                        result.bounds().minY,
                        result.bounds().minZ,
                        result.bounds().maxX,
                        result.bounds().maxY,
                        result.bounds().maxZ));
        assertTrue(ShipDormancy.fits(result.bounds()));
        assertFalse(ShipDormancy.fits(new BoundingBox3d(-100, 8000, -16000, 100, 8010, -15990)));
        assertFalse(
                ShipDormancy.fits(
                        new BoundingBox3d(
                                -16000, Space.SABLE_MIN_Y - 1, -16000, -15990, 9000, -15990)));
    }

    @Test
    void wakeAndSleepKeepTheReadinessAndPublicationBoundaries() throws Exception {
        var source = Sources.text("ShipDormancy");
        var tickets = Sources.text("SableTickets");
        assertTrue(
                tickets.indexOf("ShipDormancy.prepare")
                        < tickets.indexOf("apply(level,ShipLeases.plan(inputs))"));
        assertTrue(
                tickets.indexOf("apply(level,ShipLeases.plan(inputs))")
                        < tickets.indexOf("ShipDormancy.finish"));
        assertTrue(source.indexOf("isChunkLoadedEnough") < source.indexOf("snatchAndLoad("));
        assertTrue(source.contains("!original.dependencies().isEmpty()"));
        assertTrue(source.contains("!SableTickets.input(level,body).reasons().isEmpty()"));
        assertTrue(source.contains("!ShipHost.get().spaceDormancy()"));
        assertTrue(tickets.contains("ShipLeases.Reason.LEAD"));
        assertTrue(tickets.contains("ShipLeases.Reason.DEPENDENCY"));
        var hook = Sources.text("SableShipStorageMixin");
        assertTrue(
                hook.indexOf("ShipDormancy.loadInput(level,originalData)")
                        < hook.indexOf("original.call(level,data)"));
        assertTrue(
                hook.indexOf("original.call(level,data)")
                        < hook.indexOf("ShipDormancy.loaded(level,data,hull)"));
        assertTrue(hook.contains("if(ShipDormancy.defer(level,ship))ci.cancel()"));
        assertTrue(
                Sources.text("SableShipReadinessMixin")
                        .contains(
                                "if(ShipDormancy.managed(level,data.uuid()))ci.setReturnValue(false)"));
        assertTrue(Sources.text("SableBoarding").contains("ShipDormancy.crew(player)"));
        assertFalse(source.contains("addForceLoadTicket"));
        assertFalse(source.contains("SableFrames.move("));
    }
}
