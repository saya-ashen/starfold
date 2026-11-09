package dev.starfold.dev;

import com.mojang.authlib.GameProfile;

import dev.starfold.planet.PlanetSoundClient;
import dev.starfold.planet.PlanetSoundOwner;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.resources.sounds.ElytraOnPlayerSoundInstance;
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.UnderwaterAmbientSoundInstances;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import java.util.IdentityHashMap;
import java.util.UUID;
import java.util.function.Consumer;

/** Isolated client fixtures, never evidence of network multiplayer or acoustic output. */
public final class DevPlanetSoundProbe {
    private static final boolean ENABLED = Boolean.getBoolean("starfold.p3Probe");
    private static Session session;
    private static Object player;
    private static String capturing;
    private static boolean refreshing;

    // No Minecraft types: the bounded identity/lifecycle policy can be tested without a game.
    static final class Session {
        final IdentityHashMap<Object, Entry> entries = new IdentityHashMap<>();
        private final Consumer<String> output;
        private int lines;

        Session(Consumer<String> output) {
            this.output = output;
        }

        boolean add(Object sound, String label) {
            if (entries.containsKey(sound) || entries.size() >= 8) return false;
            entries.put(sound, new Entry(label));
            return true;
        }

        void log(String message) {
            if (lines < 64) {
                lines++;
                output.accept(message);
            }
        }

        void scaled(Object sound, double density, boolean self, float scale) {
            var entry = entries.get(sound);
            if (entry == null) return;
            entry.density = density;
            entry.self = self;
            entry.scale = scale;
            entry.scaled = true;
        }

        void calculated(Object sound, float raw, float result, String phase) {
            var entry = entries.get(sound);
            if (entry == null) return;
            entry.taps++;
            String value =
                    "phase="
                            + phase
                            + " raw="
                            + raw
                            + " density="
                            + entry.density
                            + " self="
                            + entry.self
                            + " scale="
                            + entry.scale
                            + " finalClamp="
                            + result
                            + " productionScaleObserved="
                            + entry.scaled;
            if (!value.equals(entry.last)) {
                log(
                        entry.label
                                + " id="
                                + Integer.toHexString(System.identityHashCode(sound))
                                + " "
                                + value);
                entry.last = value;
            }
            entry.scaled = false;
        }

        void stop(Consumer<Object> stopper) {
            try {
                for (var sound : entries.keySet()) stopper.accept(sound);
            } finally {
                entries.clear();
            }
        }
    }

    static final class Entry {
        final String label;
        double density;
        float scale;
        boolean self, scaled;
        int taps;
        String last;

        Entry(String label) {
            this.label = label;
        }
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static boolean command(Minecraft mc, String arg) {
        if (!ENABLED) throw new IllegalStateException("p3sound requires -Dstarfold.p3Probe=true");
        switch (arg) {
            case "start" -> start(mc);
            case "snapshot" -> {
                requireSession();
                snapshot();
                refreshing = true;
                try {
                    mc.getSoundManager()
                            .updateSourceVolume(
                                    SoundSource.PLAYERS,
                                    mc.options.getSoundSourceVolume(SoundSource.PLAYERS));
                } finally {
                    refreshing = false;
                }
            }
            case "stop" -> stop(mc);
            default -> throw new IllegalArgumentException("p3sound start|snapshot|stop");
        }
        return true;
    }

    private static void requireSession() {
        if (session == null) throw new IllegalStateException("p3sound not started");
    }

