package dev.starfold.render;

import dev.starfold.universe.PlanetPackets;
import dev.starfold.worldgen.PlanetTerrain;

import java.util.*;

/** Bounded read-only cache, isolated by flight session and planet, never a block ledger. */
public final class PlanetRemoteView {
    private PlanetPackets.Profile profile;
    private int planet = -1;
    private long revision = -1, generation;
    private BitSet changed = new BitSet(), unknown = new BitSet();
    private final LinkedHashMap<Long, GroundViewCache.Surface> surfaces =
            new LinkedHashMap<>(16, .75f, true);

    public record Snapshot(
            int planet,
            PlanetTerrain terrain,
            BitSet changed,
            Map<Long, GroundViewCache.Surface> surfaces,
            long generation,
            net.minecraft.resources.ResourceLocation biome) {
        public Snapshot(
                int planet,
                PlanetTerrain terrain,
                BitSet changed,
                Map<Long, GroundViewCache.Surface> surfaces,
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
        if (identity) surfaces.clear();
        profile = packet.profile();
        planet = packet.planet();
        revision = packet.revision();
        changed = next;
        unknown = missing;
        surfaces.keySet().removeIf(k -> !changed.get(k.intValue()) || unknown.get(k.intValue()));
        if (dirty) generation++;
        return dirty;
    }

    public boolean surface(PlanetRemotePackets.Surface packet) {
        var s = packet.surface();
        if (profile == null
                || packet.planet() != planet
                || s.session() != profile.session()
                || !changed.get((int) s.key())
                || unknown.get((int) s.key())) return false;
        var old = surfaces.get(s.key());
        if (old != null && old.revision() >= s.image().revision()) return false;
        surfaces.put(s.key(), s.image());
        while (surfaces.size() > 4096) surfaces.remove(surfaces.keySet().iterator().next());
        generation++;
        return true;
    }

    /** The observer's own planet, from what the client holds of it: no summary tells of it while they are there. */
    public boolean own(
            PlanetPackets.Profile told,
            int id,
            BitSet marked,
            Map<Long, GroundViewCache.Surface> held) {
        var known = new HashMap<Long, GroundViewCache.Surface>();
        for (var e : held.entrySet())
            if (marked.get(e.getKey().intValue())) known.put(e.getKey(), e.getValue());
        if (profile != null
                && profile.equals(told)
                && planet == id
                && changed.equals(marked)
                && surfaces.equals(known)) return false;
        profile = told;
        planet = id;
        revision = -1;
        changed = marked;
        unknown = new BitSet();
        surfaces.clear();
        surfaces.putAll(known);
        generation++;
        return true;
    }

    public Snapshot snapshot() {
        return profile == null
                ? null
                : new Snapshot(
                        planet,
                        new PlanetTerrain(profile.seed(), profile.version(), profile.generation()),
                        (BitSet) changed.clone(),
                        Map.copyOf(surfaces),
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
