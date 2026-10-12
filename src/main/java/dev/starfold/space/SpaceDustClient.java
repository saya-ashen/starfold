package dev.starfold.space;

import dev.ryanhcode.sable.Sable;
import dev.starfold.ship.HelmClient;
import dev.starfold.ship.HelmInputClient;
import dev.starfold.ship.HullLanding;

import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.particle.Particle;

import org.joml.Vector3d;

import java.util.ArrayList;

/** The moving universe in a camera-local shell, not hull exhaust or a HUD layer. */
public final class SpaceDustClient {
    static final double DUST_MIN = SpaceDust.MIN_SPEED,
            CRUISE_BOOST = SpaceDust.BOOST_SPEED,
            AHEAD_TICKS = 1.5,
            DEPTH_TICKS = 1,
            CONE = .45;
    static final int DUST_MAX_PER_TICK = (int) SpaceDust.MAX_RATE, LIFETIME = 4;
    private static final ArrayList<Particle> motes = new ArrayList<>();
    private static Object level, body;
    private static double budget, speed;
    private static dev.ryanhcode.sable.companion.math.BoundingBox3d hull;

    static boolean inside(double x, double y, double z) {
        return hull != null
                && x >= hull.minX()
                && x <= hull.maxX()
                && y >= hull.minY()
                && y <= hull.maxY()
                && z >= hull.minZ()
                && z <= hull.maxZ();
    }

    public static void initialize() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientTickEvent.Post event) -> tick());
    }

    static double density(double speed) {
        return SpaceDust.rate(speed);
    }

    private static void clear() {
        for (var mote : motes) mote.remove();
        motes.clear();
        budget = 0;
        hull = null;
    }

    private static void tick() {
        var mc = Minecraft.getInstance();
        if (mc.isPaused()) return;
        speed = 0;
        var aboard = mc.player == null ? null : Sable.HELPER.getTrackingSubLevel(mc.player);
        if (level != mc.level || body != aboard) {
            clear();
            level = mc.level;
            body = aboard;
        }
        if (mc.level == null
                || aboard == null
                || HelmClient.body() == null
                || !SpaceClient.active()
                || mc.options.particles().get() == ParticleStatus.MINIMAL) {
            clear();
            return;
        }
        var frame = SpaceClient.frame();
        var drift = frame.levelVector(frame.drift());
        speed = drift.length() * 20;
        if (speed < DUST_MIN) {
            clear();
            return;
        }
        motes.removeIf(p -> !p.isAlive());
        var flight = HelmInputClient.state(HelmClient.body().getUniqueId());
        budget +=
                SpaceDust.rate(speed, flight == null ? SpaceDust.BOOST_SPEED : flight.boostCap())
                        * (mc.options.particles().get() == ParticleStatus.DECREASED ? .5 : 1);
        var velocity = new Vector3d(drift.x(), drift.y(), drift.z());
        var forward = new Vector3d(velocity).normalize();
        var right =
                forward.cross(
                                Math.abs(forward.y) > .9
                                        ? new Vector3d(1, 0, 0)
                                        : new Vector3d(0, 1, 0),
                                new Vector3d())
                        .normalize();
        var up = right.cross(forward, new Vector3d());
        var eye = mc.gameRenderer.getMainCamera().getPosition();
        hull = HullLanding.bounds(aboard);
        // Keep the speed-scaled shell and density, but reject near-eye and in-hull flakes.
        while (budget >= 1) {
            budget--;
            double
                    ahead =
                            6.1
                                    + velocity.length()
                                            * (AHEAD_TICKS
                                                    + mc.level.random.nextDouble() * DEPTH_TICKS),
                    angle = mc.level.random.nextDouble() * Math.PI * 2,
                    radius = ahead * CONE * Math.sqrt(.5 + .5 * mc.level.random.nextDouble());
            var at =
                    new Vector3d(eye.x, eye.y, eye.z)
                            .fma(ahead, forward)
                            .fma(radius * Math.cos(angle), right)
                            .fma(radius * Math.sin(angle), up);
            if (at.distanceSquared(eye.x, eye.y, eye.z) < 36 || inside(at.x, at.y, at.z)) continue;
            var mote =
                    mc.particleEngine.createParticle(
                            SpaceDust.TYPE.get(), at.x, at.y, at.z, 0, 0, 0);
            if (mote != null) {
                mote.setParticleSpeed(-velocity.x, -velocity.y, -velocity.z);
                motes.add(mote);
            }
        }
    }

    public static String probeReading() {
        return " | dust " + motes.stream().filter(Particle::isAlive).count() + " speed " + speed;
    }

    private SpaceDustClient() {}
}
