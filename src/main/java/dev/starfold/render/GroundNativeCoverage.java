package dev.starfold.render;

import dev.starfold.planet.GroundTopology;
import dev.starfold.planet.PlanetStorage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.*;

/** Only nonempty compiled sections actually drawn in this frame can own native coverage. */
public final class GroundNativeCoverage {
    // fastutil mixes the key: Long.hashCode of a ChunkPos long is x ^ z, so diagonals collide.
    private static final Long2ObjectOpenHashMap<BitSet> drawn = new Long2ObjectOpenHashMap<>();
    private static final BitSet NONE = new BitSet();
    private static final Long2ObjectOpenHashMap<BitSet> listed = new Long2ObjectOpenHashMap<>();
    private static final ArrayDeque<BitSet> bitsets = new ArrayDeque<>();
    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet dirty = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet covered = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private static final Set<Long> result = new HashSet<>();
    private static boolean listBuilt, tracking;
    private static Set<Long> visible = Set.of();

    /** Vanilla rebuilt its visible-section list; AFTER_SKY still sees the previous frame's. */
    public static void setupRender() {
        recycle(listed);
        listBuilt = false;
    }

    public static void beginFrame() {
        recycle(drawn);
        dirty.clear();
        covered.clear();
        result.clear();
        visible = Set.of();
        tracking = GroundClient.ready();
    }

    public static void drawn(SectionRenderDispatcher.RenderSection section) {
        var mc = Minecraft.getInstance();
        if (!tracking) return;
        var compiled = section.getCompiled();
        if (compiled == SectionRenderDispatcher.CompiledSection.UNCOMPILED
                || compiled.hasNoRenderableLayers()) return;
        var p = section.getOrigin();
        int index = mc.level.getSectionIndex(p.getY());
        if (index < 0 || index >= mc.level.getSectionsCount()) return;
        // Planets are taller than 64 sections; an unsized BitSet would regrow on every high set().
        long key = ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4);
        var bits = bits(drawn, key, mc.level.getSectionsCount());
        if (!bits.get(index)) {
            bits.set(index);
            if (!covered.contains(key)) dirty.add(key);
        }
    }

    static Set<Long> visible() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return Set.of();
        if (dirty.isEmpty()) return visible;
        if (!listBuilt) {
            listed(mc);
            listBuilt = true;
        }
        boolean changed = false;
        var changes = dirty.iterator();
        while (changes.hasNext()) {
            long column = changes.nextLong();
            var pos = new ChunkPos(column);
            var chunk = mc.level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk == null) continue;
            var sections = chunk.getSections();
            // surfaceReady reads only the top two occupied sections; planets have hundreds.
            // No horizontal near-player exemption: at altitude only empty air may be visible.
            var shown = listed.getOrDefault(column, NONE);
            // A canopy above the camera leaves the frustum when looking down; vanilla does not
            // list it, so the surface is the highest occupied section vanilla means to draw.
            int top = sections.length - 1;
            while (top >= 0 && (sections[top].hasOnlyAir() || !shown.get(top))) top--;
            int below = top - 1;
            while (below >= 0 && sections[below].hasOnlyAir()) below--;
            if (!surfaceReady(top, below, drawn.get(column), shown)) continue;
            var storage =
                    GroundClient.storage(pos.getMinBlockX() + 8.0, 128, pos.getMinBlockZ() + 8.0);
            // A chart handoff can leave old visible sections in the renderer for a frame.
            if (PlanetStorage.find(storage.x(), storage.z()) != GroundClient.planet()) continue;
            var local =
                    PlanetStorage.local(
                            GroundClient.planet(),
                            BlockPos.containing(storage.x(), storage.y(), storage.z()));
            if (!GroundTopology.inChart(GroundClient.shape(), local)) continue;
            var address = GroundTopology.address(GroundClient.shape(), local);
            long key =
                    GroundSurfaceIndex.key(address.owner().getX() >> 4, address.owner().getZ() >> 4);
            if (key >= 0) {
                covered.add(column);
                changed |= result.add(key);
            }
        }
        dirty.clear();
        if (changed) visible = Collections.unmodifiableSet(new HashSet<>(result));
        return visible;
    }

    /** The highest occupied section vanilla lists must be drawn this frame. The one below must be drawn
     * too, unless vanilla does not list it as visible at all: a buried section is occluded and never compiled. */
    static boolean surfaceReady(BitSet occupied, BitSet drawn, BitSet listed) {
        int top = occupied.length() - 1;
        while (top >= 0 && !(occupied.get(top) && listed.get(top))) top--;
        return surfaceReady(top, top < 0 ? -1 : occupied.previousSetBit(top - 1), drawn, listed);
    }

    private static boolean surfaceReady(int top, int below, BitSet drawn, BitSet listed) {
        if (top < 0 || !drawn.get(top)) return false;
        return below < 0 || drawn.get(below) || !listed.get(below);
    }

    /** Sections vanilla lists as visible this frame; an occluded buried section is absent. */
    private static void listed(Minecraft mc) {
        for (var section :
                ((dev.starfold.mixin.GroundVisibleSectionsAccess) mc.levelRenderer)
                        .starfold$visibleSections()) {
            // Vanilla lists every neighbour of the camera's section, buried or not; a compiled
            // section with nothing to draw can leave no hole, so it does not hold the cover back.
            var compiled = section.getCompiled();
            if (compiled != SectionRenderDispatcher.CompiledSection.UNCOMPILED
                    && compiled.hasNoRenderableLayers()) continue;
            var p = section.getOrigin();
            int index = mc.level.getSectionIndex(p.getY());
            if (index < 0 || index >= mc.level.getSectionsCount()) continue;
            bits(listed, ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4), mc.level.getSectionsCount())
                    .set(index);
        }
    }

    private static BitSet bits(Long2ObjectOpenHashMap<BitSet> map, long key, int sections) {
        var value = map.get(key);
        if (value == null) {
            value = bitsets.pollFirst();
            if (value == null) value = new BitSet(sections);
            map.put(key, value);
        }
        return value;
    }

    private static void recycle(Long2ObjectOpenHashMap<BitSet> map) {
        var entries = map.long2ObjectEntrySet().fastIterator();
        while (entries.hasNext()) {
            var value = entries.next().getValue();
            value.clear();
            bitsets.addLast(value);
        }
        map.clear();
    }

    private GroundNativeCoverage() {}
}
