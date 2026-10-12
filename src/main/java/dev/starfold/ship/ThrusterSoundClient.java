package dev.starfold.ship;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.starfold.ground.GroundServer;
import dev.starfold.space.Space;
import dev.starfold.space.SpaceClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import org.joml.Vector3d;

import java.util.IdentityHashMap;

/** One positional loop per loaded hull. Never sends a sound packet, and never plays vacuum sound to outsiders. */
public final class ThrusterSoundClient {
    private static final SoundEvent THRUSTER =
            SoundEvent.createVariableRangeEvent(ResourceLocation.parse("starfold:thruster"));
    private static final IdentityHashMap<ClientSubLevel, Engine> engines = new IdentityHashMap<>();
    private static Object level;

    public static void initialize() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientTickEvent.Post event) -> tick());
    }

    private static void tick() {
        var mc = Minecraft.getInstance();
        if (mc.isPaused()) return;
        if (level != mc.level || mc.player == null) {
            for (var engine : engines.values()) {
                engine.finish("level");
                mc.getSoundManager().stop(engine);
            }
            engines.clear();
            level = mc.level;
        }
        if (mc.level == null || mc.player == null) return;
        engines.entrySet()
                .removeIf(
                        e -> {
                            var engine = e.getValue();
                            if (!mc.getSoundManager().isActive(engine)
                                    && mc.level.getGameTime() - engine.lastTick > 20)
                                engine.finish("inactive");
                            return engine.isStopped();
                        });
        for (var engine : engines.values()) {
            engine.target = 0;
            engine.reason = "unloaded";
        }
        var bodies = SubLevelContainer.getContainer(mc.level);
        var aboard = Sable.HELPER.getTrackingSubLevel(mc.player);
        if (bodies != null)
            for (var value : bodies.getAllSubLevels())
                if (value instanceof ClientSubLevel body) {
                    var box = body.getPlot().getBoundingBox();
                    var at =
                            body.logicalPose()
                                    .transformPosition(
                                            new Vector3d(
                                                    (box.minX() + box.maxX() + 1) / 2.0,
                                                    (box.minY() + box.maxY() + 1) / 2.0,
                                                    (box.minZ() + box.maxZ() + 1) / 2.0));
                    boolean region = GroundServer.is(mc.level) && Space.holds(at.x, at.z),
                            allowed = (!region && !SpaceClient.present()) || aboard == body;
                    double power = 0;
                    int thrusters = 0;
                    boolean boost = false;
                    // Crew hear their own hull at the ear: a long hull must not fade its engines
                    // with the walk from its middle.
                    if (aboard == body) {
                        var ear = mc.gameRenderer.getMainCamera().getPosition();
                        at.set(ear.x, ear.y, ear.z);
                    }
                    if (allowed
                            && (aboard == body
                                    || at.distanceSquared(
                                                    mc.player.getX(),
                                                    mc.player.getY(),
                                                    mc.player.getZ())
                                            < ThrusterSound.RANGE * ThrusterSound.RANGE))
                        for (var actor : body.getPlot().getBlockEntityActors()) {
                            if (actor instanceof Thrusters.Thruster thruster
                                    && !thruster.isRemoved()) {
                                thrusters++;
                                power +=
                                        HelmInputClient.power(
                                                mc.level,
                                                thruster.getBlockPos(),
                                                thruster.getBlockState());
                            }
                            if (actor instanceof Thrusters.Booster booster
                                    && !booster.isRemoved()
                                    && (booster.getBlockState()
                                                    .getValue(BlockStateProperties.POWERED)
                                            || HelmInputClient.sprint(body))) boost = true;
                        }
                    // A helmed hull idles even while it coasts; everything else is silent until it
                    // burns.
                    double target =
                            allowed
                                    ? ThrusterSound.target(
                                            power, thrusters, boost, HelmClient.body() == body)
                                    : 0;
                    var engine = engines.get(body);
                    if (engine == null && target > 0) {
                        engine = new Engine(body);
                        engines.put(body, engine);
                        engine.position(at);
                        engine.power = power;
                        engine.boost = boost;
                        engine.target = target;
                        mc.getSoundManager().play(engine);
                        probe("START", engine);
                    }
                    if (engine != null) {
                        engine.position(at);
                        engine.target = target;
                        engine.reason = allowed ? "idle" : "outside";
                        engine.power = power;
                        engine.boost = boost;
                    }
                }
    }

    private static void probe(String action, Engine engine) {
        if (Boolean.getBoolean("starfold.carryProbe")) engine.probe(action);
        else if (!action.equals("TICK")) engine.note(action);
    }

    private static final class Engine extends AbstractTickableSoundInstance {
        private final ClientSubLevel body;
        private double target, spool, power;
        private boolean boost;
        private String reason = "idle";
        private int ticks;
        private long lastTick;

        void probe(String action) {
            org.slf4j.LoggerFactory.getLogger("starfold-probe")
                    .info(
                            "PROBE ENGINE {} hull {} instance {} power {} boost {} volume {} pitch {} target {} loops {} reason {} pos {} {} {}",
                            action,
                            body.getUniqueId(),
                            System.identityHashCode(this),
                            power,
                            boost,
                            volume,
                            pitch,
                            target,
                            engines.values().stream().filter(e -> !e.isStopped()).count(),
                            reason,
                            x,
                            y,
                            z);
        }

        Engine(ClientSubLevel body) {
            super(THRUSTER, SoundSource.BLOCKS, RandomSource.create());
            this.body = body;
            lastTick = Minecraft.getInstance().level.getGameTime();
            looping = true;
            delay = 0;
            volume = 0;
            pitch = (float) ThrusterSound.pitch(0);
        }

        void note(String action) {
            org.slf4j.LoggerFactory.getLogger("starfold-engine")
                    .debug(
                            "{} power {} boost {} volume {} target {} reason {} active {}",
                            action,
                            power,
                            boost,
                            volume,
                            target,
                            reason,
                            Minecraft.getInstance().getSoundManager().isActive(this));
        }

        void position(Vector3d at) {
            x = at.x;
            y = at.y;
            z = at.z;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            var mc = Minecraft.getInstance();
            if (isStopped()) return;
            if (mc.level == null) {
                finish("level");
                return;
            }
            lastTick = mc.level.getGameTime();
            float before = volume;
            spool = ThrusterSound.spool(spool, target);
            volume = (float) ThrusterSound.volume(spool);
            pitch = (float) ThrusterSound.pitch(spool);
            if (++ticks % 5 == 0 || volume != before) ThrusterSoundClient.probe("TICK", this);
            if (target == 0 && spool == 0) finish(reason);
        }

        void finish(String why) {
            if (!isStopped()) {
                reason = why;
                stop();
                ThrusterSoundClient.probe("STOP", this);
            }
        }
    }

    public static String probeReading() {
        return " | engines " + engines.values().stream().filter(e -> !e.isStopped()).count();
    }

    private ThrusterSoundClient() {}
}
