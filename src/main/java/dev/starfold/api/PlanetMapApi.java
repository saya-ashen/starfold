package dev.starfold.api;

import dev.starfold.chart.GroundOrbit;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.PlanetSpace;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.planet.GroundCoordinates;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.render.GroundBaseCache;
import dev.starfold.render.GroundClient;
import dev.starfold.render.GroundViewClient;
import dev.starfold.render.PlanetRemoteRenderer;
import dev.starfold.space.SpaceClient;
import dev.starfold.waypoint.WaypointClient;
import dev.starfold.waypoint.WaypointPackets;
import dev.starfold.worldgen.surface.SurfaceColor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.List;

/** Client-thread planet survey API. Body indices are catalog indices, never storage addresses.
 * Face ids: TOP, EAST, WEST, NORTH, SOUTH, BOTTOM (0..5); u/v increase along each face's axes.
 * A null observer/surface means the corresponding client metadata has not arrived. */
public final class PlanetMapApi {
    public record Body(int index, String identity, String nameKey, boolean surface, boolean gas, int faceSize) {}
    public record Cell(int argb, double height) {}
    /** Actual loaded surface; waterDepth is -1 on land, otherwise blocks to the opaque bed. */
    public record LoadedCell(int argb, double height, int waterDepth) {}
    /** step is blocks/cell (4,8,16,32,64). Null cells are pending; progress is 0..1. */
    public interface Surface {
        int width();
        int step();
        double progress();
        default boolean ready() { return progress() == 1; }
        Cell cell(int face, int u, int v);
        /** Water column depth in blocks, or -1 for land/pending data. */
        default int waterDepth(int face, int u, int v) { return -1; }
    }
    /** body/face are -1 in space; heading is degrees, zero toward +v, positive toward -u. */
    public record Observer(int body, int face, double u, double v, double heading, double height, boolean inSpace) {}
    public record Marker(String name, int body, int face, double u, double v, boolean launch) {}
    public record Direction(double x, double y, double z) {}
    public record Location(int face, double u, double v) {}
    public record Atmosphere(int argb, double density) {}

    /** Body-local unit direction, using the same mapping as the exterior terrain mesh. */
    public static Direction direction(int body, int face, double u, double v) {
        return direction(shape(body), face, u, v);
    }
    static Direction direction(PlanetShape shape, int face, double u, double v) {
        return direction(shape.direction(CubeSurfaceTopology.Face.VALUES[face], u, v));
    }
    /** Inverse on the nominal sphere; an edge belongs to one of its incident faces. */
    public static Location locate(int body, Direction direction) { return locate(shape(body), direction); }
    static Location locate(PlanetShape shape, Direction direction) {
        var p = shape.locate(PlanetSpace.unit(new Point(direction.x(), direction.y(), direction.z())).scale(shape.radius()));
        return new Location(p.face().ordinal(), p.point().x(), p.point().z());
    }
    private static PlanetShape shape(int body) { return PlanetShape.of(GroundClient.ephemeris().catalogBody(body).slot()); }
    private static Direction direction(Point p) { return new Direction(p.x(), p.y(), p.z()); }

    /** Current primary illuminant in body-local axes, including axial tilt and spin. Null before metadata arrives. */
    public static Direction sun(int body) {
        var model = GroundClient.ephemeris();
        return model == null ? null : sun(model, body, SystemMapApi.time());
    }
    static Direction sun(GroundEphemeris.Model model, int body, double tick) {
        var frame = model.frame(body, tick);
        return direction(PlanetSpace.unit(frame.local(model.star(frame.center(), tick).position())));
    }
    public static Atmosphere atmosphere(int body) {
        var model = GroundClient.ephemeris();
        return model == null ? null : atmosphere(model.catalogBody(body).atmosphere());
    }
    static Atmosphere atmosphere(dev.starfold.worldgen.PlanetAtmosphere air) {
        return air.density() == 0 ? null : new Atmosphere(0xff000000 | air.color(), air.density());
    }

    public static List<Body> bodies() {
        var model = GroundClient.ephemeris();
        if (model == null) return List.of();
        return model.catalogIndices().stream().filter(i -> !model.catalogBody(i).light()).map(i -> {
            var b = model.catalogBody(i);
            boolean surface = b.slot() >= 0;
            return new Body(i, b.id().region() + "-" + b.id().index(),
                    surface ? PlanetSystem.name(b.slot()) : b.gas().orElseThrow().name(),
                    surface, b.appearance() == dev.starfold.chart.GroundEphemeris.Appearance.GAS,
                    surface ? PlanetShape.of(b.slot()).size() : 0);
        }).toList();
    }

    public static Surface surface(int body, int step) {
        var model = GroundClient.ephemeris();
        if (model == null) return null;
        int slot = model.catalogBody(body).slot();
        if (slot < 0) return null;
        var terrain = GroundClient.ready() && GroundClient.planet() == slot ? GroundViewClient.terrain() : PlanetRemoteRenderer.terrain(slot);
        return terrain == null ? null : GroundBaseCache.surface(terrain, step);
    }

