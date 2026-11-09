package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.*;
import dev.starfold.chart.GasPlayerExposureState.Participation;
import dev.starfold.geometry.BentStripGeometry.Point;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Optional;

class GasPlayerExposureCacheTest {
    private static final ResourceLocation DIMENSION = ResourceLocation.parse("starfold:system");
    private static final GasPlayerExposureCache.Identity FOCUS =
            new GasPlayerExposureCache.Identity(DIMENSION, 17);

    private static GroundEphemeris.Model model() {
        var bodies = new ArrayList<>(GroundSolar.defaults().bodies());
        bodies.add(GroundEphemeris.Body.gas(
                new GroundEphemeris.Fixed(new Point(40000, 0, 0)), 4096,
                new GasGiant(1, 31, "Amber", 0x98653d, 0xead1a2, 256, 512)));
        return new GroundEphemeris.Model(bodies);
    }

    private static GasPlayerExposurePacket packet(long sequence, int age) {
        var model = model();
        return new GasPlayerExposurePacket(17, DIMENSION, sequence, age,
                Optional.of(new GasPlayerExposurePacket.Snapshot(
                        new GasPlayerExposure.Sample(GasPlayerExposure.Condition.DANGER,
                                Optional.of(new GasPressure.Reading(model.bodies().size() - 1, 512, 1))),
                        new GasPlayerExposureValues(37, 0, 0), Participation.ACTIVE)));
    }

    @Test
    void noShipIsNeededAndGraceIsAlwaysTheUnextrapolatedServerValue() {
        var cache = new GasPlayerExposureCache();
        cache.focus(FOCUS);
        var packet = packet(1, 0);
        assertTrue(cache.receive(packet, model(), 10));
        assertSame(packet, cache.current(10));
        assertSame(packet, cache.current(49));
        assertEquals(37, cache.current(49).snapshot().orElseThrow().values().graceTicks());
        assertNull(cache.current(50));
    }

    @Test
    void ageAndExpiryDoNotLetDuplicateOrReorderedSamplesRefreshOrResurrectState() {
        var cache = new GasPlayerExposureCache();
        cache.focus(FOCUS);
        assertTrue(cache.receive(packet(10, 10), model(), 100));
        assertNotNull(cache.current(129));
        assertFalse(cache.receive(packet(10, 0), model(), 129));
        assertFalse(cache.receive(packet(9, 0), model(), 129));
        assertNull(cache.current(130));
        assertFalse(cache.receive(packet(10, 0), model(), 131));
        assertNull(cache.current(131));
        assertTrue(cache.receive(packet(11, 0), model(), 132));
        assertNotNull(cache.current(171));
        assertNull(cache.current(172));
        assertNull(cache.current(131), "backwards receipt clocks cannot show a sample");
    }

    @Test
    void wrongSessionDimensionAndModelCannotPoisonSequenceOrTtl() {
        var cache = new GasPlayerExposureCache();
        cache.focus(FOCUS);
        var good = packet(1, 0);
        for (var bad : new GasPlayerExposurePacket[] {
                new GasPlayerExposurePacket(18, DIMENSION, 999, 0, good.snapshot()),
                new GasPlayerExposurePacket(17, ResourceLocation.parse("minecraft:overworld"),
                        999, 0, good.snapshot())})
            assertFalse(cache.receive(bad, model(), 1));
        assertFalse(cache.receive(packet(999, 0), GroundSolar.defaults(), 1));
        assertFalse(cache.receive(packet(999, 0), null, 1));
        assertTrue(cache.receive(good, model(), 1));
        assertFalse(GasPlayerExposureCache.compatible(good, GroundSolar.defaults()));
        assertNull(cache.current(41));
    }

    @Test
    void clearAndUnknownAreOrderedAndDoNotFabricateGraceOrGeometry() {
        var cache = new GasPlayerExposureCache();
        cache.focus(FOCUS);
        assertTrue(cache.receive(packet(1, 0), model(), 1));
        var unknown = GasPlayerExposurePacket.sampled(17, DIMENSION, 2,
                GasPlayerExposure.unknown(), new GasPlayerExposureValues(0, 0, 19),
                Participation.EXEMPT);
        assertTrue(cache.receive(unknown, null, 2));
        assertEquals(GasPlayerExposure.Risk.UNKNOWN,
                cache.current(2).snapshot().orElseThrow().sample().risk());
        assertEquals(0, cache.current(2).snapshot().orElseThrow().values().graceTicks());
        assertTrue(cache.receive(GasPlayerExposurePacket.clear(17, DIMENSION, 3), null, 3));
        assertTrue(cache.current(3).snapshot().isEmpty());
        assertFalse(cache.receive(packet(2, 0), model(), 3));
        assertTrue(GasPlayerExposureHud.lines(cache.current(3)).isEmpty());
    }

