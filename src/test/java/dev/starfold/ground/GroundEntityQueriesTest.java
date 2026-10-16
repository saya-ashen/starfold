package dev.starfold.ground;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.PlanetShape;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetStorage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.*;
import net.minecraft.world.phys.AABB;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Stream;

class GroundEntityQueriesTest {
    record Entry(int id, AABB box, boolean removed) implements EntityAccess {
        public int getId() {
            return id;
        }

        public UUID getUUID() {
            return new UUID(0, id);
        }

        public BlockPos blockPosition() {
            return BlockPos.containing(box.getCenter());
        }

        public AABB getBoundingBox() {
            return box;
        }

        public void setLevelCallback(EntityInLevelCallback callback) {}

        public Stream<Entry> getSelfAndPassengers() {
            return Stream.of(this);
        }

        public Stream<Entry> getPassengersAndSelf() {
            return Stream.of(this);
        }

        public void setRemoved(Entity.RemovalReason reason) {}

        public boolean shouldBeSaved() {
            return true;
        }

        public boolean isAlwaysTicking() {
            return false;
        }
    }

    @Test
    void inverseSectionLookupsEqualTheFullWalkIncludingHaloEntities() {
        try {
            for (int size : new int[] {512, 1024, 2048}) compare(size);
        } finally {
            PlanetShape.reset();
        }
    }

