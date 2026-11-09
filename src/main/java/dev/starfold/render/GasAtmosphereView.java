package dev.starfold.render;

import dev.starfold.chart.GasAppearance;
import dev.starfold.chart.GasGiant;
import dev.starfold.chart.GasOcclusion;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.space.SpaceClient;
import dev.starfold.space.SpaceClientHost;

import net.minecraft.client.Camera;

/** Camera-only gas fog, independent of target lock. Never a hull-pressure or damage authority. */
public final class GasAtmosphereView {
    public record Layer(int bodyIndex, GasGiant policy, double pressure, Point color) {
        public Layer {
            java.util.Objects.requireNonNull(policy);
            GroundEphemeris.point(color);
            if (bodyIndex < 0 || !Double.isFinite(pressure) || pressure < 0 || pressure > 1)
                throw new IllegalArgumentException("Gas fog layer");
        }

        public float fogEnd() {
            return (float) Math.max(8, 256 * Math.pow(1 - pressure, 2));
        }
    }

    public static Layer layer(Camera camera) {
        var world = net.minecraft.client.Minecraft.getInstance().level;
        if (world == null
                || !dev.starfold.planet.PlanetLevels.is(world)
                || !RendererGuard.pipelineSupported()
                || !SpaceClient.active()) return null;
        var entity = camera.getEntity();
        if (entity instanceof net.minecraft.world.entity.LivingEntity living
                && (living.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)
                        || living.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS)))
            return null;
        var model = SpaceClientHost.get().ephemeris();
        double tick = SpaceClient.time();
        var eye = SpaceClient.universeEye(camera.getPosition());
        Layer best = null;
        for (int index : model.catalogIndices()) {
            var body = model.catalogBody(index);
            if (body.gas().isEmpty()) continue;
            var policy = body.gas().orElseThrow();
            double depth = body.radius() - eye.sub(model.frame(index, tick).center()).length();
            double pressure = policy.pressure(depth);
            if (pressure <= 0 || best != null && best.pressure() >= pressure) continue;
            best =
                    new Layer(
                            index,
                            policy,
                            pressure,
                            color(model, index, eye, tick).scale(Math.min(1, pressure * 3)));
        }
        return best;
    }

    static Point color(GroundEphemeris.Model model, int bodyIndex, Point eye, double tick) {
        var body = model.catalogBody(bodyIndex);
        var offset = eye.sub(model.frame(bodyIndex, tick).center());
        var normal = offset.length() < 1e-9 ? GroundEphemeris.Y : offset.scale(1 / offset.length());
        var sun = model.star(eye, tick).position();
        var delta = sun.sub(eye);
        var direction = delta.length() < 1e-9 ? GroundEphemeris.Y : delta.scale(1 / delta.length());
        double light =
                Math.max(0, normal.dot(direction))
                        * GasOcclusion.transmission(
                                GasOcclusion.relative(model, tick, null, bodyIndex), eye, sun);
        return GasAppearance.fogColor(body.gas().orElseThrow(), light);
    }

    private GasAtmosphereView() {}
}
