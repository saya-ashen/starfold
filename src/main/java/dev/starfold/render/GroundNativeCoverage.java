package dev.starfold.render;

import dev.starfold.api.ChunkWorkDiagnostics;
import dev.starfold.api.RenderWorkDiagnostics;
import dev.starfold.api.RenderWorkDiagnostics.Metric;
import dev.starfold.planet.GroundTopology;
import dev.starfold.planet.PlanetStorage;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

import java.util.*;

/** Only nonempty compiled sections actually drawn in this frame can own native coverage. */
public final class GroundNativeCoverage {
    private static final PassWindow opaque = new PassWindow();
    private static final PassWindow complete = new PassWindow();
    private static final Long2ObjectOpenHashMap<BitSet> translucent =
            new Long2ObjectOpenHashMap<>();
    private static final BitSet NONE = new BitSet();
    private static final ArrayDeque<BitSet> bitsets = new ArrayDeque<>();
    private static boolean listBuilt, tracking;

    /** Separate current-frame evidence: late-only sections cannot delay the opaque mask. */
    static final class PassWindow {
        // fastutil mixes the key: Long.hashCode of a ChunkPos long is x ^ z, so diagonals collide.
        final Long2ObjectOpenHashMap<BitSet> submitted = new Long2ObjectOpenHashMap<>();
        final Long2ObjectOpenHashMap<BitSet> required = new Long2ObjectOpenHashMap<>();
        final it.unimi.dsi.fastutil.longs.LongOpenHashSet dirty =
                new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        final it.unimi.dsi.fastutil.longs.LongOpenHashSet covered =
                new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        final Set<Long> result = new HashSet<>();
        Set<Long> visible = Set.of();

        void reset() {
            recycle(submitted);
            dirty.clear();
            covered.clear();
            result.clear();
            visible = Set.of();
        }

        void record(long column, int sections, int index, BitSet water, boolean waterLayer) {
            if (recordSubmission(bits(submitted, column, sections), water, index, waterLayer)
                    && !covered.contains(column)) dirty.add(column);
        }

        boolean ready(long column, int sections, java.util.function.IntPredicate occupied) {
            boolean capture = RenderWorkDiagnostics.active();
            long started = capture ? RenderWorkDiagnostics.start() : 0;
            try {
                return surfaceReady(
                        sections,
                        occupied,
                        submitted.getOrDefault(column, NONE),
                        required.getOrDefault(column, NONE),
                        capture);
            } finally {
                if (capture) {
                    RenderWorkDiagnostics.add(Metric.NATIVE_READY_TESTS, 1);
                    RenderWorkDiagnostics.finish(Metric.NATIVE_READY_NS, started);
                }
            }
        }
    }

    /** Vanilla rebuilt its visible-section list; AFTER_SKY still sees the previous frame's. */
    public static void setupRender() {
        recycle(complete.required);
        recycle(opaque.required);
        listBuilt = false;
    }

    public static void beginFrame() {
        resetDraws();
        tracking = GroundClient.ready();
    }

    static void resetDraws() {
        opaque.reset();
        complete.reset();
        recycle(translucent);
    }

    static PassWindow pass(boolean translucentPass) {
        return translucentPass ? complete : opaque;
    }

    public static void drawn(SectionRenderDispatcher.RenderSection section, RenderType layer) {
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
        boolean waterLayer = layer == RenderType.translucent();
        var water = waterLayer ? bits(translucent, key, mc.level.getSectionsCount()) : NONE;
        recordDraw(key, mc.level.getSectionsCount(), index, layer, water);
        var trace = ChunkWorkDiagnostics.listener();
        if (trace != null) trace.drawn(key, p.getY() >> 4);
    }

    /** Shared by actual post-draw submissions and ordering regressions. */
    static void recordDraw(long column, int sections, int index, RenderType layer, BitSet water) {
        if (RenderWorkDiagnostics.active())
            RenderWorkDiagnostics.add(Metric.NATIVE_SUBMITTED_SECTIONS, 1);
        complete.record(column, sections, index, water, layer == RenderType.translucent());
        if (opaqueLayer(layer)) opaque.record(column, sections, index, NONE, false);
    }

    static boolean opaqueLayer(RenderType layer) {
        return layer != RenderType.translucent() && layer != RenderType.tripwire();
    }

