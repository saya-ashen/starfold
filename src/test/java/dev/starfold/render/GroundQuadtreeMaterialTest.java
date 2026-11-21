package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The compact palette must retain BufferBuilder's signed-normal albedo convention. */
class GroundQuadtreeMaterialTest {
    @Test
    void compactAlbedoNormalMatchesSignedNormalizedBufferBuilderBytes() {
        for (int channel = 0; channel <= 255; channel++) {
            int packed = GroundRenderer.packedAlbedoNormal(channel * 0x010101);
            int expected = (byte) ((channel / 255f * 2 - 1) * 127f);
            for (int shift : new int[] {0, 8, 16})
                assertEquals(expected, (byte) (packed >>> shift));
        }
        assertEquals(0x818181, GroundRenderer.packedAlbedoNormal(0));
        assertEquals(0x7f7f7f, GroundRenderer.packedAlbedoNormal(0xffffff));
        assertEquals(0x81007f, GroundRenderer.packedAlbedoNormal(0xff8000));
    }
}
