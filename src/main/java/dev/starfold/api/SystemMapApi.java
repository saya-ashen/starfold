package dev.starfold.api;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.render.GroundClient;
import dev.starfold.ship.ShipMapClient;
import dev.starfold.ship.ShipMapPackets;
import dev.starfold.ship.ShipRecord;
import dev.starfold.space.MapNavigation;
import dev.starfold.space.SpaceClient;
import dev.starfold.space.SpaceNavigationClient;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.List;
import java.util.UUID;

/** Client-thread atlas data in universe blocks, simulation ticks and catalog indices. */
public final class SystemMapApi {
    public record Position(double x, double y, double z) {
        public double distance(Position p) { return Math.sqrt(Math.pow(x-p.x, 2) + Math.pow(y-p.y, 2) + Math.pow(z-p.z, 2)); }
    }
    public enum Kind { STAR, PLANET, MOON, GAS }
    /** parent=-1 for a fixed root. Axis/radial define a circular path in universe axes. */
    public record Orbit(int body, String identity, String nameKey, Kind kind, int system, int parent,
                        Position position, Position axis, Position radial, double radius, double period,
                        double phase, long epoch, double distance, boolean surface) {}
    public record System(int body, String identity, String name, Position position, double distance, int planets, boolean home) {}
    public record Colors(int low, int high) {}
    public record Target(int body, UUID ship) {}
    /** face=-1 in space. body is a catalog index, system is the fixed ancestor's catalog index. */
    public record Ship(UUID id, String name, int body, int system, int face, double u, double v, Position position, double distance) {}

    private static Position point(dev.starfold.geometry.BentStripGeometry.Point p) { return new Position(p.x(), p.y(), p.z()); }
    public static double time() { return GroundClient.skyTime(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false)); }
    public static Position observer() {
        var mc = Minecraft.getInstance();
        var p = SpaceClient.active() ? SpaceClient.universeEye(mc.player.getEyePosition())
                : GroundClient.ready() ? GroundClient.universeEye() : null;
        return p == null ? null : point(p);
    }
    public static int currentSystem() {
        var model = GroundClient.ephemeris(); var p = observer();
        if (model == null || p == null) return -1;
        var ground = PlanetMapApi.observer();
        if (ground != null && !ground.inSpace()) return MapNavigation.system(model, ground.body());
        return MapNavigation.nearestSystem(model, new dev.starfold.geometry.BentStripGeometry.Point(p.x(), p.y(), p.z()), time());
    }
    /** Outside the complete orbital envelope, M opens the interstellar layer. */
    public static boolean inSystem() {
        int system = currentSystem();
        var p = observer();
        if (system < 0 || p == null) return false;
        var model = GroundClient.ephemeris();
        return point(model.frame(system, time()).center()).distance(p) <= MapNavigation.systemRadius(model, system);
    }
    public static List<System> systems() {
        var model = GroundClient.ephemeris(); var p = observer();
        if (model == null || p == null) return List.of();
        double tick = time();
        return model.catalogIndices().stream().filter(i -> model.catalogBody(i).light() && model.catalogBody(i).motion() instanceof GroundEphemeris.Fixed)
                .map(i -> {
                    var b = model.catalogBody(i); var at = point(model.frame(i, tick).center());
                    int count = (int) model.bodies().stream().filter(c -> !c.light() && c.motion() instanceof GroundEphemeris.Orbit o && model.catalogBody(o.parent()).light() && MapNavigation.system(model, o.parent()) == i).count();
                    String id = b.id().region() + "-" + b.id().index();
                    return new System(i, id, "", at, p.distance(at), count, at.distance(point(model.origin())) == 0);
                }).sorted(java.util.Comparator.comparingDouble((System s) -> s.position().distance(point(model.origin())))
                        .thenComparing(System::identity)).toList();
    }
    public static List<Orbit> orbits() { return orbits(currentSystem()); }
    public static List<Orbit> orbits(int system) {
        var model = GroundClient.ephemeris(); var p = observer();
        if (model == null || p == null || system < 0) return List.of();
        double tick = time();
        return model.catalogIndices().stream().filter(i -> MapNavigation.system(model, i) == system).map(i -> {
            var b = model.catalogBody(i); var at = point(model.frame(i, tick).center());
            var o = b.motion() instanceof GroundEphemeris.Orbit path ? path : null;
            String id = b.id().region() + "-" + b.id().index();
            var kind = b.light() ? Kind.STAR : b.gas().isPresent() ? Kind.GAS
                    : o != null && !model.catalogBody(o.parent()).light() ? Kind.MOON : Kind.PLANET;
            String name = b.slot() >= 0 ? PlanetSystem.name(b.slot()) : b.gas().map(g -> g.name()).orElse("");
            return new Orbit(i, id, name, kind, system, o == null ? -1 : o.parent(), at,
                    point(o == null ? GroundEphemeris.Y : o.axis()), point(o == null ? GroundEphemeris.ZERO : o.radial()),
                    o == null ? 0 : o.radius(), o == null ? 0 : o.period(), o == null ? 0 : o.phase(), o == null ? 0 : o.epoch(), p.distance(at), b.slot() >= 0);
        }).toList();
    }
    public static List<Ship> ships() {
        var model = GroundClient.ephemeris(); var p = observer();
        if (model == null || p == null) return List.of();
        double tick = time();
        return ShipMapClient.ships().stream().filter(r -> r.dimension().equals(Minecraft.getInstance().level.dimension().location()))
                .filter(r -> MapNavigation.position(model, r, tick) != null).map(r -> {
            var at = point(MapNavigation.position(model, r, tick));
            int body = r.state() instanceof ShipRecord.OnSlot slot ? model.index(slot.slot())
                    : model.index(((ShipRecord.InSpace) r.state()).place().body());
            var pose = MapNavigation.surface(r);
            return new Ship(r.uuid(), r.name().orElse("SHIP-" + r.id().region() + "-" + r.id().index()), body,
                    MapNavigation.system(model, body), pose == null ? -1 : pose.face().ordinal(),
                    pose == null ? 0 : pose.point().x(), pose == null ? 0 : pose.point().z(), at, p.distance(at));
        }).toList();
    }
    /** Schematic surface palette; gas bands retain the catalog's own colors. */
    public static Colors colors(int body) { return colors(GroundClient.ephemeris().catalogBody(body)); }
    static Colors colors(GroundEphemeris.Body b) {
        var gas = b.gas().orElse(null);
        if (gas != null) return new Colors(0xff000000 | gas.lowColor(), 0xff000000 | gas.highColor());
        int color = b.light() ? 0xffffd584 : b.atmosphere().density() > 0 ? 0xff000000 | b.atmosphere().color() : 0xffa2a8af;
        return new Colors(color, color);
    }
    /** Navigation remains owned by Starfold, including selections made outside the atlas. */
    public static Target navigationTarget() {
        return new Target(SpaceNavigationClient.selectedBodyIndex(), SpaceNavigationClient.selectedShipId());
    }
    public static void refreshShips() { ShipMapClient.refresh(); }
    public static void target(int body) {
        SpaceNavigationClient.target(body);
        PacketDistributor.sendToServer(new ShipMapPackets.Target(body, null));
    }
    public static void target(UUID ship) {
        SpaceNavigationClient.targetShip(ship);
        PacketDistributor.sendToServer(new ShipMapPackets.Target(-1, ship));
    }
    private SystemMapApi() {}
}