    @Test
    void storageLookupBypassesSablesProjectionAndSizeGuard() throws Exception {
        var node = new org.objectweb.asm.tree.ClassNode();
        try (var in =
                dev.starfold.TestArchiveResources.open(
                        "dev/ryanhcode/sable/util/SubLevelInclusiveLevelEntityGetter.class")) {
            assertNotNull(in);
            new org.objectweb.asm.ClassReader(in).accept(node, 0);
        }
        int overloads = 0;
        for (var method : node.methods) {
            if (!method.name.equals("getIgnoringSubLevels")) continue;
            overloads++;
            var calls = new ArrayList<String>();
            for (var instruction : method.instructions)
                if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call)
                    calls.add(call.owner + "." + call.name);
            assertEquals(List.of("net/minecraft/world/level/entity/LevelEntityGetter.get"), calls);
        }
        assertEquals(2, overloads);
        var source = dev.starfold.Sources.text("GroundEntityQueriesMixin");
        assertTrue(source.contains("storage.getIgnoringSubLevels"));
        assertTrue(source.contains("getAllIntersecting"));
        assertTrue(source.contains("level.getPartEntities().isEmpty()"));
        assertFalse(source.contains("GroundSeams.entities(level)"));
    }

    private static void compare(int size) {
        PlanetShape.system(new int[] {size, size}, new int[] {191, 191});
        var access = PlanetAccess.SHARED;
        var random = new Random(20261009);
        var entities = new ArrayList<Entry>();
        var sections = new EntitySectionStorage<Entry>(Entry.class, key -> Visibility.TICKING);
        for (int slot = 0; slot < 2; slot++)
            for (int face = 0; face < 6; face++) {
                var base = access.origin(slot, face);
                for (int i = 0; i < 240; i++) {
                    double x = base.x() + random.nextDouble(-size, size * 2),
                            z = base.z() + random.nextDouble(-size, size * 2);
                    double width = random.nextDouble(.1, 4), height = random.nextDouble(.1, 4);
                    var box =
                            new AABB(
                                    x - width / 2,
                                    245,
                                    z - width / 2,
                                    x + width / 2,
                                    245 + height,
                                    z + width / 2);
                    add(entities, sections, box, i % 23 == 0);
                }
            }
        // A neighbour's halo can project deep into the root face, before transport runs.
        var root = access.origin(0, 0);
        var query = new AABB(root.x() + 30, 244, root.z() + 30, root.x() + 34, 251, root.z() + 34);
        var inverse = GroundSeams.queryBoxes(access, query).get(1);
        add(entities, sections, inverse.deflate(.5), false);
        equal(entities, sections, query);
        // Entities outside charts retain their ordinary boxes in the old mapper.
        add(entities, sections, new AABB(27000, 245, 100, 27001, 247, 101), false);
        add(entities, sections, new AABB(-3000, 245, -3000, -2999, 247, -2999), false);
        for (int slot = 0; slot < 2; slot++)
            for (int face = 0; face < 6; face++) {
                var base = access.origin(slot, face);
                double[] points = {-1, .2, 2, size / 2.0, size - 2, size - .2, size + 1};
                for (double x : points)
                    for (double z : points)
                        for (double reach : new double[] {.3, 4, 24})
                            equal(
                                    entities,
                                    sections,
                                    new AABB(
                                            base.x() + x - reach,
                                            244,
                                            base.z() + z - reach,
                                            base.x() + x + reach,
                                            251,
                                            base.z() + z + reach));
            }
        equal(entities, sections, new AABB(26990, 244, 90, 27010, 251, 110));
        equal(entities, sections, new AABB(-3010, 244, -3010, -2990, 251, -2990));
        // A query large enough to cover a storage gap and the other planet.
        equal(
                entities,
                sections,
                new AABB(-100, 244, -100, PlanetStorage.offset(1) + size, 251, size));
    }

    private static void add(
            List<Entry> entities, EntitySectionStorage<Entry> sections, AABB box, boolean removed) {
        var e = new Entry(entities.size(), box, removed);
        entities.add(e);
        sections.getOrCreateSection(SectionPos.asLong(e.blockPosition())).add(e);
    }

    private static void equal(
            List<Entry> entities, EntitySectionStorage<Entry> sections, AABB query) {
        var access = PlanetAccess.SHARED;
        var old = new HashSet<Integer>();
        for (var e : entities) if (matches(e, query)) old.add(e.id());
        var indexed = new LinkedHashSet<Integer>();
        var boxes = GroundSeams.queryBoxes(access, query);
        for (int i = 1; i < boxes.size(); i++)
            sections.getEntities(
                    boxes.get(i).inflate(2),
                    e -> {
                        if (geometricMatch(e, query)) indexed.add(e.id());
                        return net.minecraft.util.AbortableIterationConsumer.Continuation.CONTINUE;
                    });
        boolean interior =
                access.inChart(BlockPos.containing(query.getCenter()))
                        && GroundSeams.interior(access, query, 2)
                        && indexed.isEmpty();
        sections.getEntities(
                interior ? query : query.inflate(2),
                e -> {
                    if (interior
                            ? !e.removed() && query.intersects(e.box())
                            : geometricMatch(e, query)) indexed.add(e.id());
                    return net.minecraft.util.AbortableIterationConsumer.Continuation.CONTINUE;
                });
        indexed.removeIf(id -> id % 3 == 0);
        assertEquals(old, indexed, "size=" + PlanetShape.of(0).size() + " query=" + query);
        // Typed selection and a pre-filled result list obey the same predicate and cap.
        var typedOld =
                old.stream().filter(id -> id % 2 == 0).collect(java.util.stream.Collectors.toSet());
        var typedNew = indexed.stream().filter(id -> id % 2 == 0).toList();
        assertEquals(typedOld, new HashSet<>(typedNew));
        var capped = new ArrayList<Integer>(List.of(-1));
        for (int id : typedNew) {
            if (capped.size() >= 3) break;
            capped.add(id);
        }
        assertEquals(Math.min(3, typedOld.size() + 1), capped.size());
        assertTrue(typedOld.containsAll(capped.subList(1, capped.size())));
    }

    private static boolean matches(Entry e, AABB query) {
        // Model the caller's exclusion/predicate separately from candidate enumeration.
        return e.id() % 3 != 0 && geometricMatch(e, query);
    }

    private static boolean geometricMatch(Entry e, AABB query) {
        var image =
                GroundSeams.box(
                        PlanetAccess.SHARED, e.box(), query.getCenter().x, query.getCenter().z);
        return !e.removed() && image != null && query.intersects(image);
    }
}
