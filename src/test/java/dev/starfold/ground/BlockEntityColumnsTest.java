package dev.starfold.ground;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;

class BlockEntityColumnsTest {
    @BeforeAll
    static void boot() {
        ImageStreamTestFixtures.boot();
    }

    private record Column(ImageStreamTestFixtures.Owner owner, int x, int z, BlockPos at) {}

    private record Update(BlockEntity entity, BlockPos at, CompoundTag tag) {}

    private static final class Entity extends BlockEntity {
        final CompoundTag tag = new CompoundTag();

        Entity(BlockPos pos) {
            super(BlockEntityType.CHEST, pos, Blocks.CHEST.defaultBlockState());
            tag.putInt("x", pos.getX());
            tag.putInt("y", pos.getY());
            tag.putInt("z", pos.getZ());
            var payload = new CompoundTag();
            payload.putString("owner", pos.toShortString());
            tag.put("payload", payload);
        }

        @Override
        public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
            return tag;
        }
    }

    private static ImageStreamTestFixtures.Owner owner(int x, int z, int columns) {
        var owner = ImageStreamTestFixtures.owner(new ChunkPos(x, z));
        for (int column = 0; column < columns; column++) {
            // Deliberately not height-sorted: preserve the owner's table iteration within a column.
            for (int y : new int[] {255, -63, 80}) {
                var pos = new BlockPos(x * 16 + column % 16, y, z * 16 + column / 16);
                owner.entities.put(pos, new Entity(pos));
            }
        }
        return owner;
    }

    private static List<Column> columns(
            ImageStreamTestFixtures.Owner a, ImageStreamTestFixtures.Owner b, int turns) {
        var frame = new ObserverFrame(0, 0, turns, 32, -16);
        var out = new ArrayList<Column>();
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                var at = new BlockPos(x + 64, 0, z - 96);
                var storage = frame.toStorage(at);
                out.add(new Column(x < 8 ? a : b, storage.getX() & 15, storage.getZ() & 15, at));
            }
        return out;
    }

    /** The baseline's per-column full scan and independent tag copying/coordinate rewrite. */
    private static List<Update> oldWalk(List<Column> columns) {
        var out = new ArrayList<Update>();
        for (var c : columns)
            for (var be : c.owner.getBlockEntities().values()) {
                var pos = be.getBlockPos();
                if ((pos.getX() & 15) != c.x || (pos.getZ() & 15) != c.z) continue;
                var at = new BlockPos(c.at.getX(), pos.getY(), c.at.getZ());
                var tag = be.getUpdateTag(ImageStreamTestFixtures.lookup).copy();
                if (tag.contains("x")) {
                    tag.putInt("x", at.getX());
                    tag.putInt("z", at.getZ());
                }
                out.add(new Update(be, at, tag));
            }
        return out;
    }

    private static List<Update> indexedWalk(List<Column> columns) {
        var index = new BlockEntityColumns();
        var out = new ArrayList<Update>();
        for (var c : columns)
            for (var be : index.get(c.owner, c.x, c.z)) {
                var at = new BlockPos(c.at.getX(), be.getBlockPos().getY(), c.at.getZ());
                out.add(
                        new Update(
                                be,
                                at,
                                ImageStream.blockEntityTag(
                                        be.getUpdateTag(ImageStreamTestFixtures.lookup), at)));
            }
        return out;
    }

    @Test
    void emptyTablesAreScannedOnlyOncePerOwnerAndProduceNoUpdates() {
        var a = owner(-3, 5, 0);
        var b = owner(-3, 5, 0);
        var columns = columns(a, b, 0);
        assertEquals(oldWalk(columns), indexedWalk(columns));
        assertEquals(129, a.tableReads); // 128 baseline + one index, including empty maps
        assertEquals(129, b.tableReads);
        assertEquals(0, a.entities.visits + b.entities.visits);
    }

    @Test
    void differentialAllRotationsMixedOwnersMultiHeightAndDuplicateMappings() {
        var a = owner(-3, 5, 256);
        var b = owner(7, -9, 73);
        var tags = new IdentityHashMap<BlockEntity, CompoundTag>();
        var states = new IdentityHashMap<BlockEntity, BlockState>();
        for (var owner : List.of(a, b))
            for (var be : owner.entities.values()) {
                tags.put(be, be.getUpdateTag(ImageStreamTestFixtures.lookup).copy());
                states.put(be, be.getBlockState());
            }
        for (int turns = 0; turns < 4; turns++) {
            var columns = columns(a, b, turns);
            // Multiple image columns intentionally map to the same owner column.
            columns.add(new Column(a, 0, 0, new BlockPos(1000, 0, 2000)));
            columns.add(new Column(a, 0, 0, new BlockPos(1001, 0, 2000)));
            var expected = oldWalk(columns);
            var actual = indexedWalk(columns);
            assertEquals(expected, actual, "Rotation " + turns);
            var firstDuplicate = actual.subList(actual.size() - 6, actual.size() - 3);
            var secondDuplicate = actual.subList(actual.size() - 3, actual.size());
            assertEquals(
                    List.of(255, -63, 80), firstDuplicate.stream().map(u -> u.at.getY()).toList());
            for (int i = 0; i < 3; i++) {
                assertSame(firstDuplicate.get(i).entity, secondDuplicate.get(i).entity);
                assertNotSame(firstDuplicate.get(i).tag, secondDuplicate.get(i).tag);
            }
        }
        for (var be : tags.keySet()) {
            assertEquals(tags.get(be), be.getUpdateTag(ImageStreamTestFixtures.lookup));
            assertSame(states.get(be), be.getBlockState());
        }
    }

    @Test
    void indexIsScopedToOneSendAndDistinctOwnerObjectsHaveSeparateBuckets() {
        var a = owner(2, 3, 1);
        var b = owner(2, 3, 1);
        assertNotSame(a, b);
        assertEquals(a, b, "The fixture deliberately gives equal-position owners value equality");
        var index = new BlockEntityColumns();
        assertNotSame(index.get(a, 0, 0).getFirst(), index.get(b, 0, 0).getFirst());
        assertEquals(1, a.tableReads);
        assertEquals(1, b.tableReads);
        var later = new Entity(new BlockPos(32, 40, 48));
        a.entities.put(later.getBlockPos(), later);
        assertEquals(3, index.get(a, 0, 0).size());
        assertEquals(4, new BlockEntityColumns().get(a, 0, 0).size());
    }

    @Test
    void updateTagsAreDeepCopiedAndCoordinateRewriteKeepsOriginalRules() {
        var source = new CompoundTag();
        var nested = new CompoundTag();
        nested.putString("value", "original");
        source.put("payload", nested);
        var at = new BlockPos(100, 200, 300);
        var withoutCoordinates = ImageStream.blockEntityTag(source, at);
        assertFalse(withoutCoordinates.contains("x"));
        withoutCoordinates.getCompound("payload").putString("value", "changed");
        assertEquals("original", source.getCompound("payload").getString("value"));
        source.putInt("x", 1);
        source.putInt("y", -63);
        source.putInt("z", 2);
        var withCoordinates = ImageStream.blockEntityTag(source, at);
        assertEquals(100, withCoordinates.getInt("x"));
        assertEquals(300, withCoordinates.getInt("z"));
        assertEquals(-63, withCoordinates.getInt("y"));
        assertEquals(1, source.getInt("x"));
        assertEquals(2, source.getInt("z"));
    }

    @Test
    void boundedOfflineVisitEvidence() {
        for (int ownerCount : new int[] {1, 2}) {
            var a = owner(-3, 5, 256);
            var b = ownerCount == 1 ? a : owner(7, -9, 256);
            var columns = columns(a, b, 1);
            var expected = oldWalk(columns);
            int oldVisits = a.entities.visits + (a == b ? 0 : b.entities.visits);
            int oldReads = a.tableReads + (a == b ? 0 : b.tableReads);
            a.entities.visits = b.entities.visits = a.tableReads = b.tableReads = 0;
            var actual = indexedWalk(columns);
            int newVisits = a.entities.visits + (a == b ? 0 : b.entities.visits);
            int newReads = a.tableReads + (a == b ? 0 : b.tableReads);
            assertEquals(expected, actual);
            assertEquals(256 * 768, oldVisits);
            assertEquals(ownerCount * 768, newVisits);
            assertEquals(256, oldReads);
            assertEquals(ownerCount, newReads);
            System.out.printf(
                    "EVIDENCE BE: owners=%d, entries/owner=768, columns=256, table reads=%d/%d, entity visits=%d/%d, updates=%d/%d\n",
                    ownerCount,
                    oldReads,
                    newReads,
                    oldVisits,
                    newVisits,
                    expected.size(),
                    actual.size());
        }
    }
}
