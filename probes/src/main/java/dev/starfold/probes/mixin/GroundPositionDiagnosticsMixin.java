package dev.starfold.probes.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.starfold.dev.DevPositionDiagnostics;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Observe the actual field writes, not every setPos/absMoveTo delegation or every entity tick. */
@Mixin(Entity.class)
public abstract class GroundPositionDiagnosticsMixin {
    @WrapOperation(method = "setPosRaw(DDD)V", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/entity/Entity;position:Lnet/minecraft/world/phys/Vec3;",
            opcode = Opcodes.PUTFIELD))
    private void starfold$diagnosticPosition(Entity entity, Vec3 value, Operation<Void> original) {
        var scope = DevPositionDiagnostics.begin(entity, "position-write", () -> "destination=" + value,
                DevPositionDiagnostics.jump(entity, value));
        try {
            original.call(entity, value);
        } finally {
            DevPositionDiagnostics.end(scope);
        }
    }

    @WrapOperation(method = "setLevel", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/entity/Entity;level:Lnet/minecraft/world/level/Level;",
            opcode = Opcodes.PUTFIELD))
    private void starfold$diagnosticDimension(Entity entity, Level value, Operation<Void> original) {
        var scope = DevPositionDiagnostics.begin(entity, "dimension-write",
                () -> "destination=" + value.dimension().location(), true);
        try {
            original.call(entity, value);
        } finally {
            DevPositionDiagnostics.end(scope);
        }
    }
}
