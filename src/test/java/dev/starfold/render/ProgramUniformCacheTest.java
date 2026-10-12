package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

class ProgramUniformCacheTest {
    @Test
    void sameShaderObjectWithNewGlProgramMustResolveAgain() {
        var cache = new ProgramUniformCache<Object>();
        var shader = new Object();
        var calls = new AtomicInteger();
        assertEquals(1, cache.location(shader, 10, "CPOrbitAnchor", calls::incrementAndGet));
        assertEquals(1, cache.location(shader, 10, "CPOrbitAnchor", calls::incrementAndGet));
        assertEquals(2, cache.location(shader, 20, "CPOrbitAnchor", calls::incrementAndGet));
        assertEquals(2, calls.get());
    }

    @Test
    void relinkWithSameNumericHandleInvalidatesMissingAndPresentLocations() {
        var cache = new ProgramUniformCache<Object>();
        var shader = new Object();
        assertEquals(-1, cache.location(shader, 10, "CPCapActive", () -> -1));
        assertEquals(-1, cache.location(shader, 10, "CPCapActive", () -> 5));
        cache.invalidate(shader);
        assertEquals(5, cache.location(shader, 10, "CPCapActive", () -> 5));
    }

    @Test
    void wrappersAndUniformNamesRemainIndependent() {
        var cache = new ProgramUniformCache<Object>();
        var a = new Object();
        var b = new Object();
        assertEquals(1, cache.location(a, 10, "a", () -> 1));
        assertEquals(2, cache.location(a, 10, "b", () -> 2));
        assertEquals(3, cache.location(b, 10, "a", () -> 3));
        cache.invalidate(a);
        assertEquals(3, cache.location(b, 10, "a", () -> 99));
    }
}