    /** Never requests or generates chunks. Face coordinates are mapped through the current client chart. */
    public static LoadedCell loadedSurface(int body, int face, int u, int v) {
        var mc = Minecraft.getInstance();
        var model = GroundClient.ephemeris();
        if (mc.level == null || model == null || !GroundClient.ready()) return null;
        int slot = model.catalogBody(body).slot();
        if (slot < 0) return null;
        var at = GroundClient.planePoint(slot, CubeSurfaceTopology.Face.VALUES[face],
                new Point(u + .5, 0, v + .5));
        if (at == null) return null;
        int x = (int) Math.floor(at.x()), z = (int) Math.floor(at.z());
        var chunk = mc.level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) return null;
        var pos = new BlockPos.MutableBlockPos();
        return loadedColumn(chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15),
                chunk.getMinBuildHeight(), y -> chunk.getBlockState(pos.set(x, y, z)));
    }

    static LoadedCell loadedColumn(int top, int floor,
            java.util.function.IntFunction<BlockState> column) {
        int waterTop = Integer.MIN_VALUE, waterColor = 0;
        for (int y = top; y >= floor; y--) {
            var state = column.apply(y);
            var fluid = state.getFluidState();
            boolean water = fluid.getType() == Fluids.WATER || fluid.getType() == Fluids.FLOWING_WATER;
            if (water && waterTop == Integer.MIN_VALUE) {
                waterTop = y;
                waterColor = 0xff000000 | SurfaceColor.color(state);
            }
            if (state.canOcclude() || !water && !fluid.isEmpty()) {
                return waterTop != Integer.MIN_VALUE
                        ? new LoadedCell(waterColor, waterTop + 1 - GroundCoordinates.FLOOR, waterTop - y)
                        : new LoadedCell(0xff000000 | SurfaceColor.color(state), y + 1 - GroundCoordinates.FLOOR, -1);
            }
        }
        return waterTop == Integer.MIN_VALUE ? null : new LoadedCell(waterColor,
                waterTop + 1 - GroundCoordinates.FLOOR, waterTop - floor + 1);
    }

    /** The active waypoint owned by Starfold navigation, or null when no waypoint session is active. */
    public static Marker navigationTarget() {
        var model = GroundClient.ephemeris();
        var m = WaypointClient.active();
        return model == null || m == null ? null : new Marker(m.name(), model.index(m.planet()),
                m.pose().face().ordinal(), m.pose().point().x(), m.pose().point().z(),
                m.name().equals(dev.starfold.waypoint.Waypoint.LAUNCH));
    }

    public static Observer observer() {
        if (SpaceClient.present()) return new Observer(-1, -1, 0, 0, 0, 0, true);
        if (!GroundClient.ready()) return null;
        var pose = GroundClient.pose();
        var transported = GroundClient.shape().canonical(CubeSurfaceTopology.Face.VALUES[GroundClient.face()], GroundClient.eye());
        return new Observer(GroundClient.model().index(GroundClient.planet()), pose.face().ordinal(),
                pose.point().x(), pose.point().z(), GroundClient.frame().yawToStorage(Minecraft.getInstance().player.getYRot())
                        + transported.turns() * 90, pose.point().y(), false);
    }

    /** Existing space-region ownership or completed full-sphere display transition. */
    public static boolean seenFromSpace(int body) {
        if (GroundClient.ephemeris() == null) return false;
        if (SpaceClient.active()) {
            var model = GroundClient.ephemeris();
            var eye = SpaceClient.universeEye(Minecraft.getInstance().player.getEyePosition());
            var b = model.catalogBody(body);
            double radius = b.slot() >= 0 ? PlanetShape.of(b.slot()).radius() : b.radius();
            return dev.starfold.space.SpaceView.visible(model.frame(body, SpaceClient.time()).center().sub(eye).length(), radius);
        }
        var o = observer();
        return o != null && o.body() == body && GroundOrbit.weight(GroundClient.eye().y()) > .9; // the sphere view settles just under END
    }

    public static List<Marker> markers() {
        var model = GroundClient.ephemeris();
        if (model == null) return List.of();
        return WaypointClient.markers().stream().map(m -> new Marker(m.name(), model.index(m.planet()),
                m.pose().face().ordinal(), m.pose().point().x(), m.pose().point().z(),
                m.name().equals(dev.starfold.waypoint.Waypoint.LAUNCH))).toList();
    }
    public static void refreshMarkers() { request(WaypointPackets.Action.LIST, "", -1, -1, 0, 0); }
    public static void mark(String name, int body, int face, double u, double v) { request(WaypointPackets.Action.ADD, name, body, face, u, v); }
    public static void unmark(String name) { request(WaypointPackets.Action.REMOVE, name, -1, -1, 0, 0); }
    public static void target(String name) {
        markers().stream().filter(m -> m.name().equals(name)).findFirst().ifPresent(m ->
                dev.starfold.space.SpaceNavigationClient.target(m.body()));
        request(WaypointPackets.Action.TARGET, name, -1, -1, 0, 0);
    }
    private static void request(WaypointPackets.Action action, String name, int body, int face, double u, double v) {
        PacketDistributor.sendToServer(new WaypointPackets.Edit(action, name, body, face, u, v));
    }
    private PlanetMapApi() {}
}
