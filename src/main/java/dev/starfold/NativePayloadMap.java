package dev.starfold;

import net.minecraft.server.level.ServerPlayer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Other mods' own packets that carry places. NativePacketMap relabels vanilla's packets for each observer; a mod's
 *  payload is opaque to it, so each kind that names a place registers here how it is relabelled. Whatever sends such
 *  a payload outside the player's connection passes it through {@link #relabel} itself. */
public final class NativePayloadMap {
    public interface Relabel<T>{T apply(ServerPlayer observer,NativeFrame frame,T payload);}
    private static final Map<Class<?>,Relabel<?>> KINDS=new ConcurrentHashMap<>();
    public static <T> void register(Class<T> kind,Relabel<T> relabel){KINDS.put(kind,relabel);}
    /** The payload as this observer's client must see it; the same object where nothing changes. */
    @SuppressWarnings("unchecked") public static <T> T relabel(ServerPlayer observer,T payload){
        var kind=(Relabel<T>)KINDS.get(payload.getClass());
        return kind==null||!NativeFrames.active(observer)?payload:kind.apply(observer,NativeFrames.get(observer),payload);
    }
    private NativePayloadMap(){}
}
