package dev.starfold;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Optional development instrumentation. The release mod installs no listeners. */
public final class DevelopmentHooks {
    public static BooleanSupplier driving = () -> false;
    public static Predicate<UUID> alwaysTicket = ship -> true;
    public static Predicate<UUID> heldTicket = ship -> false;
    public static Runnable beforeKeys = () -> {};
    public static java.util.function.Consumer<ServerLevel> tickLevel = level -> {};
    public static MoveListener mappedMove = (player, packet, mapped) -> {};
    public static Arrival arrival = new Arrival() {};
    public static Relight relight = new Relight() {};
    public static boolean remoteDraw, ownDraw;
    public static long parentNanos;

    public interface MoveListener {
        void accept(ServerPlayer player, ServerboundMovePlayerPacket packet, ServerboundMovePlayerPacket mapped);
    }

    public interface Arrival {
        default boolean enabled() { return false; }
        default long start() { return 0; }
        default void end(String name, long start) {}
        default void seamHeld(ServerLevel level, long chunk, boolean ready) {}
        default void missingRead(ServerLevel level, BlockPos query, BlockPos owner) {}
        default void log(String message, Object... values) {}
    }

    public interface Relight {
        default boolean enabled() { return false; }
        default void changed(ServerLevel level, BlockPos owner) {}
        default void stream(ServerPlayer player, int queued, int processed, long nanos, int sent,
                int pending, int remaining, int dirty, int lit, long oldestAge) {}
        default void mismatch(int planet, long image, boolean identity, byte[] emitted, byte[] reference) {}
    }

    private DevelopmentHooks() {}
}
