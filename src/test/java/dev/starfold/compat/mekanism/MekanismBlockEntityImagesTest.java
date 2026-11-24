package dev.starfold.compat.mekanism;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;

class MekanismBlockEntityImagesTest {
    @Test
    void rotatesConnectionBitsAndPerFaceModesTogether() {
        var tag = new CompoundTag();
        tag.putByte("connections", (byte) ((1 << Direction.NORTH.ordinal()) | 1));
        tag.putByte("acceptors", (byte) (1 << Direction.WEST.ordinal()));
        tag.putIntArray("connection", new int[] {0, 1, 2, 3, 4, 5});
        tag.putInt("energy", 320);
        var original = tag.copy();
        MekanismBlockEntityImages.rotateTransmitter(tag, 1);
        assertEquals((1 << Direction.EAST.ordinal()) | 1, tag.getByte("connections"));
        assertEquals(1 << Direction.NORTH.ordinal(), tag.getByte("acceptors"));
        assertArrayEquals(new int[] {0, 1, 4, 5, 3, 2}, tag.getIntArray("connection"));
        assertEquals(320, tag.getInt("energy"));
        MekanismBlockEntityImages.rotateTransmitter(tag, -1);
        assertEquals(original, tag);
    }
}
