package dev.starfold.ground;

import dev.starfold.flight.GroundFlightPackets;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.ship.SableFrames;
import dev.starfold.space.Space;
import dev.starfold.space.SpaceServer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.WeakHashMap;

/** Server-side owner of each observer's ObserverFrame. The body lives in canonical storage; the
 * frame only says how that storage chart is labelled on this observer's client. */
public final class ObserverFrames {
    private record Entry(ServerLevel level, ObserverFrame frame) {}

    // Packets may be sent from network threads as well as the server thread.
    private static final Map<ServerPlayer, Entry> FRAMES =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    public static boolean active(ServerPlayer player) {
        return GroundServer.is(player.level());
    }

    private static ObserverFrame fresh(ServerPlayer player) {
        if (SpaceServer.in(player)) return ObserverFrame.identity(0, 0);
        var access = PlanetAccess.of(player.level());
        try {
            return ObserverFrame.identity(
                    access.storagePlanet(player.getX(), player.getZ()), access.face(player.getX()));
        } catch (IllegalArgumentException outside) {
            return ObserverFrame.identity(0, 0);
        }
    }

    public static ObserverFrame get(ServerPlayer player) {
        var entry = FRAMES.get(player);
        if (entry == null || entry.level != player.level()) {
            var frame = fresh(player);
            set(player, frame);
            return frame;
        }
        if (SpaceServer.in(player) && !entry.frame.equals(ObserverFrame.identity(0, 0))) {
            var frame = ObserverFrame.identity(0, 0);
            set(player, frame);
            for (var told : RESET) told.accept(player);
            return frame;
        }
        return entry.frame;
    }

    private static void set(ServerPlayer player, ObserverFrame frame) {
        var old = FRAMES.put(player, new Entry(player.serverLevel(), frame));
        // A frame names a planet, and the client checks that name against the system it has been
        // told of. The first
        // frame of a connection can leave before the join event that tells it, so the system goes
        // with it.
        if (old == null || old.level != player.serverLevel()) GroundServer.shapes(player);
        if (!SableFrames.carrying(player)
                && player.connection.hasChannel(GroundFlightPackets.Frame.TYPE))
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    player, new GroundFlightPackets.Frame(frame));
    }

    /** Re-announce after (re)connecting a client session; the frame itself is unchanged. */
    public static void announce(ServerPlayer player) {
        set(player, get(player));
    }

    /** The body is about to be moved from a root-chart cell (usually in the halo) to its canonical
     * owner cell on an adjacent face. Client coordinates do not change. */
    public static void cross(ServerPlayer player, BlockPos halo, BlockPos owner, int transport) {
        var access = PlanetAccess.of(player.level());
        var frame = get(player);
        set(player, frame.cross(access.face(owner.getX()), centre(halo), centre(owner), transport));
    }

    private static Point centre(BlockPos pos) {
        return new Point(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
    }

    /** The body is being placed at a canonical storage position by the server (flight, landing).
     * Stays continuous when that place is drawn in the current chart; otherwise starts a fresh frame. */
    public static void relocate(ServerPlayer player, Point target) {
        var access = PlanetAccess.of(player.level());
        var frame = get(player);
        var at = BlockPos.containing(target.x(), target.y(), target.z());
        try {
            if (!access.inChart(at)) {
                reset(player, target);
                return;
            }
            int planet = access.storagePlanet(target.x(), target.z()),
                    face = access.face(target.x());
            if (planet == frame.planet() && face == frame.root()) return;
            if (planet == frame.planet()) {
                var root = access.middle(frame.planet(), frame.root());
                var image = access.image(root.x(), root.z(), target);
                if (image != null) {
                    var halo = BlockPos.containing(image.x(), image.y(), image.z());
                    var address = access.address(halo);
                    if (address.owner().getX() == at.getX()
                            && address.owner().getZ() == at.getZ()) {
                        cross(player, halo, address.owner().atY(at.getY()), address.turns());
                        return;
                    }
                }
            }
        } catch (IllegalArgumentException outside) {
        }
        reset(player, target);
    }

    private static void reset(ServerPlayer player, Point target) {
        var access = PlanetAccess.of(player.level());
        ObserverFrame frame;
        try {
            frame =
                    Space.holds(target.x(), target.z())
                            ? ObserverFrame.identity(0, 0)
                            : ObserverFrame.identity(
                                    access.storagePlanet(target.x(), target.z()),
                                    access.face(target.x()));
        } catch (IllegalArgumentException outside) {
            frame = ObserverFrame.identity(0, 0);
        }
        if (!frame.equals(get(player))) {
            set(player, frame);
            for (var told : RESET) told.accept(player);
        }
    }

    private static final java.util.List<java.util.function.Consumer<ServerPlayer>> RESET =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** A fresh frame gives every place new client coordinates. Vanilla's own state reaches the client again with
     *  the reload that follows; whoever keeps places on that client by other means is told here to send them again. */
    public static void onReset(java.util.function.Consumer<ServerPlayer> told) {
        RESET.add(told);
    }

    /** A vanilla teleport is about to be sent for the body's current position: keep the frame if
     * the destination is in its root chart, otherwise begin a fresh one (an ordinary reload). */
    public static ObserverFrame teleported(ServerPlayer player) {
        var access = PlanetAccess.of(player.level());
        var frame = get(player);
        try {
            if (access.inChart(player.blockPosition())
                    && access.storagePlanet(player.getX(), player.getZ()) == frame.planet()
                    && access.face(player.getX()) == frame.root()) return frame;
        } catch (IllegalArgumentException outside) {
        }
        reset(player, new Point(player.getX(), player.getY(), player.getZ()));
        return get(player);
    }

    private ObserverFrames() {}
}
