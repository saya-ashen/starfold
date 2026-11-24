package dev.starfold.compat.create;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;

import org.junit.jupiter.api.Test;

class CreateBlockEntityImagesTest {
    @Test
    void projectsTankControllerAndLastKnownPosition() {
        var controller = new BlockPos(18431, 174, 1070);
        var member = controller.above();
        var image = new BlockPos(5166, 174, 2048);
        for (String type : new String[] {"fluid_tank", "creative_fluid_tank"}) {
            var tag = new CompoundTag();
            tag.put("Controller", NbtUtils.writeBlockPos(controller));
            tag.put("LastKnownPos", NbtUtils.writeBlockPos(member));
            CreateBlockEntityImages.relabelPositions(tag, type, pos -> pos.equals(controller) ? image : image.above());
            assertEquals(image, NbtUtils.readBlockPos(tag, "Controller").orElseThrow());
            assertEquals(image.above(), NbtUtils.readBlockPos(tag, "LastKnownPos").orElseThrow());
        }
    }

    @Test
    void projectsShaftRpmOntoThePositiveDisplayAxis() {
        var tag = new CompoundTag();
        tag.putFloat("Speed", 16);
        CreateBlockEntityImages.rotateShaftSpeed(tag, Direction.Axis.X, -1);
        assertEquals(-16f, tag.getFloat("Speed"));
        CreateBlockEntityImages.rotateShaftSpeed(tag, Direction.Axis.Z, 1);
        assertEquals(16f, tag.getFloat("Speed"));
        CreateBlockEntityImages.rotateShaftSpeed(tag, Direction.Axis.X, 1);
        assertEquals(16f, tag.getFloat("Speed"));
        CreateBlockEntityImages.rotateShaftSpeed(tag, Direction.Axis.Y, 2);
        assertEquals(16f, tag.getFloat("Speed"));
    }
}
