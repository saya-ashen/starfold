package dev.starfold.ground;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Other mods' own packets that carry places. PacketMap relabels vanilla's packets for each observer; a mod's
 *  payload is opaque to it, so each kind that names a place registers here how it is relabelled. Whatever sends such
 *  a payload outside the player's connection passes it through {@link #relabel} itself. */
public final class PayloadMap {
    public interface Relabel<T> {
        T apply(ServerPlayer observer, ObserverFrame frame, T payload);
    }

    private static final Map<Class<?>, Relabel<?>> KINDS = new ConcurrentHashMap<>();

    public static <T> void register(Class<T> kind, Relabel<T> relabel) {
        KINDS.put(kind, relabel);
    }

    /** The payload as this observer's client must see it; the same object where nothing changes. */
    @SuppressWarnings("unchecked")
    public static <T> T relabel(ServerPlayer observer, T payload) {
        var kind = (Relabel<T>) KINDS.get(payload.getClass());
        return kind == null || !ObserverFrames.active(observer)
                ? payload
                : kind.apply(observer, ObserverFrames.get(observer), payload);
    }

    private PayloadMap() {}
}
