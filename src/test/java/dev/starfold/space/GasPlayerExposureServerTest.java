package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.GasPlayerExposureState;
import dev.starfold.chart.GasPlayerExposureValues;

import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

class GasPlayerExposureServerTest {
    private static final UUID UUID_VALUE = new UUID(0, 17);

    /** Like respawned entities, different objects may compare equal. */
    private static final class Player {
        final CompoundTag persistent = new CompoundTag();

        Player(GasPlayerExposureValues values) {
            GasPlayerExposureSave.write(persistent, values);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Player;
        }

        @Override
        public int hashCode() {
            return 17;
        }
    }

    private static final class Fixture {
        final Map<UUID, GasPlayerExposureServer.PlayerState<Player>> states = new HashMap<>();
        int restores, writes;

        void clone(Player from, Player to, boolean death) {
            // The same operation (including map commit and persistence callback) as PlayerEvent.Clone.
            GasPlayerExposureServer.transferClone(states, UUID_VALUE, from, to, death, () -> {
                restores++;
                return GasPlayerExposureState.restored(GasPlayerExposureSave.read(from.persistent));
            }, values -> {
                writes++;
                GasPlayerExposureSave.write(to.persistent, values);
            });
        }

        GasPlayerExposureServer.PlayerState<Player> current() {
            return states.get(UUID_VALUE);
        }

        void install(Player owner, GasPlayerExposureState state) {
            states.put(UUID_VALUE, new GasPlayerExposureServer.PlayerState<>(owner, state));
        }
    }

    private static GasPlayerExposureValues values(int grace, int safe, int attempt) {
        return new GasPlayerExposureValues(grace, safe, attempt);
    }

    @Test
    void multigenerationReplayCannotRollbackCurrentOwnerStateOrAnyTargetNbt() {
        var a = new Player(GasPlayerExposureValues.initial());
        var b = new Player(values(50, 9, 0));
        var c = new Player(values(40, 8, 0));
        var fixture = new Fixture();
        fixture.install(a, new GasPlayerExposureState(values(0, 10, 7), 101));
        fixture.clone(a, b, false);
        fixture.clone(b, c, false);
        assertSame(c, fixture.current().owner);
        var current = fixture.current();
        current.exposure = new GasPlayerExposureState(values(0, 17, 6), 303);
        current.session = 88;
        current.sequence = 99;
        var bNbt = b.persistent.copy();
        var cNbt = c.persistent.copy();
        var exposure = current.exposure;
        fixture.clone(a, b, true); // Old generation, NOT a missing-state restore.
        assertSame(current, fixture.current());
        assertSame(c, fixture.current().owner);
        assertSame(exposure, fixture.current().exposure);
        assertEquals(303, fixture.current().exposure.lastTick());
        fixture.clone(b, c, true); // Delayed duplicate after the rejected replay.
        assertSame(current, fixture.current());
        assertSame(exposure, fixture.current().exposure);
        assertEquals(88, fixture.current().session);
        assertEquals(99, fixture.current().sequence);
        assertEquals(bNbt, b.persistent);
        assertEquals(cNbt, c.persistent);
        assertEquals(0, fixture.restores);
        assertEquals(2, fixture.writes, "only the two legitimate transfers write their targets");
    }

    @Test
    void conflictingOwnerEvenIfEqualRejectsBeforeRestoreOrTargetWrite() {
        var a = new Player(GasPlayerExposureValues.initial());
        var b = new Player(values(50, 9, 0));
        var c = new Player(values(40, 8, 0));
        assertEquals(a, c);
        assertNotSame(a, c);
        var fixture = new Fixture();
        fixture.install(c, new GasPlayerExposureState(values(0, 99, 20), 456));
        var current = fixture.current();
        var bNbt = b.persistent.copy();
        GasPlayerExposureServer.transferClone(fixture.states, UUID_VALUE, a, b, true,
                () -> { fail("stale source NBT must never be read"); return null; },
                ignored -> fail("rejected target NBT must never be written"));
        assertSame(current, fixture.current());
        assertSame(c, fixture.current().owner);
        assertEquals(new GasPlayerExposureState(values(0, 99, 20), 456), current.exposure);
        assertEquals(bNbt, b.persistent);
    }

