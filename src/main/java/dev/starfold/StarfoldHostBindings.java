package dev.starfold;

import dev.starfold.flight.FlightHost;
import dev.starfold.flight.GroundFlightServer;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.ground.*;
import dev.starfold.render.GroundViewServer;
import dev.starfold.ship.SableFrames;
import dev.starfold.ship.ShipHost;
import dev.starfold.space.SpacePackets;
import dev.starfold.sky.GroundEphemerisSave;
import dev.starfold.sky.SkyHost;
import dev.starfold.space.SpaceApproach;
import dev.starfold.space.SpaceHost;
import dev.starfold.space.SpaceServer;
import dev.starfold.space.SpaceView;
import dev.starfold.waypoint.ShipGuide;
import dev.starfold.waypoint.WaypointSave;
import dev.starfold.waypoint.WaypointServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/** Cross-subsystem server adapters, installed before subsystem initialization. */
final class StarfoldHostBindings {
    private StarfoldHostBindings() {}

    static void install() {
        installFlightHost();
        installGroundHost();
        installShipHost();
        installSkyHost();
        installSpaceHost();
    }

    private static void installFlightHost() {
        FlightHost.install(
                new FlightHost() {
                    @Override
                    public void viewLost(net.minecraft.server.level.ServerPlayer player) {
                        GroundViewServer.remove(player);
                    }

                    @Override
                    public void targetSync(net.minecraft.server.level.ServerPlayer player) {
                        WaypointServer.send(player);
                    }

                    @Override
                    public void targetCleared(net.minecraft.server.level.ServerPlayer player) {
                        WaypointSave.get(player.serverLevel()).clear(player.getUUID());
                    }

                    @Override
                    public Component failure(RuntimeException error) {
                        return WaypointServer.failure(error);
                    }

                    @Override
                    public int leave(net.minecraft.server.level.ServerPlayer player) {
                        return PlanetPlayers.leave(player);
                    }

                    @Override
                    public boolean nearHull(
                            net.minecraft.server.level.ServerPlayer player, double distance) {
                        return dev.starfold.ship.SableFrames.near(player, distance);
                    }
                });
    }

    private static void installGroundHost() {
        GroundHost.install(
                new GroundHost() {
                    @Override
                    public void afterDeath(net.minecraft.server.level.ServerPlayer player) {
                        PlanetPlayers.afterDeath(player);
                        dev.starfold.space.GasPlayerExposureServer.completedDeath(player);
                    }

                    @Override
                    public void viewLost(net.minecraft.server.level.ServerPlayer player) {
                        GroundViewServer.remove(player);
                    }

                    @Override
                    public void resited(net.minecraft.server.level.ServerPlayer player) {
                        GroundFlightServer.site(player);
                    }

                    @Override
                    public void regionSync(net.minecraft.server.level.ServerPlayer player) {
                        SpaceServer.sync(player);
                    }

                    @Override
                    public boolean controlled(net.minecraft.world.entity.Entity entity) {
                        return GroundFlightServer.controlled(entity);
                    }

                    @Override
                    public boolean aloft(net.minecraft.server.level.ServerPlayer player) {
                        return GroundFlightServer.aloft(player);
                    }

                    @Override
                    public boolean carrying(net.minecraft.server.level.ServerPlayer player) {
                        return SableFrames.carrying(player);
                    }

                    @Override
                    public void clearArrival() {
                        SpaceApproach.clear();
                    }
                });
    }

