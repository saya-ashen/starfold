package dev.starfold.ground;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;

import net.minecraft.world.level.chunk.DataLayer;

import org.junit.jupiter.api.Test;

import java.util.*;

class ImageLightSectionTest {
    @Test
    void darkSectionExplicitlyReplacesPreviouslyLitClientData() {
        var values = new BitSet();
        var empty = new BitSet();
        var updates = new ArrayList<byte[]>();
        var previous = new DataLayer();
        previous.set(2, 8, 0, 14);
        ImageStream.lightSection(previous, true, 12, values, empty, updates);
        assertTrue(values.get(12));
        assertFalse(empty.get(12));
        assertEquals(14, new DataLayer(updates.getFirst()).get(2, 8, 0));

        values.clear();
        empty.clear();
        updates.clear();
        ImageStream.lightSection(new DataLayer(), false, 12, values, empty, updates);
        assertFalse(values.get(12));
        assertTrue(empty.get(12), "Omitting this bit leaves the previous 14 on the client");
        assertTrue(updates.isEmpty());
    }

    @Test
    void skyFallbackValuesAreSentEvenWithoutAnOwnerDataLayer() {
        var sky = new DataLayer();
        sky.fill(15);
        var values = new BitSet();
        var empty = new BitSet();
        var updates = new ArrayList<byte[]>();
        ImageStream.lightSection(sky, true, 20, values, empty, updates);
        assertTrue(values.get(20));
        assertFalse(empty.get(20));
        assertEquals(15, new DataLayer(updates.getFirst()).get(15, 15, 15));
    }

    @Test
    void samplerAlwaysPublishesASectionIncludingNullableSourceFallback() throws Exception {
        var source = Sources.text("ImageStream");
        assertFalse(source.contains("if (!present) continue"));
        assertTrue(source.contains("listener.getLightValue("));
        assertTrue(
                source.contains(
                        "lightSection(out, lit, s, masks[layer], masks[2 + layer], updates.get(layer))"));
    }
}