    @Test
    void legitimateCurrentOwnerTransferPreservesCompleteStateAndOnlyResetsTransport() {
        var a = new Player(GasPlayerExposureValues.initial());
        var b = new Player(GasPlayerExposureValues.initial());
        assertEquals(a, b, "Entity.equals is not ownership");
        var fixture = new Fixture();
        var state = new GasPlayerExposureState(values(0, 99, 7), 42);
        fixture.install(a, state);
        fixture.current().session = 5;
        fixture.current().sequence = 10;
        fixture.clone(a, b, false);
        assertSame(b, fixture.current().owner);
        assertSame(state, fixture.current().exposure);
        assertEquals(42, fixture.current().exposure.lastTick());
        assertEquals(0, fixture.current().session);
        assertEquals(-1, fixture.current().sequence);
        assertEquals(state.values(), GasPlayerExposureSave.read(b.persistent));
        assertEquals(0, fixture.restores);
        assertEquals(1, fixture.writes);
    }

    @Test
    void immediateDuplicateDoesNotRestoreRebaseWriteOrClearNewRecovery() {
        var a = new Player(GasPlayerExposureValues.initial());
        var b = new Player(GasPlayerExposureValues.initial());
        var fixture = new Fixture();
        fixture.install(a, new GasPlayerExposureState(values(37, 99, 0), 42));
        fixture.clone(a, b, true);
        var current = fixture.current();
        current.exposure = new GasPlayerExposureState(values(37, 1, 0), 43);
        current.session = 88;
        var nbt = b.persistent.copy();
        fixture.clone(a, b, true);
        assertSame(current, fixture.current());
        assertEquals(new GasPlayerExposureState(values(37, 1, 0), 43), current.exposure);
        assertEquals(88, current.session);
        assertEquals(nbt, b.persistent);
        assertEquals(0, fixture.restores);
        assertEquals(1, fixture.writes);
    }

    @Test
    void deathTransferClearsOnlySafeProgressAndKeepsLastTickAndDangerCadence() {
        var a = new Player(GasPlayerExposureValues.initial());
        var b = new Player(GasPlayerExposureValues.initial());
        var fixture = new Fixture();
        var state = new GasPlayerExposureState(values(0, 99, 7), 42);
        fixture.install(a, state);
        fixture.clone(a, b, true);
        assertSame(b, fixture.current().owner);
        assertEquals(new GasPlayerExposureState(values(0, 0, 7), 42), fixture.current().exposure);
        assertEquals(values(0, 0, 7), GasPlayerExposureSave.read(b.persistent));
        assertEquals(values(0, 99, 7), state.values(), "immutable old state is not rewritten");
        assertEquals(0, fixture.restores);
        assertEquals(1, fixture.writes);
    }

    @Test
    void genuinelyMissingStateRestoresOnceAndCommitsForBothDeathAndNonDeath() {
        for (boolean death : new boolean[] {false, true}) {
            var a = new Player(values(0, 99, 20));
            var b = new Player(GasPlayerExposureValues.initial());
            var sourceNbt = a.persistent.copy();
            var fixture = new Fixture();
            fixture.clone(a, b, death);
            assertSame(b, fixture.current().owner);
            var expected = values(0, death ? 0 : 99, 20);
            assertEquals(new GasPlayerExposureState(expected, -1), fixture.current().exposure);
            assertEquals(expected, GasPlayerExposureSave.read(b.persistent));
            assertEquals(sourceNbt, a.persistent);
            assertEquals(1, fixture.restores);
            assertEquals(1, fixture.writes);
            fixture.clone(a, b, death);
            assertEquals(1, fixture.restores);
            assertEquals(1, fixture.writes);
        }
    }
}