    private static void installShipHost() {
        ShipHost.install(
                new ShipHost() {
                    @Override
                    public void mapSync(ServerLevel level) { dev.starfold.waypoint.MapNavigationServer.sync(level); }

                    @Override
                    public boolean alwaysTicket(java.util.UUID ship) {
                        return DevelopmentHooks.alwaysTicket.test(ship);
                    }

                    @Override
                    public boolean retainLead(
                            ServerLevel level, dev.ryanhcode.sable.sublevel.ServerSubLevel body) {
                        return SpaceServer.retainLead(level, body);
                    }

                    @Override
                    public boolean probeTicket(java.util.UUID ship) {
                        return DevelopmentHooks.heldTicket.test(ship);
                    }

                    @Override
                    public boolean externalRoll(
                            net.minecraft.world.level.Level level,
                            dev.ryanhcode.sable.sublevel.ServerSubLevel body) {
                        return SpaceServer.rollControl(level, body);
                    }

                    @Override
                    public void gasCollected(dev.ryanhcode.sable.sublevel.ServerSubLevel body) {
                        ShipGuide.skim(body);
                    }

                    @Override
                    public void pilotReleased(net.minecraft.server.level.ServerPlayer player) {
                        SpaceServer.releaseHelm(player);
                    }

                    @Override
                    public void releaseArrival(ServerLevel level) {
                        SpaceApproach.release(level);
                    }

                    @Override
                    public void advanceSurroundings(
                            ServerLevel level,
                            dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container) {
                        SpaceServer.step(level, container);
                    }

                    @Override
                    public dev.starfold.sky.Space.Frame departureFrame(
                            ServerLevel level,
                            dev.starfold.sky.Space.Motion motion,
                            org.joml.Quaterniond orientation,
                            dev.starfold.chart.GroundEphemeris.Model model,
                            int planet,
                            long tick) {
                        return SpaceServer.entering(
                                level, motion, orientation, model, planet, tick);
                    }

                    @Override
                    public void hullDeparted(ServerLevel level, int planet, String id) {
                        SpaceServer.departed(level, planet, id);
                    }

                    @Override
                    public dev.starfold.sky.Space.Frame surroundingsFrame(ServerLevel level) {
                        return SpaceServer.frame(level);
                    }

                    @Override
                    public Point carriedSpin(ServerLevel level, java.util.UUID hull) {
                        return SpaceServer.carriedSpin(level, hull);
                    }

                    @Override
                    public boolean landingLoaded(ServerLevel level, Point anchor) {
                        return SpaceApproach.full(level, anchor);
                    }

                    @Override
                    public boolean landingReady(
                            dev.starfold.chart.GroundEphemeris.Body body,
                            double height,
                            boolean full) {
                        return SpaceApproach.ready(body, height, full);
                    }

                    @Override
                    public net.minecraft.world.level.ChunkPos landingColumn(
                            dev.starfold.geometry.BentStripGeometry.Point anchor) {
                        return SpaceApproach.column(anchor);
                    }

                    @Override
                    public void hullEnteredChart(ServerLevel level, String id) {
                        SpaceServer.enteredChart(level, id);
                    }

                    @Override
                    public void hullArrived(
                            ServerLevel level, dev.ryanhcode.sable.sublevel.ServerSubLevel body) {
                        SpaceApproach.arrived(level, body);
                    }

                    @Override
                    public SpacePackets.Frame carriedFrame(
                            net.minecraft.server.level.ServerPlayer player) {
                        return SpaceServer.packet(player);
                    }
                });
    }

    private static void installSkyHost() {
        SkyHost.install(
                new SkyHost() {
                    @Override
                    public boolean underWay() {
                        return GroundFlightServer.underWay();
                    }

                    @Override
                    public double sunlight(
                            ServerLevel level,
                            net.minecraft.core.BlockPos pos,
                            GroundEphemerisSave clock) {
                        var eye =
                                SpaceServer.frame(level)
                                        .universe(
                                                new dev.starfold.geometry.BentStripGeometry.Point(
                                                        pos.getX() + .5,
                                                        pos.getY() + .5,
                                                        pos.getZ() + .5),
                                                clock.model,
                                                clock.tick());
                        return SpaceView.sunlight(clock.model, eye, clock.tick());
                    }
                });
    }

    private static void installSpaceHost() {
        SpaceHost.install(
                new SpaceHost() {
                    @Override
                    public void targetCleared(net.minecraft.server.level.ServerPlayer player) { dev.starfold.waypoint.MapNavigationServer.markerSelected(player); }

                    @Override
                    public void targetSync(
                            net.minecraft.server.level.ServerPlayer player, long session) {
                        WaypointServer.send(player, session);
                    }
                });
    }

}