    /** A second layer in an already submitted section still changes surface readiness. */
    static boolean recordSubmission(BitSet submitted, BitSet water, int index, boolean waterLayer) {
        boolean changed = !submitted.get(index);
        submitted.set(index);
        if (waterLayer) {
            changed |= !water.get(index);
            water.set(index);
        }
        return changed;
    }

    static Set<Long> visible() {
        return visible(true);
    }

    /**
     * Timings cover selected work only: chunk lookup, dirty iteration, collection updates and
     * result copying are outside these scopes. The listed requirements are built on first demand
     * for both passes; section submission counts include repeated layer draws.
     */
    static Set<Long> visible(boolean translucentPass) {
        boolean capture = RenderWorkDiagnostics.active();
        if (capture) {
            RenderWorkDiagnostics.add(Metric.NATIVE_VISIBLE_CALLS, 1);
            RenderWorkDiagnostics.add(
                    translucentPass ? Metric.NATIVE_COMPLETE_CALLS : Metric.NATIVE_OPAQUE_CALLS, 1);
        }
        long dirtyColumns = 0, mappedColumns = 0, coveredColumns = 0, missingColumns = 0;
        try {
            var mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) return Set.of();
            var state = pass(translucentPass);
            if (state.dirty.isEmpty()) return state.visible;
            if (!listBuilt) {
                listed(mc);
                listBuilt = true;
            }
            boolean changed = false;
            var changes = state.dirty.iterator();
            while (changes.hasNext()) {
                long column = changes.nextLong();
                if (capture) dirtyColumns++;
                var pos = new ChunkPos(column);
                var chunk = mc.level.getChunkSource().getChunkNow(pos.x, pos.z);
                if (chunk == null) continue;
                var sections = chunk.getSections();
                // Every listed occupied section relevant to this pass must actually draw, including
                // lower cliff sections. Opaque never waits for late-only water/glass/tripwire
                // sections.
                if (!state.ready(column, sections.length, i -> !sections[i].hasOnlyAir())) {
                    if (capture) missingColumns++;
                    continue;
                }
                // The complete/translucent window still cannot hide water before its real draw.
                if (translucentPass
                        && !waterSurfaceReady(chunk, translucent.getOrDefault(column, NONE)))
                    continue;
                if (capture) mappedColumns++;
                long mapStarted = capture ? RenderWorkDiagnostics.start() : 0;
                long key;
                try {
                    var storage =
                            GroundClient.storage(
                                    pos.getMinBlockX() + 8.0, 128, pos.getMinBlockZ() + 8.0);
                    // A chart handoff can leave old visible sections in the renderer for a frame.
                    if (PlanetStorage.find(storage.x(), storage.z()) != GroundClient.planet())
                        continue;
                    var local =
                            PlanetStorage.local(
                                    GroundClient.planet(),
                                    BlockPos.containing(storage.x(), storage.y(), storage.z()));
                    if (!GroundTopology.inChart(GroundClient.shape(), local)) continue;
                    var address = GroundTopology.address(GroundClient.shape(), local);
                    key =
                            GroundSurfaceIndex.key(
                                    address.owner().getX() >> 4, address.owner().getZ() >> 4);
                } finally {
                    if (capture) RenderWorkDiagnostics.finish(Metric.NATIVE_MAP_NS, mapStarted);
                }
                if (key >= 0) {
                    state.covered.add(column);
                    changed |= state.result.add(key);
                    if (capture) coveredColumns++;
                }
            }
            state.dirty.clear();
            if (changed) state.visible = Collections.unmodifiableSet(new HashSet<>(state.result));
            return state.visible;
        } finally {
            if (capture) {
                RenderWorkDiagnostics.add(Metric.NATIVE_DIRTY_COLUMNS, dirtyColumns);
                RenderWorkDiagnostics.add(Metric.NATIVE_MAP_COLUMNS, mappedColumns);
                RenderWorkDiagnostics.add(Metric.NATIVE_COVERED_COLUMNS, coveredColumns);
                RenderWorkDiagnostics.add(Metric.NATIVE_MISSING_COLUMNS, missingColumns);
                RenderWorkDiagnostics.add(
                        translucentPass
                                ? Metric.NATIVE_COMPLETE_DIRTY_COLUMNS
                                : Metric.NATIVE_OPAQUE_DIRTY_COLUMNS,
                        dirtyColumns);
                RenderWorkDiagnostics.add(
                        translucentPass
                                ? Metric.NATIVE_COMPLETE_COVERED_COLUMNS
                                : Metric.NATIVE_OPAQUE_COVERED_COLUMNS,
                        coveredColumns);
            }
        }
    }

    /**
     * A seabed draw cannot own the water above it, including water vanilla did not list. Check
     * exposed tops only: buried fluids and unlisted canopy retain the existing contract. A current
     * dry palette proves that no top in that section can reject; retain that evidence only within
     * this call. Palette counts include lazy tests reached during the scan; the scan timer includes
     * those tests, while the palette timer covers the initial gate only.
     */
    static boolean waterSurfaceReady(ChunkAccess chunk, BitSet submitted) {
        boolean capture = RenderWorkDiagnostics.active();
        long started = capture ? RenderWorkDiagnostics.start() : 0;
        long palettes = 0, cells = 0, fluids = 0, fastAccepts = 0, fallbacks = 0;
        try {
            boolean needsScan;
            int sectionCount = chunk.getSectionsCount();
            LevelChunkSection[] sections;
            // Per-call only: packets can mutate these same objects between calls. Zero means
            // unknown, one proves dry, two requires the original fluid lookup.
            byte[] waterSections = null;
            long paletteStarted = capture ? RenderWorkDiagnostics.start() : 0;
            try {
                sections = chunk.getSections();
                needsScan = sections == null || sections.length != sectionCount;
                if (!needsScan) {
                    for (int index = 0; index < sections.length; index++) {
                        if (submitted.get(index)) continue;
                        var section = sections[index];
                        if (capture && section != null) palettes++;
                        // Palette false positives only cost a scan. Unknown data cannot prove
                        // readiness.
                        if (section == null
                                || section.maybeHas(
                                        state ->
                                                state.getFluidState()
                                                        .getType()
                                                        .isSame(Fluids.WATER))) {
                            waterSections = new byte[sectionCount];
                            for (int dry = 0; dry < index; dry++)
                                if (!submitted.get(dry)) waterSections[dry] = 1;
                            if (section != null) waterSections[index] = 2;
                            needsScan = true;
                            break;
                        }
                    }
                }
            } finally {
                if (capture)
                    RenderWorkDiagnostics.finish(Metric.NATIVE_WATER_PALETTE_NS, paletteStarted);
            }
            // The scan can reject only water in an undrawn section. Read current palettes each
            // call:
            // client packets and block updates can mutate the same chunk and section objects.
            if (!needsScan) {
                if (capture) fastAccepts++;
                return true;
            }
            if (capture) fallbacks++;
            long scanStarted = capture ? RenderWorkDiagnostics.start() : 0;
            try {
                var pos = new BlockPos.MutableBlockPos();
                int minX = chunk.getPos().getMinBlockX();
                int minZ = chunk.getPos().getMinBlockZ();
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (capture) cells++;
                        int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                        int index = chunk.getSectionIndex(y);
                        // Most ocean tiles use one section; skip block lookup once its water layer
                        // drew.
                        if (index < 0 || index >= sectionCount || submitted.get(index)) continue;
                        if (waterSections != null) {
                            if (waterSections[index] == 0) {
                                var section = sections[index];
                                if (section != null) {
                                    if (capture) palettes++;
                                    waterSections[index] =
                                            (byte)
                                                    (section.maybeHas(
                                                                    state ->
                                                                            state.getFluidState()
                                                                                    .getType()
                                                                                    .isSame(
                                                                                            Fluids
                                                                                                    .WATER))
                                                            ? 2
                                                            : 1);
                                }
                            }
                            if (waterSections[index] == 1) continue;
                        }
                        pos.set(minX + x, y, minZ + z);
                        if (capture) fluids++;
                        if (chunk.getFluidState(pos).getType().isSame(Fluids.WATER)) return false;
                    }
                }
                return true;
            } finally {
                if (capture) RenderWorkDiagnostics.finish(Metric.NATIVE_WATER_SCAN_NS, scanStarted);
            }
        } finally {
            if (capture) {
                RenderWorkDiagnostics.add(Metric.NATIVE_WATER_CALLS, 1);
                RenderWorkDiagnostics.add(Metric.NATIVE_WATER_FALLBACKS, fallbacks);
                RenderWorkDiagnostics.add(Metric.NATIVE_WATER_PALETTE_SECTIONS, palettes);
                RenderWorkDiagnostics.add(Metric.NATIVE_WATER_FAST_ACCEPTS, fastAccepts);
                RenderWorkDiagnostics.add(Metric.NATIVE_WATER_SURFACE_CELLS, cells);
                RenderWorkDiagnostics.add(Metric.NATIVE_WATER_FLUID_LOOKUPS, fluids);
                RenderWorkDiagnostics.finish(Metric.NATIVE_WATER_NS, started);
            }
        }
    }

    /**
     * Every listed occupied section must be drawn this frame, with at least one present. Unlisted
     * buried sections or canopy do not hold back coverage of the column.
     */
    static boolean surfaceReady(BitSet occupied, BitSet drawn, BitSet listed) {
        return surfaceReady(occupied.length(), occupied::get, drawn, listed, false);
    }

    private static boolean surfaceReady(
            int sections,
            java.util.function.IntPredicate occupied,
            BitSet drawn,
            BitSet listed,
            boolean capture) {
        long checks = 0;
        try {
            boolean ready = false;
            for (int index = listed.nextSetBit(0);
                    index >= 0 && index < sections;
                    index = listed.nextSetBit(index + 1)) {
                if (capture) checks++;
                if (!occupied.test(index)) continue;
                if (!drawn.get(index)) return false;
                ready = true;
            }
            return ready;
        } finally {
            if (capture) RenderWorkDiagnostics.add(Metric.NATIVE_READY_SECTIONS, checks);
        }
    }

    static boolean requiresOpaqueSubmission(SectionRenderDispatcher.CompiledSection compiled) {
        if (compiled == SectionRenderDispatcher.CompiledSection.UNCOMPILED) return true;
        for (var layer : RenderType.chunkBufferLayers())
            if (opaqueLayer(layer) && !compiled.isEmpty(layer)) return true;
        return false;
    }

    /** Compiled empty sections leave no hole; unknown or renderable sections still require draw. */
    static boolean requiresSubmission(boolean uncompiled, boolean noRenderableLayers) {
        return uncompiled || !noRenderableLayers;
    }

    /** Stable completed-frame evidence, independent of the current draw stage's mutable window. */
    static final class FinalWindow {
        private Set<Long> snapshot = Set.of();

        boolean complete(boolean translucent, Set<Long> current) {
            if (!translucent) return false;
            // visible() normally returns an immutable set; copying also seals caller-owned sets.
            if (snapshot != current && !snapshot.equals(current)) snapshot = Set.copyOf(current);
            return true;
        }

        Set<Long> snapshot() {
            return snapshot;
        }

        void reset() {
            snapshot = Set.of();
        }
    }

    /** Sections vanilla lists as visible this frame; an occluded buried section is absent. */
    private static void listed(Minecraft mc) {
        boolean capture = RenderWorkDiagnostics.active();
        long started = capture ? RenderWorkDiagnostics.start() : 0;
        long visited = 0;
        try {
            for (var section :
                    ((dev.starfold.mixin.GroundVisibleSectionsAccess) mc.levelRenderer)
                            .starfold$visibleSections()) {
                if (capture) visited++;
                // Vanilla lists every neighbour of the camera's section, buried or not; a compiled
                // section with nothing to draw can leave no hole, so it does not hold the cover
                // back.
                var compiled = section.getCompiled();
                var p = section.getOrigin();
                int index = mc.level.getSectionIndex(p.getY());
                if (index < 0 || index >= mc.level.getSectionsCount()) continue;
                long column = ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4);
                recordListed(column, mc.level.getSectionsCount(), index, compiled);
            }
        } finally {
            if (capture) {
                RenderWorkDiagnostics.add(Metric.NATIVE_REQUIRED_SECTIONS, visited);
                RenderWorkDiagnostics.finish(Metric.NATIVE_REQUIRED_NS, started);
            }
        }
    }

    static void recordListed(
            long column,
            int sections,
            int index,
            SectionRenderDispatcher.CompiledSection compiled) {
        if (!requiresSubmission(
                compiled == SectionRenderDispatcher.CompiledSection.UNCOMPILED,
                compiled.hasNoRenderableLayers())) return;
        bits(complete.required, column, sections).set(index);
        if (requiresOpaqueSubmission(compiled)) bits(opaque.required, column, sections).set(index);
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
