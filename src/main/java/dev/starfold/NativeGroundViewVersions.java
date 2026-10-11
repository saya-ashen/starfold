package dev.starfold;
import java.util.*;
/** In-memory invalidation stamps; source chunks remain authoritative. Reset on server stop. */
public final class NativeGroundViewVersions {
    private final Map<Long,Long> versions=new HashMap<>();private long revision;
    public long get(long key){return versions.getOrDefault(key,0L);}
    public void changed(long key){if(key>=0&&key<(long)NativePlanetShape.count()*NativePlanetStorage.CHUNKS_PER_PLANET)versions.put(key,++revision);}
}
