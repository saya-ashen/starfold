package dev.starfold.dev;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.math.OrientedBoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.LevelExtension;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import dev.starfold.ship.Airstair;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;

import org.joml.*;

/** Development-only pose census; SAT matches Sable's CanFallAtleastHelper, including its last-pose convention. */
public final class PilotPoseProbe {
    public static void sample(Player p, boolean fits) {
        var body = Sable.HELPER.getTrackingSubLevel(p);
        if (body == null) return;
        var level = p.level();
        var local =
                body.logicalPose()
                        .transformPositionInverse(new Vector3d(p.getX(), p.getY(), p.getZ()));
        var last =
                body.lastPose()
                        .transformPositionInverse(new Vector3d(p.getX(), p.getY(), p.getZ()));
        if (Boolean.getBoolean("starfold.proneProbe")) DevProneProbe.sample(p, fits);
        if (level.isClientSide)
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE CLOAK tick {} offsets {} world {} local {}",
                            level.getGameTime(),
                            java.util.List.of(
                                    p.xCloak - p.getX(), p.yCloak - p.getY(), p.zCloak - p.getZ()),
                            p.position(),
                            java.util.List.of(local.x, local.y, local.z));
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE RIDERPOSE side {} tick {} uuid {} pose {} fits {} feet {} lastFeet {} ground {} sneak {} delta {} collision {}",
                        level.isClientSide ? "client" : "server",
                        level.getGameTime(),
                        p.getUUID(),
                        p.getPose(),
                        fits,
                        java.util.Arrays.toString(new double[] {local.x, local.y, local.z}),
                        java.util.Arrays.toString(new double[] {last.x, last.y, last.z}),
                        p.onGround(),
                        p.isShiftKeyDown(),
                        p.getDeltaMovement(),
                        fits
                                ? "none"
                                : "last["
                                        + collision(p, false)
                                        + "] logical["
                                        + collision(p, true)
                                        + "]");
    }

    private static String collision(Player p, boolean current) {
        return collision(p, current ? 1 : 0);
    }

    static String collision(Player p, int mode) {
        var level = p.level();
        var aabb = p.getDimensions(Pose.STANDING).makeBoundingBox(p.position()).deflate(1e-7);
        var sink = ((LevelExtension) level).sable$getJOMLSink();
        sink.entityBoxOrientation.identity();
        var entity =
                new OrientedBoundingBox3d(
                        (aabb.minX + aabb.maxX) / 2,
                        (aabb.minY + aabb.maxY) / 2,
                        (aabb.minZ + aabb.maxZ) / 2,
                        aabb.getXsize() - .1,
                        aabb.getYsize(),
                        aabb.getZsize() - .1,
                        sink.entityBoxOrientation,
                        sink);
        var cube = new OrientedBoundingBox3d(sink);
        var near = new BoundingBox3d(aabb);
        near.expand(1.05, near);
        for (var body : Sable.HELPER.getAllIntersecting(level, near)) {
            var pose =
                    mode == 2 && body instanceof dev.ryanhcode.sable.sublevel.ClientSubLevel client
                            ? client.renderPose(.5f)
                            : mode == 1 ? body.logicalPose() : body.lastPose();
            var bounds = new BoundingBox3d(aabb);
            bounds.expand(-.05, 0, -.05, bounds);
            bounds.transformInverse(pose, new Matrix4d(), bounds);
            cube.getOrientation().set(pose.orientation());
            sink.entityBoxOrientation
                    .identity()
                    .rotateY(SubLevelEntityCollision.getHitBoxYaw(pose));
            entity.setOrientation(sink.entityBoxOrientation);
            for (var at :
                    BlockPos.betweenClosed(
                            BlockPos.containing(bounds.minX, bounds.minY - 2, bounds.minZ),
                            BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ))) {
                var state = level.getBlockState(at);
                if (state.isAir()) continue;
                for (var box : state.getCollisionShape(level, at).toAabbs()) {
                    cube.getPosition()
                            .set(
                                    at.getX() + (box.minX + box.maxX) / 2,
                                    at.getY() + (box.minY + box.maxY) / 2,
                                    at.getZ() + (box.minZ + box.maxZ) / 2);
                    pose.transformPosition(cube.getPosition());
                    cube.getDimensions().set(box.getXsize(), box.getYsize(), box.getZsize());
                    var sat = new Vector3d();
                    OrientedBoundingBox3d.sat(entity, cube, sat);
                    if (sat.lengthSquared() > 0
                            && sat.x != Double.MAX_VALUE
                            && sat.y != Double.MAX_VALUE
                            && sat.z != Double.MAX_VALUE)
                        return "body="
                                + body.getUniqueId()
                                + " block="
                                + at.toShortString()
                                + " state="
                                + state
                                + " separation="
                                + java.util.Arrays.toString(new double[] {sat.x, sat.y, sat.z});
                }
            }
        }
        return "level=" + !level.noCollision(p, aabb);
    }

    /** Read-only doorway sweep: test the next 0.1 block of the player's intended walking direction. */
    static void boarding(Player p, String label) {
        var level = p.level();
        var bodies = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        double a = java.lang.Math.toRadians(p.getYRot());
        var box =
                p.getBoundingBox().move(-java.lang.Math.sin(a) * .1, 0, java.lang.Math.cos(a) * .1);
        var logger = org.slf4j.LoggerFactory.getLogger("starfold-probe");
        var terrainHits = new java.util.ArrayList<String>();
        for (var at :
                BlockPos.betweenClosed(
                        BlockPos.containing(box.minX, box.minY, box.minZ),
                        BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            var state = level.getBlockState(at);
            for (var shape : state.getCollisionShape(level, at).toAabbs())
                if (shape.move(at).intersects(box))
                    terrainHits.add(at.toShortString() + " " + state + " box=" + shape.move(at));
        }
        logger.info(
                "PROBE BOARDING-TERRAIN {} tick {} swept {} hits {} stairs {}",
                label,
                level.getGameTime(),
                box,
                terrainHits,
                Airstair.collisions(level, p, box, java.util.List.of()).stream()
                        .flatMap(s -> s.toAabbs().stream())
                        .toList());
        for (var body : bodies.getAllSubLevels()) {
            var pose = body.logicalPose();
            if (pose.position().distance(p.getX(), p.getY(), p.getZ()) > 50) continue;
            var local = pose.transformPositionInverse(new Vector3d(p.getX(), p.getY(), p.getZ()));
            var bounds = new BoundingBox3d(box);
            bounds.transformInverse(pose, new Matrix4d(), bounds);
            var hits = new java.util.ArrayList<String>();
            for (var at :
                    BlockPos.betweenClosed(
                            BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ),
                            BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ))) {
                var state = level.getBlockState(at);
                for (var shape : state.getCollisionShape(level, at).toAabbs())
                    if (shape.move(at)
                            .intersects(
                                    new net.minecraft.world.phys.AABB(
                                            bounds.minX,
                                            bounds.minY,
                                            bounds.minZ,
                                            bounds.maxX,
                                            bounds.maxY,
                                            bounds.maxZ)))
                        hits.add(at.toShortString() + " " + state + " box=" + shape.move(at));
            }
            logger.info(
                    "PROBE BOARDING {} tick {} survival {} feet {} local {} box {} sweptLocal {} hits {} groundBelow {} tracked {} fit {}",
                    label,
                    level.getGameTime(),
                    !p.isCreative(),
                    p.position(),
                    java.util.Arrays.toString(new double[] {local.x, local.y, local.z}),
                    p.getBoundingBox(),
                    java.util.Arrays.toString(
                            new double[] {
                                bounds.minX,
                                bounds.minY,
                                bounds.minZ,
                                bounds.maxX,
                                bounds.maxY,
                                bounds.maxZ
                            }),
                    hits,
                    level.getBlockState(p.blockPosition().below()),
                    Sable.HELPER.getTrackingSubLevel(p) == body,
                    collision(p, true));
        }
    }

    private PilotPoseProbe() {}
}