    @Test
    void dimensionChangeSessionChangeAndDisconnectDiscardPreviousScene() {
        var cache = new GasPlayerExposureCache();
        cache.focus(FOCUS);
        assertTrue(cache.receive(packet(1, 0), model(), 1));
        cache.focus(new GasPlayerExposureCache.Identity(
                ResourceLocation.parse("minecraft:overworld"), 18));
        assertNull(cache.current(2));
        assertFalse(cache.receive(packet(999, 0), model(), 2));
        cache.focus(new GasPlayerExposureCache.Identity(DIMENSION, 19));
        assertNull(cache.current(3));
        assertFalse(cache.receive(packet(999, 0), model(), 3));
        cache.reset();
        assertNull(cache.current(4));
        assertFalse(cache.receive(packet(999, 0), model(), 4));
        cache.focus(FOCUS); // A new connection can establish authority afresh.
        assertTrue(cache.receive(packet(0, 0), model(), 5));
    }

    @Test
    void respawnOrLevelReplacementRetiresOldSessionEvenIfAuthorityWasTemporarilyAbsent() {
        var cache = new GasPlayerExposureCache();
        cache.focus(FOCUS);
        assertTrue(cache.receive(packet(1, 0), model(), 1));
        cache.focus(null);
        cache.invalidate(); // Client LocalPlayer/ClientLevel reference changed.
        cache.focus(FOCUS);
        assertNull(cache.current(2));
        assertFalse(cache.receive(packet(999, 0), model(), 2));
        cache.focus(new GasPlayerExposureCache.Identity(DIMENSION, 18));
        var newLife = new GasPlayerExposurePacket(18, DIMENSION, 0, 0, packet(0, 0).snapshot());
        assertTrue(cache.receive(newLife, model(), 3));
        assertFalse(cache.receive(packet(999, 0), model(), 3));
        assertEquals(37, cache.current(3).snapshot().orElseThrow().values().graceTicks(),
                "a new display session does not reset persisted grace");
    }

    @Test
    void unknownSequenceFenceSurvivesTemporaryLossOfTheSameAuthority() {
        temporaryLossPreservesSequence(GasPlayerExposurePacket.sampled(17, DIMENSION, 10,
                GasPlayerExposure.unknown(), new GasPlayerExposureValues(0, 0, 19),
                Participation.ACTIVE));
    }

    @Test
    void clearSequenceFenceSurvivesTemporaryLossOfTheSameAuthority() {
        temporaryLossPreservesSequence(GasPlayerExposurePacket.clear(17, DIMENSION, 10));
    }

    private static void temporaryLossPreservesSequence(GasPlayerExposurePacket marker) {
        var cache = new GasPlayerExposureCache();
        cache.focus(FOCUS);
        assertTrue(cache.receive(packet(8, 0), model(), 90));
        assertTrue(cache.receive(marker, null, 100));
        cache.focus(null);
        assertNull(cache.current(101), "loss of authority hides the previous sample");
        assertFalse(cache.receive(packet(11, 0), model(), 101));
        cache.focus(FOCUS);
        assertNull(cache.current(102), "restoring authority must not restore its old sample");
        assertFalse(cache.receive(packet(9, 0), model(), 102),
                "old danger must not cross the UNKNOWN/CLEAR sequence fence");
        assertFalse(cache.receive(packet(10, 0), model(), 103));
        assertNull(cache.current(103));
        assertTrue(cache.receive(packet(11, 10), model(), 110));
        assertNotNull(cache.current(139));
        assertFalse(cache.receive(packet(10, 0), model(), 139));
        assertNull(cache.current(140), "rejected old data must not refresh receipt TTL");
        assertFalse(cache.receive(packet(11, 0), model(), 141));
        cache.focus(null);
        cache.focus(FOCUS);
        assertFalse(cache.receive(packet(11, 0), model(), 142),
                "expiry and a second temporary loss must still retain the fence");
        assertTrue(cache.receive(packet(12, 0), model(), 143));
    }

