package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.starfold.api.ChunkWorkDiagnostics;
import dev.starfold.ship.SableTickets;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Ships wholly outside world collision sections need no synchronous FULL column acquisition. */
@Mixin(
        targets = "dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager",
        remap = false)
public abstract class SablePhysicsChunkTicketsMixin {
    @WrapOperation(
            method =
                    "update(Lnet/minecraft/server/level/ServerLevel;Ldev/ryanhcode/sable/api/sublevel/ServerSubLevelContainer;Ldev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem;Ldev/ryanhcode/sable/api/physics/PhysicsPipeline;D)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Ldev/ryanhcode/sable/companion/math/BoundingBox3d;chunkBoundsFrom()Ldev/ryanhcode/sable/companion/math/BoundingBox3i;"),
            remap = false)
    private BoundingBox3i starfold$validCollisionSections(
            BoundingBox3d box, Operation<BoundingBox3i> original, ServerLevel level) {
        // The upstream box already includes its one-block margin and vertical prediction.
        return SableTickets.collisionBounds(
                original.call(box), level.getMinSection(), level.getSectionsCount());
    }

    @WrapOperation(
            method = "inhabitChunk",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;"),
            remap = false)
    private ChunkAccess starfold$measureFullRequest(
            ServerLevel level, int x, int z, ChunkStatus status, boolean load,
            Operation<ChunkAccess> original) {
        var trace = ChunkWorkDiagnostics.listener();
        if (trace == null) return original.call(level, x, z, status, load);
        long start = System.nanoTime();
        try {
            return original.call(level, x, z, status, load);
        } finally {
            trace.record(ChunkWorkDiagnostics.Stage.SABLE_COLLISION_CHUNK,
                    ChunkPos.asLong(x, z), start, System.nanoTime() - start);
        }
    }

    @WrapOperation(
            method = "addTicket",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;"),
            remap = false)
    private LevelChunk starfold$measureSectionSource(Level level, int x, int z, Operation<LevelChunk> original) {
        var trace = ChunkWorkDiagnostics.listener();
        if (trace == null) return original.call(level, x, z);
        long start = System.nanoTime();
        try {
            return original.call(level, x, z);
        } finally {
            trace.record(ChunkWorkDiagnostics.Stage.SABLE_COLLISION_CHUNK,
                    ChunkPos.asLong(x, z), start, System.nanoTime() - start);
        }
    }
}
