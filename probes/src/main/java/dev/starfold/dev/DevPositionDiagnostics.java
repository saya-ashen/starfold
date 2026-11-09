package dev.starfold.dev;

import dev.starfold.probes.mixin.GroundPositionDiagnosticAccess;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Supplier;

/** Temporary local-only position causality trace. Never obtains/initializes an observer frame. */
public final class DevPositionDiagnostics {
    private static final PositionDiagnosticWindow WINDOW = new PositionDiagnosticWindow(
            Boolean.getBoolean("starfold.positionDiagnostics"),
            System.getProperty("starfold.positionDiagnostics.uuid"));
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("starfold-probe");
    public record State(String dimension, double x, double y, double z,
                        int teleportId, Vec3 awaiting, int listenerTick) {}

    public record Event(long id, long parent, int connection, String uuid, String operation, String phase,
                        int serverTick, String thread, State state, String detail,
                        List<String> stack) {}

    public record Scope(ServerPlayer player, PositionDiagnosticWindow.Cause cause, String operation) {}

    public static boolean enabled() {
        return WINDOW.enabled();
    }

    private static ServerPlayer target(Entity entity) {
        if (!enabled() || !(entity instanceof ServerPlayer player)) return null;
        // Like GroundInboundMixin: never sample mutable player/world state on the Netty pass.
        var server = player.server;
        if (server == null || !server.isSameThread()) return null;
        return WINDOW.matches(player.getUUID()) ? player : null;
    }

    private static State state(ServerPlayer player) {
        var access = (GroundPositionDiagnosticAccess) player.connection;
        return new State(player.level().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ(),
                access == null ? -1 : access.starfold$diagnosticTeleportId(),
                access == null ? null : access.starfold$diagnosticAwaiting(),
                access == null ? -1 : access.starfold$diagnosticListenerTick());
    }

    private static List<String> stack(long id, boolean meaningful) {
        if (!WINDOW.stack(id, meaningful)) return List.of();
        return StackWalker.getInstance().walk(frames -> frames
                .filter(f -> !f.getClassName().equals(DevPositionDiagnostics.class.getName()))
                .limit(PositionDiagnosticWindow.MAX_FRAMES)
                .map(Object::toString).toList());
    }

    private static void emit(ServerPlayer player, long id, long parent, int session,
                             String operation, String phase, String detail, boolean meaningful) {
        LOG.info("PROBE POSITION {}", new Event(id, parent, session, player.getUUID().toString(), operation, phase,
                player.server.getTickCount(), Thread.currentThread().getName(), state(player),
                detail, stack(id, meaningful)));
    }

    public static Scope begin(Entity entity, String operation, Supplier<String> detail,
                              boolean meaningful) {
        var player = target(entity);
        if (player == null) return null;
        var cause = WINDOW.begin(player.getUUID(), System.nanoTime(), player.connection);
        if (cause == null) return null;
        emit(player, cause.id(), cause.parent(), cause.connection(),
                operation, "before", detail.get(), meaningful);
        return new Scope(player, cause, operation);
    }

    /** Finally denotes scope closure, not successful completion; vanilla exceptions propagate. */
    public static void end(Scope scope) {
        if (scope == null) return;
        long id = WINDOW.end(scope.player().getUUID(), System.nanoTime(), scope.cause());
        if (id != 0) emit(scope.player(), id, scope.cause().id(), scope.cause().connection(),
                scope.operation(), "finally", "", false);
    }

    public static boolean jump(Entity entity, Vec3 destination) {
        var player = target(entity);
        return player != null && player.position().distanceToSqr(destination) >= 256;
    }

    public static String move(ServerboundMovePlayerPacket packet) {
        // Missing coordinates are explicitly absent, not filled from the player's mutable position.
        return "hasPosition=" + packet.hasPosition() + " xyz="
                + (packet.hasPosition() ? List.of(packet.getX(0), packet.getY(0), packet.getZ(0)) : "absent")
                + " hasRotation=" + packet.hasRotation() + " yaw=" + packet.getYRot(0)
                + " pitch=" + packet.getXRot(0) + " onGround=" + packet.isOnGround();
    }

    public static void mappedMove(ServerPlayer player, ServerboundMovePlayerPacket raw,
                                  ServerboundMovePlayerPacket mapped) {
        var scope = begin(player, "move-frame-map",
                () -> "raw={" + move(raw) + "} mapped={" + move(mapped) + "}", false);
        end(scope);
    }

    private DevPositionDiagnostics() {}
}
