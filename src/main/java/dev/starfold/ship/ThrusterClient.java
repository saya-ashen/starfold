package dev.starfold.ship;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import org.joml.Vector3d;

/** Sparse ballistic spray beyond the drawn plume, in Level coordinates, never in Sable's remote storage plot. */
public final class ThrusterClient {
    public static boolean inset(BlockState state) {
        return state.getBlock() instanceof Thrusters.ThrusterBlock;
    }

    private record Boost(long tick, boolean on) {}

    private static final java.util.Map<ClientSubLevel, Boost> BOOSTS =
            new java.util.WeakHashMap<>();

    static boolean boosted(Level level, BlockPos pos) {
        if (!(Sable.HELPER.getContaining(level, pos) instanceof ClientSubLevel body)) return false;
        var cached = BOOSTS.get(body);
        long tick = level.getGameTime();
        if (cached != null && cached.tick() == tick) return cached.on();
        boolean on = false;
        for (var actor : body.getPlot().getBlockEntityActors())
            if (actor instanceof Thrusters.Booster booster
                    && (booster.getBlockState().getValue(BlockStateProperties.POWERED)
                            || HelmInputClient.sprint(body))) {
                on = true;
                break;
            }
        BOOSTS.put(body, new Boost(tick, on));
        return on;
    }

    public static void tick(Level level, BlockPos pos, BlockState state) {
        int power = HelmInputClient.power(level, pos, state);
        if (power == 0) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!(Sable.HELPER.getContaining(level, pos) instanceof ClientSubLevel body)) return;
        var facing = state.getValue(BlockStateProperties.FACING);
        var normal = facing.getNormal();
        var nozzle =
                new Vector3d(
                        pos.getX() + .5 + normal.getX() * .55,
                        pos.getY() + .5 + normal.getY() * .55,
                        pos.getZ() + .5 + normal.getZ() * .55);
        var pose = body.logicalPose();
        var at = pose.transformPosition(new Vector3d(nozzle));
        if (at.distanceSquared(mc.player.getX(), mc.player.getY(), mc.player.getZ()) > 160 * 160)
            return;
        boolean boosted = boosted(level, pos);
        // Measured point displacement includes rotation and is in this Level's frame: zero for a
        // stationary region lead.
        var inherited =
                new Vector3d(at).sub(body.lastPose().transformPosition(new Vector3d(nozzle)));
        var direction =
                pose.orientation()
                        .transform(new Vector3d(normal.getX(), normal.getY(), normal.getZ()));
        double speed = (.15 + .15 * power / 15.0) * (boosted ? 1.2 : 1);
        int oldCount = 1 + power / 5 + (boosted ? 2 : 0),
                count = oldCount / 4 + (level.random.nextInt(4) < oldCount % 4 ? 1 : 0);
        at.add(new Vector3d(direction).mul((boosted ? 6 : 2.5) * power / 15.0));
        for (int i = 0; i < count; i++) {
            var offset =
                    new Vector3d(
                            (level.random.nextDouble() - .5) * .24,
                            (level.random.nextDouble() - .5) * .24,
                            (level.random.nextDouble() - .5) * .24);
            offset.sub(new Vector3d(direction).mul(offset.dot(direction)));
            var start = new Vector3d(at).add(offset);
            var velocity =
                    new Vector3d(direction)
                            .mul(speed * (.85 + level.random.nextDouble() * .3))
                            .add(inherited);
            var particle =
                    mc.particleEngine.createParticle(
                            i == 0 ? ParticleTypes.END_ROD : ParticleTypes.SOUL_FIRE_FLAME,
                            start.x,
                            start.y,
                            start.z,
                            0,
                            0,
                            0);
            if (particle != null) {
                particle.setParticleSpeed(velocity.x, velocity.y, velocity.z);
                particle.setLifetime(boosted ? 4 : 3);
                particle.scale(i == 0 ? .45f : boosted ? .65f : .5f);
                ((dev.starfold.mixin.ExhaustParticleAccess) particle).starfold$friction(1);
            }
        }
    }

    private ThrusterClient() {}
}