    private static void start(Minecraft mc) {
        stop(mc);
        if (mc.player == null || mc.level == null)
            throw new IllegalStateException("p3sound needs world/player");
        player = mc.player;
        session =
                new Session(
                        s ->
                                org.slf4j.LoggerFactory.getLogger("starfold-probe")
                                        .info("PROBE P3SOUND {}", s));
        session.log("START isolated synthetic fixtures; not network/audio evidence");
        try {
            // Capture only the instance actually built by the vanilla local playSound path.
            capturing = "local-playSound";
            try {
                mc.player.playSound(SoundEvents.NOTE_BLOCK_HARP.value(), 4, 1);
            } finally {
                capturing = null;
            }
            if (session.entries.isEmpty())
                session.log("UNVERIFIED local-playSound: no construction hook observed");
            play(
                    mc,
                    new EntityBoundSoundInstance(
                            SoundEvents.NOTE_BLOCK_HARP.value(),
                            SoundSource.PLAYERS,
                            4,
                            1,
                            mc.player,
                            7) {
                        {
                            looping = true;
                        }
                    },
                    "local-bound-loop");
            var remote =
                    new RemotePlayer(
                            mc.level,
                            new GameProfile(
                                    UUID.fromString("00000000-0000-0000-0000-000000000003"),
                                    "P3SoundFixture"));
            remote.setPos(mc.player.getX(), mc.player.getY(), mc.player.getZ());
            play(
                    mc,
                    new EntityBoundSoundInstance(
                            SoundEvents.NOTE_BLOCK_HARP.value(),
                            SoundSource.PLAYERS,
                            4,
                            1,
                            remote,
                            7),
                    "remote-bound-synthetic");
            play(
                    mc,
                    new SimpleSoundInstance(
                            SoundEvents.NOTE_BLOCK_HARP.value(),
                            SoundSource.PLAYERS,
                            4,
                            1,
                            RandomSource.create(7),
                            mc.player.getX(),
                            mc.player.getY(),
                            mc.player.getZ()),
                    "anonymous-same-position");
            // Both real vanilla instance types: non-tickable category refresh and bound tickable.
            play(
                    mc,
                    new SimpleSoundInstance(
                            SoundEvents.NOTE_BLOCK_HARP.value().getLocation(),
                            SoundSource.PLAYERS,
                            4,
                            1,
                            RandomSource.create(7),
                            true,
                            0,
                            SoundInstance.Attenuation.NONE,
                            mc.player.getX(),
                            mc.player.getY(),
                            mc.player.getZ(),
                            false),
                    "anonymous-loop");

            ownerCheck(new ElytraOnPlayerSoundInstance(mc.player), "elytra");
            ownerCheck(
                    new UnderwaterAmbientSoundInstances.UnderwaterAmbientSoundInstance(mc.player),
                    "underwater-loop");
            ownerCheck(
                    new UnderwaterAmbientSoundInstances.SubSound(
                            mc.player, SoundEvents.AMBIENT_UNDERWATER_ENTER) {},
                    "underwater-sub");
            snapshot();
        } catch (RuntimeException | Error error) {
            stop(mc);
            throw error;
        }
    }

    private static void ownerCheck(SoundInstance sound, String label) {
        session.log(
                "OWNER_INTERFACE_ONLY "
                        + label
                        + " applied="
                        + (sound instanceof PlanetSoundOwner)
                        + " self="
                        + PlanetSoundClient.self(sound, player)
                        + " not played");
    }

    private static void play(Minecraft mc, SoundInstance sound, String label) {
        if (!session.add(sound, label)) throw new IllegalStateException("p3sound fixture capacity");
        session.log(
                "FIXTURE "
                        + label
                        + " ownerInterface="
                        + (sound instanceof PlanetSoundOwner)
                        + " self="
                        + PlanetSoundClient.self(sound, player));
        mc.getSoundManager().play(sound);
    }

    /** Called after production provenance assignment, not a replacement for that assignment. */
    public static void created(SoundInstance sound) {
        if (session != null && capturing != null) {
            session.add(sound, capturing);
            session.log(
                    "CAPTURE vanilla LocalPlayer.playSound construction self="
                            + PlanetSoundClient.self(sound, player));
            capturing = null;
        }
    }

    /** Values calculated by production scale(), not an independent diagnostic formula. */
    public static void scaled(SoundInstance sound, double density, boolean self, float scale) {
        if (session == null) return;
        session.scaled(sound, density, self, scale);
    }

    /** The return from the original scalar SoundEngine operation, passed through unchanged. */
    public static void calculated(SoundInstance sound, float raw, float result, String phase) {
        if (session == null) return;
        session.calculated(sound, raw, result, refreshing ? "category-refresh" : phase);
    }

    /** Existing production category refresh supplies context; no sound values are modified. */
    public static void refreshing(boolean value) {
        refreshing = value;
    }

    private static void snapshot() {
        for (var item : session.entries.entrySet()) {
            var e = item.getValue();
            session.log(
                    "SNAPSHOT "
                            + e.label
                            + " id="
                            + Integer.toHexString(System.identityHashCode(item.getKey()))
                            + " taps="
                            + e.taps
                            + (e.taps == 0 ? " UNVERIFIED no engine tap" : ""));
        }
    }

    static void tick(Minecraft mc) {
        if (session != null && (mc.player != player || mc.level == null)) stop(mc);
    }

    static void stop(Minecraft mc) {
        if (session == null) return;
        try {
            session.stop(sound -> mc.getSoundManager().stop((SoundInstance) sound));
            session.log("STOP fixtures stopped; references cleared");
        } finally {
            session = null;
            player = null;
            capturing = null;
            refreshing = false;
        }
    }

    private DevPlanetSoundProbe() {}
}
