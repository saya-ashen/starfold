package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import dev.starfold.ground.GroundServer;
import dev.starfold.planet.PlanetAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/** Create's shafts carry rotation across a face seam. The neighbour found past a seam is the block entity of the face
 *  it belongs to, whose own position and directions are that face's: Create, which reads the way from one to the other
 *  off their positions, is told it as each of the two sees it. Only a shaft carried straight over is joined; cogs
 *  meshing across a seam, and a gearbox reading its source's side there, are not. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.RotationPropagator", remap = false)
// Named apart from the Ground mixins: those are audited against the game jar, which holds no
// Create.
public abstract class CreateSeamRotationMixin {
    @Shadow
    private static float getAxisModifier(KineticBlockEntity be, Direction direction) {
        throw new AssertionError();
    }

    @WrapMethod(method = "getRotationSpeedModifier")
    private static float starfold$acrossSeam(
            KineticBlockEntity from, KineticBlockEntity to, Operation<Float> original) {
        // Read through the vanilla type: Create's own supertypes need not be there to compile
        // against.
        BlockEntity a = (BlockEntity) (Object) from, b = (BlockEntity) (Object) to;
        var level = a.getLevel();
        if (level == null
                || !GroundServer.canonical(level)
                || a.getBlockPos().distManhattan(b.getBlockPos()) <= 3)
            return original.call(from, to);
        Direction out = starfold$toward(level, a.getBlockPos(), b.getBlockPos()),
                back = starfold$toward(level, b.getBlockPos(), a.getBlockPos());
        if (out == null || back == null) return original.call(from, to);
        if (!(a.getBlockState().getBlock() instanceof IRotate here)
                || !(b.getBlockState().getBlock() instanceof IRotate there)
                || !here.hasShaftTowards(level, a.getBlockPos(), a.getBlockState(), out)
                || !there.hasShaftTowards(level, b.getBlockPos(), b.getBlockState(), back))
            return 0;
        float over = getAxisModifier(to, back);
        if (over != 0) over = 1 / over;
        // Each face counts along its own axes: where the two count the shared shaft in opposite
        // senses, the speed changes sign with it.
        return getAxisModifier(from, out)
                * over
                * (out.getAxisDirection() == back.getAxisDirection() ? -1 : 1);
    }

    private static Direction starfold$toward(Level level, BlockPos from, BlockPos to) {
        var access = PlanetAccess.of(level);
        for (var d : Direction.values()) {
            var p = from.relative(d);
            if (access.inChart(p) && access.address(p).owner().equals(to)) return d;
        }
        return null;
    }
}
