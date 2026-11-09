package dev.starfold.dev;

import dev.ryanhcode.sable.Sable;
import dev.starfold.ship.Airstair;
import dev.starfold.ship.CloakMotion;
import dev.starfold.ship.HelmClient;
import dev.starfold.ship.HelmServer;
import dev.starfold.ship.HullLanding;

import net.minecraft.world.entity.player.Player;

import org.joml.Vector3d;

/** Opt-in, read-only every-pose-update census. No collision or movement overrides. */
final class DevProneProbe {
    private record Previous(Object body, Vector3d position, Vector3d velocity, Vector3d cloak) {}

    private static final java.util.Map<Player, Previous> history =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    static void sample(Player p, boolean fits) {
        var body = Sable.HELPER.getTrackingSubLevel(p);
        if (body == null) return;
        var level = p.level();
        var pose = body.logicalPose();
        var at = new Vector3d(pose.position());
        var old = history.get(p);
        boolean fresh = old == null || old.body() != body;
        var velocity = fresh ? new Vector3d() : new Vector3d(at).sub(old.position()).mul(20);
        var acceleration =
                fresh ? new Vector3d() : new Vector3d(velocity).sub(old.velocity()).mul(20);
        var feet = new Vector3d(p.getX(), p.getY(), p.getZ());
        var cloak = fresh ? new Vector3d(feet) : new Vector3d(old.cloak());
        cloak.set(
                CloakMotion.follow(cloak.x, feet.x),
                CloakMotion.follow(cloak.y, feet.y),
                CloakMotion.follow(cloak.z, feet.z));
        history.put(p, new Previous(body, at, velocity, cloak));
        double clearance = HullLanding.ground(body).clearance();
        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("side", level.isClientSide ? "client" : "server");
        data.put("tick", level.getGameTime());
        data.put("pose", p.getPose().name());
        data.put("fits", fits);
        data.put("player", xyz(feet));
        data.put("local", xyz(pose.transformPositionInverse(new Vector3d(feet))));
        data.put("hull", xyz(at));
        data.put("lastHull", xyz(body.lastPose().position()));
        data.put(
                "orientation",
                new double[] {
                    pose.orientation().x(),
                    pose.orientation().y(),
                    pose.orientation().z(),
                    pose.orientation().w()
                });
        data.put("velocity", xyz(velocity));
        data.put("acceleration", xyz(acceleration));
        data.put("clearance", Double.isFinite(clearance) ? clearance : null);
        data.put("cloak66", xyz(new Vector3d(cloak).sub(feet)));
        data.put(
                "cloak67",
                new double[] {p.xCloak - p.getX(), p.yCloak - p.getY(), p.zCloak - p.getZ()});
        data.put("currentCollision", PilotPoseProbe.collision(p, 1));
        data.put("previousCollision", PilotPoseProbe.collision(p, 0));
        if (level.isClientSide) data.put("renderHalfCollision", PilotPoseProbe.collision(p, 2));
        data.put("ground", p.onGround());
        if (body instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel server) {
            data.put("bits", HelmServer.bits(server));
            data.put(
                    "pilot",
                    p instanceof net.minecraft.server.level.ServerPlayer player
                            && HelmServer.pilot(player, server));
            data.put(
                    "physicsVelocity",
                    xyz(
                            dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(
                                            (net.minecraft.server.level.ServerLevel) level)
                                    .physicsSystem()
                                    .getPipeline()
                                    .getLinearVelocity(server, new Vector3d())));
        } else data.put("pilot", HelmClient.body() == body);
        var stairs = new java.util.ArrayList<String>();
        for (var holder : body.getPlot().getLoadedChunks())
            for (var be : holder.getChunk().getBlockEntities().values())
                if (be instanceof Airstair.Stair stair)
                    stairs.add(
                            stair.getBlockPos()
                                    + " length="
                                    + stair.length
                                    + " visual="
                                    + stair.visual
                                    + " settled="
                                    + stair.settled);
        data.put("stairs", stairs);
        var standing =
                p.getDimensions(net.minecraft.world.entity.Pose.STANDING)
                        .makeBoundingBox(p.position())
                        .deflate(1e-7);
        var terrain = new java.util.ArrayList<String>();
        for (var block :
                net.minecraft.core.BlockPos.betweenClosed(
                        net.minecraft.core.BlockPos.containing(
                                standing.minX, standing.minY, standing.minZ),
                        net.minecraft.core.BlockPos.containing(
                                standing.maxX, standing.maxY, standing.maxZ))) {
            var state = level.getBlockState(block);
            for (var shape : state.getCollisionShape(level, block).toAabbs())
                if (shape.move(block).intersects(standing))
                    terrain.add(block + " " + state + " " + shape);
        }
        data.put("terrain", terrain);
        data.put(
                "treads",
                Airstair.collisions(level, p, standing, java.util.List.of()).stream()
                        .map(Object::toString)
                        .toList());
        var contact =
                ((dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision
                                        .EntityMovementExtension)
                                p)
                        .sable$getCollisionInfo();
        if (contact != null) {
            data.put("sableGround", contact.verticalCollisionBelow);
            data.put("sableVertical", contact.verticalCollision);
            data.put("sableMotion", String.valueOf(contact.motion));
            data.put("sableInherited", String.valueOf(contact.inheritedMotion));
            data.put("sableContacts", String.valueOf(contact.firstCollisions));
        }
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info("PROBE PRONE {}", new com.google.gson.Gson().toJson(data));
    }

    private static double[] xyz(org.joml.Vector3dc p) {
        return new double[] {p.x(), p.y(), p.z()};
    }

    private DevProneProbe() {}
}