    @Test
    void newSessionAndRealDisconnectStartNewSequencesButTemporaryLossDoesNot() {
        var cache = new GasPlayerExposureCache();
        cache.focus(FOCUS);
        assertTrue(cache.receive(packet(10, 0), model(), 100));
        cache.focus(null);
        cache.focus(new GasPlayerExposureCache.Identity(DIMENSION, 18));
        assertNull(cache.current(101));
        var newSession = new GasPlayerExposurePacket(18, DIMENSION, 0, 0, packet(0, 0).snapshot());
        assertTrue(cache.receive(newSession, model(), 102));
        assertFalse(cache.receive(packet(999, 0), model(), 102));
        cache.reset();
        cache.focus(new GasPlayerExposureCache.Identity(DIMENSION, 18));
        assertNull(cache.current(103));
        assertTrue(cache.receive(newSession, model(), 104),
                "a real disconnect clears the fence even for the same identity");
        cache.focus(null);
        cache.invalidate();
        cache.focus(new GasPlayerExposureCache.Identity(DIMENSION, 18));
        assertFalse(cache.receive(newSession, model(), 105),
                "invalidate must retire the last session across a temporary null");
        cache.reset();
        cache.focus(new GasPlayerExposureCache.Identity(DIMENSION, 18));
        assertTrue(cache.receive(newSession, model(), 106), "disconnect also clears retirement");
    }

    @Test
    void sameSessionDifferentDimensionIsANewIdentityButRetirementStillCoversTheSession() {
        var cache = new GasPlayerExposureCache();
        cache.focus(FOCUS);
        assertTrue(cache.receive(packet(10, 0), model(), 100));
        var dimension = ResourceLocation.parse("minecraft:overworld");
        var other = new GasPlayerExposureCache.Identity(dimension, 17);
        var first = new GasPlayerExposurePacket(17, dimension, 0, 0, packet(0, 0).snapshot());
        cache.focus(null);
        cache.focus(other);
        assertNull(cache.current(101));
        assertFalse(cache.receive(packet(999, 0), model(), 101));
        assertTrue(cache.receive(first, model(), 102));
        cache.focus(null);
        cache.focus(other);
        assertNull(cache.current(103));
        assertFalse(cache.receive(first, model(), 103), "same dimension/session retains its fence");
        cache.focus(FOCUS); // Identity changed; there is no historical per-dimension sequence map.
        assertTrue(cache.receive(packet(0, 0), model(), 104));
        cache.focus(null);
        cache.invalidate();
        cache.focus(other);
        assertFalse(cache.receive(first, model(), 105), "retired session is blocked in every dimension");
        assertNull(cache.current(105));
    }

    @Test
    void maximumSequenceCannotWrapAfterTemporaryLossOfAuthority() {
        var cache = new GasPlayerExposureCache();
        cache.focus(FOCUS);
        assertTrue(cache.receive(packet(Long.MAX_VALUE, 0), model(), 100));
        cache.focus(null);
        cache.focus(FOCUS);
        assertNull(cache.current(101));
        assertFalse(cache.receive(packet(0, 0), model(), 101));
        assertFalse(cache.receive(packet(Long.MAX_VALUE, 0), model(), 102));
        assertNull(cache.current(102));
    }

    @Test
    void invalidReadingsAreRejectedBeforeSequenceMutation() {
        var cache = new GasPlayerExposureCache();
        cache.focus(FOCUS);
        int gas = model().bodies().size() - 1;
        for (var reading : new GasPressure.Reading[] {
                new GasPressure.Reading(0, 512, 1),
                new GasPressure.Reading(gas, 4097, 1),
                new GasPressure.Reading(gas, 512, .2)}) {
            var bad = GasPlayerExposurePacket.sampled(17, DIMENSION, 999,
                    new GasPlayerExposure.Sample(GasPlayerExposure.Condition.DANGER,
                            Optional.of(reading)), GasPlayerExposureValues.initial(), Participation.ACTIVE);
            assertFalse(cache.receive(bad, model(), 1));
        }
        var falseSafe = GasPlayerExposurePacket.sampled(17, DIMENSION, 999,
                new GasPlayerExposure.Sample(GasPlayerExposure.Condition.SAFE,
                        Optional.of(new GasPressure.Reading(gas, 512, 1))),
                GasPlayerExposureValues.initial(), Participation.ACTIVE);
        assertFalse(cache.receive(falseSafe, model(), 1));
        assertTrue(cache.receive(packet(1, 0), model(), 1));
    }
}
