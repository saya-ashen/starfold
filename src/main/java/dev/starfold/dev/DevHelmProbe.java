package dev.starfold.dev;

import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.ship.Airstair;
import dev.starfold.ship.HelmClient;
import dev.starfold.ship.HelmInputClient;
import dev.starfold.ship.HelmServer;
import dev.starfold.ship.HullLanding;
import dev.starfold.ship.SableFrames;
import dev.starfold.ship.Thrusters;
import dev.starfold.sky.GroundClock;
import dev.starfold.space.SpaceServer;

/** Explicit development readback; never takes or releases the helm. */
final class DevHelmProbe {
    static void initialize() {
        DevPlanetPackProbe.initialize();
        DevHaulerProbe.initialize();
        DevDevicesProbe.initialize();
        if (!Boolean.getBoolean("starfold.helmProbe")) return;
        if (Boolean.getBoolean("starfold.safeProbe")) {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    (net.neoforged.neoforge.event.tick.PlayerTickEvent.Post e) -> {
                        if (e.getEntity() instanceof net.minecraft.server.level.ServerPlayer p)
                            safety(p, "tick");
                    });
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    (net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent e) -> {
                        if (e.getEntity() instanceof net.minecraft.server.level.ServerPlayer p) {
                            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                                    .info(
                                            "PROBE HURT tick {} source {} amount {}",
                                            p.level().getGameTime(),
                                            e.getSource().getMsgId(),
                                            e.getAmount());
                            safety(p, "hurt");
                        }
                    });
        }
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.RegisterCommandsEvent event) ->
                        event.getDispatcher()
                                .register(
                                        net.minecraft.commands.Commands.literal("feelspeed")
                                                .requires(s -> s.hasPermission(2))
                                                .then(
                                                        net.minecraft.commands.Commands.argument(
                                                                        "speed",
                                                                        com.mojang.brigadier
                                                                                .arguments
                                                                                .DoubleArgumentType
                                                                                .doubleArg(0, 800))
                                                                .executes(
                                                                        c -> {
                                                                            SpaceServer.probeSpeed(
                                                                                    c.getSource()
                                                                                            .getPlayerOrException(),
                                                                                    com.mojang
                                                                                            .brigadier
                                                                                            .arguments
                                                                                            .DoubleArgumentType
                                                                                            .getDouble(
                                                                                                    c,
                                                                                                    "speed"));
                                                                            return 1;
                                                                        }))));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.RegisterCommandsEvent event) ->
                        event.getDispatcher()
                                .register(
                                        net.minecraft.commands.Commands.literal("airsky")
                                                .requires(s -> s.hasPermission(2))
                                                .then(
                                                        net.minecraft.commands.Commands.argument(
                                                                        "ticks",
                                                                        com.mojang.brigadier
                                                                                .arguments
                                                                                .LongArgumentType
                                                                                .longArg(0, 48000))
                                                                .executes(
                                                                        c -> {
                                                                            var level =
                                                                                    c.getSource()
                                                                                            .getPlayerOrException()
                                                                                            .serverLevel();
                                                                            if (!PlanetLevels.is(
                                                                                    level))
                                                                                return 0;
                                                                            GroundClock.get(level)
                                                                                    .skip(
                                                                                            com
                                                                                                    .mojang
                                                                                                    .brigadier
                                                                                                    .arguments
                                                                                                    .LongArgumentType
                                                                                                    .getLong(
                                                                                                            c,
                                                                                                            "ticks"));
                                                                            return 1;
                                                                        }))));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.RegisterCommandsEvent event) ->
                        event.getDispatcher()
                                .register(
                                        net.minecraft.commands.Commands.literal("helmstate")
                                                .requires(s -> s.hasPermission(2))
                                                .then(
                                                        net.minecraft.commands.Commands.argument(
                                                                        "label",
                                                                        com.mojang.brigadier
                                                                                .arguments
                                                                                .StringArgumentType
                                                                                .word())
                                                                .executes(
                                                                        c -> {
                                                                            state(
                                                                                    c.getSource()
                                                                                            .getPlayerOrException(),
                                                                                    com.mojang
                                                                                            .brigadier
                                                                                            .arguments
                                                                                            .StringArgumentType
                                                                                            .getString(
                                                                                                    c,
                                                                                                    "label"));
                                                                            return 1;
                                                                        }))));
    }

    static void state(net.minecraft.world.entity.player.Player player, String label) {
        if (player instanceof net.minecraft.server.level.ServerPlayer p)
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE RECOVERY {} dimension {} planet {} feet {} health {} ground {} loaded {} bed {} bedDimension {} spawn {} sleeping {}",
                            label,
                            p.level().dimension().location(),
                            PlanetLevels.is(p.level())
                                    ? PlanetStorage.find(p.getX(), p.getZ())
                                    : -1,
                            p.position(),
                            p.getHealth(),
                            p.onGround(),
                            p.level().hasChunkAt(p.blockPosition()),
                            p.getRespawnPosition(),
                            p.getRespawnDimension().location(),
                            p.serverLevel().getSharedSpawnPos(),
                            p.isSleeping());
        var level = player.level();
        for (var mob :
                level.getEntitiesOfClass(
                        net.minecraft.world.entity.Mob.class, player.getBoundingBox().inflate(24)))
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE AIRSTAIR_MOB {} side {} type {} feet {} climbable {} health {}",
                            label,
                            level.isClientSide ? "client" : "server",
                            mob.getType(),
                            mob.position(),
                            mob.onClimbable(),
                            mob.getHealth());
        var bodies = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (bodies == null) return;
        for (var body : bodies.getAllSubLevels()) {
            var bounds = body.getPlot().getBoundingBox();
            var census = new java.util.TreeMap<String, Integer>();
            var thrusters = new java.util.ArrayList<String>();
            for (var pos :
                    net.minecraft.core.BlockPos.betweenClosed(
                            bounds.minX(),
                            bounds.minY(),
                            bounds.minZ(),
                            bounds.maxX(),
                            bounds.maxY(),
                            bounds.maxZ())) {
                var state = level.getBlockState(pos);
                if (!state.isAir())
                    census.merge(
                            net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                    .getKey(state.getBlock())
                                    .toString(),
                            1,
                            Integer::sum);
                if (state.getBlock() instanceof Thrusters.ThrusterBlock)
                    thrusters.add(
                            pos
                                    + " "
                                    + state
                                    + " effective="
                                    + (body
                                                    instanceof
                                                    dev.ryanhcode.sable.sublevel.ServerSubLevel
                                                            server
                                            ? HelmServer.power(server, pos, state)
                                            : HelmInputClient.power(level, pos, state)));
            }
            for (var holder : body.getPlot().getLoadedChunks())
                for (var be : holder.getChunk().getBlockEntities().values())
                    if (be instanceof Airstair.Stair ladder) {
                        var top = Airstair.top(ladder, body);
                        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                                .info(
                                        "PROBE AIRSTAIR {} side {} pos {} top {} length {} settled {} visual {} feet {} climbable {} health {}",
                                        label,
                                        level.isClientSide ? "client" : "server",
                                        ladder.getBlockPos(),
                                        top,
                                        ladder.length,
                                        ladder.settled,
                                        ladder.visual,
                                        player.position(),
                                        player.onClimbable(),
                                        player.getHealth());
                    }
            double clearance = HullLanding.ground(body).clearance();
            boolean pilot =
                    player instanceof net.minecraft.server.level.ServerPlayer server
                            ? body instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel ship
                                    && HelmServer.pilot(server, ship)
                            : HelmClient.body() == body;
            var local =
                    body.logicalPose()
                            .transformPositionInverse(
                                    new org.joml.Vector3d(
                                            player.getX(), player.getY(), player.getZ()));
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE HELMSTATE {} side {} player {} body {} pilot {} local {} clearance {} ground {} census {} thrusters {}",
                            label,
                            level.isClientSide ? "client" : "server",
                            player.getUUID(),
                            body.getUniqueId(),
                            pilot,
                            java.util.List.of(local.x, local.y, local.z),
                            clearance,
                            player.onGround(),
                            census,
                            thrusters);
            if (body instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel server) {
                var velocity =
                        dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(
                                        (net.minecraft.server.level.ServerLevel) level)
                                .physicsSystem()
                                .getPipeline()
                                .getLinearVelocity(server, new org.joml.Vector3d());
                var center = server.getMassTracker().getCenterOfMass();
                org.slf4j.LoggerFactory.getLogger("starfold-probe")
                        .info(
                                "PROBE LANDDETAIL {} tick {} mass {} centre {} velocity {} bits {} nose {}",
                                label,
                                level.getGameTime(),
                                server.getMassTracker().getMass(),
                                center,
                                java.util.List.of(velocity.x, velocity.y, velocity.z),
                                HelmServer.bits(server),
                                HelmServer.forward(server));
            }
        }
    }

    private static void safety(net.minecraft.server.level.ServerPlayer p, String phase) {
        var body = SableFrames.aboard(p);
        var v = new org.joml.Vector3d();
        var local = new org.joml.Vector3d(p.getX(), p.getY(), p.getZ());
        if (body != null) {
            dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(p.serverLevel())
                    .physicsSystem()
                    .getPipeline()
                    .getLinearVelocity(body, v);
            body.logicalPose().transformPositionInverse(local);
        }
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE SAFETY {} tick {} health {} fall {} ground {} tracking {} aboard {} pilot {} vy {} feet {} local {}",
                        phase,
                        p.level().getGameTime(),
                        p.getHealth(),
                        p.fallDistance,
                        p.onGround(),
                        dev.ryanhcode.sable.Sable.HELPER.getTrackingSubLevel(p) != null,
                        body == null ? null : body.getUniqueId(),
                        HelmServer.active(p),
                        v.y,
                        p.position(),
                        java.util.List.of(local.x, local.y, local.z));
    }

    private DevHelmProbe() {}
}
