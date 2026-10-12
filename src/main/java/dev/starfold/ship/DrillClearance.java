package dev.starfold.ship;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.drill.DrillMovementBehaviour;
import com.simibubi.create.foundation.utility.BlockHelper;

import dev.ryanhcode.sable.Sable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import org.joml.Vector3d;

/** A drill head carried by a hull is not on the world's grid. Create breaks one cell per drill, the one its point is in;
 *  the rest of what its face covers stays, the head stands on that rim, and the rope then jacks the hull up.
 *  Here a working drill also clears every other world cell just ahead of its whole face. Only on hulls. */
public final class DrillClearance {
    /** Behind Create's own target point (.15 ahead), so a drill's own cell is always claimed before it is seen here. */
    static final double AHEAD = .1, REACH = .52;

    static final int SAMPLES = 9;

    public static void initialize() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.tick.EntityTickEvent.Post event) -> {
                    if (event.getEntity() instanceof AbstractContraptionEntity entity
                            && entity.level() instanceof ServerLevel level) tick(level, entity);
                });
    }

    /** Points over a drill's face, in its own block's frame: the face centre is the origin, a and b span it. */
    static double offset(int index) {
        return -REACH + 2 * REACH * index / (SAMPLES - 1);
    }

    private static void tick(ServerLevel level, AbstractContraptionEntity entity) {
        var body = Sable.HELPER.getContaining(entity);
        var contraption = entity.getContraption();
        if (body == null || contraption == null) return;
        var claimed = new java.util.HashSet<BlockPos>();
        var work = new java.util.ArrayList<MovementContext>();
        for (var actor : contraption.getActors()) {
            var context = actor.right;
            if (context == null
                    || !(com.simibubi.create.api.behaviour.movement.MovementBehaviour.REGISTRY.get(
                                    actor.left.state().getBlock())
                            instanceof DrillMovementBehaviour)) continue;
            if (context.data.contains("BreakingPos"))
                NbtUtils.readBlockPos(context.data, "BreakingPos").ifPresent(claimed::add);
            work.add(context);
        }
        if (work.isEmpty()) return;
        var seen = new java.util.HashSet<BlockPos>();
        for (var context : work) {
            var drill =
                    (DrillMovementBehaviour)
                            com.simibubi.create.api.behaviour.movement.MovementBehaviour.REGISTRY
                                    .get(context.state.getBlock());
            Direction facing = context.state.getValue(BlockStateProperties.FACING);
            var normal = context.rotation.apply(Vec3.atLowerCornerOf(facing.getNormal()));
            // Working means advancing nose first, or held by a cell being broken; a stopped or
            // returning head digs nothing.
            if (!(entity.isStalled()
                    || context.relativeMotion.dot(Vec3.atLowerCornerOf(facing.getNormal())) > 1e-6))
                continue;
            var a =
                    context.rotation.apply(
                            Vec3.atLowerCornerOf(
                                    (facing.getAxis() == Direction.Axis.Y
                                                    ? Direction.EAST
                                                    : Direction.UP)
                                            .getNormal()));
            var b = normal.cross(a);
            var front = Vec3.atCenterOf(context.localPos).add(normal.scale(.5 + AHEAD));
            for (int i = 0; i < SAMPLES; i++)
                for (int j = 0; j < SAMPLES; j++) {
                    var plot =
                            entity.toGlobalVector(
                                    front.add(a.scale(offset(i))).add(b.scale(offset(j))), 1);
                    var world =
                            body.logicalPose()
                                    .transformPosition(new Vector3d(plot.x, plot.y, plot.z));
                    var pos = BlockPos.containing(world.x, world.y, world.z);
                    if (!seen.add(pos)
                            || claimed.contains(pos)
                            || Sable.HELPER.getContaining(level, pos) != null) continue;
                    var state = level.getBlockState(pos);
                    if (state.isAir() || !drill.canBreak(level, pos, state)) continue;
                    BlockHelper.destroyBlock(
                            level, pos, 1, stack -> drill.dropItem(context, stack));
                }
        }
    }

    private DrillClearance() {}
}
