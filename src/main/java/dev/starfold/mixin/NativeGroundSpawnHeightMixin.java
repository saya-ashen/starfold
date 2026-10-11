package dev.starfold.mixin;
import dev.starfold.NativeGroundDaylight;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A planet's column is some 650 blocks of rock under its ground, and vanilla tries every height alike: the monsters of a
 *  sunless planet all came out in its caves and filled the cap there. Half of its tries are made on the ground. */
@Mixin(NaturalSpawner.class)
public abstract class NativeGroundSpawnHeightMixin {
    @Inject(method="getRandomPosWithin",at=@At("RETURN"),cancellable=true)
    private static void starfold$sunlessGround(Level level,LevelChunk chunk,CallbackInfoReturnable<BlockPos> ci){
        var pos=ci.getReturnValue();if(!level.random.nextBoolean())return;
        var ground=new BlockPos(pos.getX(),chunk.getHeight(Heightmap.Types.WORLD_SURFACE,pos.getX(),pos.getZ())+1,pos.getZ());
        if(NativeGroundDaylight.sunless(level,ground))ci.setReturnValue(ground);
    }
}
