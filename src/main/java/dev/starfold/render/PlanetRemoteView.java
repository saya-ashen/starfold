package dev.starfold.render;

import dev.starfold.universe.PlanetPackets;
import dev.starfold.worldgen.PlanetTerrain;

import java.util.*;

/** Bounded read-only cache, isolated by flight session and planet, never a block ledger. */
public final class PlanetRemoteView {
    private PlanetPackets.Profile profile;
    private PlanetTerrain terrain;
    private int planet = -1;
    private long revision = -1, generation;
    private BitSet changed = new BitSet(), unknown = new BitSet();
    private final GroundSurfaceCache surfaces = new GroundSurfaceCache();
    private final Map<Long, Long> surfaceRevisions = new HashMap<>();

    public record Snapshot(
            int planet,
            PlanetTerrain terrain,
            BitSet changed,
            Map<Long, GroundSurface> surfaces,
            long generation,
            net.minecraft.resources.ResourceLocation biome) {
        public Snapshot(
                int planet,
                PlanetTerrain terrain,
                BitSet changed,
                Map<Long, GroundSurface> surfaces,
                long generation) {
            this(
                    planet,
                    terrain,
                    changed,
                    surfaces,
                    generation,
                    net.minecraft.resources.ResourceLocation.withDefaultNamespace("plains"));
        }
    }

    public boolean summary(PlanetRemotePackets.Summary packet) {
        if (!packet.profile().surfaceCompatible()) return false;
        if (profile != null
                && profile.equals(packet.profile())
                && planet == packet.planet()
                && packet.revision() < revision) return false;
        boolean identity =
                profile == null || !profile.equals(packet.profile()) || planet != packet.planet();
        var next = BitSet.valueOf(packet.changed());
        var missing = BitSet.valueOf(packet.unknown());
        boolean dirty =
                identity
                        || revision != packet.revision()
                        || !changed.equals(next)
                        || !unknown.equals(missing);
        if (identity) {
            surfaces.clear();
            surfaceRevisions.clear();
        }
        if (identity) terrain = packet.profile().terrain();
        profile = packet.profile();
        planet = packet.planet();
        revision = packet.revision();
        changed = next;
        unknown = missing;
        for (long key : List.copyOf(surfaces.keySet()))
            if (!changed.get((int) key) || unknown.get((int) key)) {
                surfaces.remove(key);
                surfaceRevisions.remove(key);
            }
        if (dirty) generation++;
        return dirty;
    }

    public boolean surface(PlanetRemotePackets.Surface packet) {
        var s = packet.surface();
        if (profile == null || !s.compatible()
                || packet.planet() != planet || s.session() != profile.session()
                || s.image()!=null && (!changed.get((int)s.key()) || unknown.get((int)s.key()))) return false;
        if (surfaceRevisions.getOrDefault(s.key(), -1L) >= s.revision()) return false;
        surfaceRevisions.put(s.key(),s.revision());
        if(s.image()==null) surfaces.remove(s.key());
        else surfaces.put(s.key(), s.image());
        generation++;
        return true;
    }

    /** The observer's own planet, from what the client holds of it: no summary tells of it while they are there. */
    public boolean own(
            PlanetPackets.Profile told,
            int id,
            BitSet marked,
            Map<Long, GroundSurface> held) {
        if(!told.surfaceCompatible()) return false;
        var known = new HashMap<Long, GroundSurface>();
        for (var e : held.entrySet())
            if (marked.get(e.getKey().intValue())) known.put(e.getKey(), e.getValue());
        if (profile != null
                && profile.equals(told)
                && planet == id
                && changed.equals(marked)
                && surfaces.equals(known)) return false;
        if (profile == null || !profile.equals(told)) terrain = told.terrain();
        profile = told;
        planet = id;
        revision = -1;
        changed = marked;
        unknown = new BitSet();
        surfaces.clear();
        surfaceRevisions.clear();
        surfaces.putAll(known);
        generation++;
        return true;
    }

    public PlanetPackets.Profile profile() { return profile; }

    public PlanetTerrain terrain() { return terrain; }

    public Snapshot snapshot() {
        return profile == null
                ? null
                : new Snapshot(
                        planet,
                        terrain,
                        (BitSet) changed.clone(),
                        surfaces.isEmpty() ? Map.of() : Collections.unmodifiableMap(new HashMap<>(surfaces)),
                        generation,
                        profile.biome());
    }

    public long generation() {
        return generation;
    }

    public long session() {
        return profile == null ? 0 : profile.session();
    }

    public int size() {
        return surfaces.size();
    }
}
