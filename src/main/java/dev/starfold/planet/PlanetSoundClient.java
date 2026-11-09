package dev.starfold.planet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/** Volume-only muffling, not an OpenAL filter. UI, music, records and voice remain untouched. */
public final class PlanetSoundClient {
    public interface Observer {
        default boolean enabled() { return false; }
        default void created(SoundInstance sound) {}
        default void scaled(SoundInstance sound, double density, boolean self, float scale) {}
        default void calculated(SoundInstance sound, float raw, float result, String phase) {}
        default void refreshing(boolean value) {}
    }

    public static Observer observer = new Observer() {};
    private static double lastDensity = Double.NaN;
    private static final ThreadLocal<Object> emitter = new ThreadLocal<>();
    // Only vanilla SimpleSoundInstances are registered here; their equality is object identity.
    private static final Map<SoundInstance, WeakReference<Object>> owners = new WeakHashMap<>();

    public static void withOwner(Object owner, Runnable play) {
        Object previous = emitter.get();
        emitter.set(owner);
        try {
            play.run();
        } finally {
            if (previous == null) emitter.remove();
            else emitter.set(previous);
        }
    }

    public static <T extends SoundInstance> T created(T sound) {
        Object owner = emitter.get();
        if (owner != null) {
            // Consume at construction, before SoundManager callbacks can emit unrelated sounds.
            emitter.remove();
            owners.put(sound, new WeakReference<>(owner));
        }
        if (observer.enabled()) observer.created(sound);
        return sound;
    }

    public static boolean self(SoundInstance sound, Object localPlayer) {
        Object owner;
        if (sound instanceof PlanetSoundOwner bound) owner = bound.starfold$soundOwner();
        else {
            var reference = owners.get(sound);
            owner = reference == null ? null : reference.get();
        }
        return localPlayer != null && owner == localPlayer;
    }

    public static double density() {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return 1;
        var environment = PlanetConditions.at(mc.level, mc.player.blockPosition());
        return environment == null ? 1 : environment.atmosphere().density();
    }

    public static boolean world(SoundSource source) {
        return switch (source) {
            case BLOCKS, HOSTILE, NEUTRAL, WEATHER, AMBIENT, PLAYERS -> true;
            default -> false;
        };
    }

    public static float scale(SoundInstance sound, Object localPlayer, double density) {
        return world(sound.getSource())
                ? (float) PlanetConditions.soundScale(density, self(sound, localPlayer))
                : 1;
    }

    public static float scale(SoundInstance sound) {
        double density = density();
        Object player = Minecraft.getInstance().player;
        float scale = scale(sound, player, density);
        if (observer.enabled()) observer.scaled(sound, density, self(sound, player), scale);
        return scale;
    }

    public static void tick() {
        double now = density();
        if (now == lastDensity) return;
        lastDensity = now;
        var mc = Minecraft.getInstance();
        // Refresh already-playing non-tickable channels when the observer changes planet.
        if (observer.enabled()) observer.refreshing(true);
        try {
            for (var source : SoundSource.values())
                if (world(source))
                    mc.getSoundManager()
                            .updateSourceVolume(source, mc.options.getSoundSourceVolume(source));
        } finally {
            if (observer.enabled()) observer.refreshing(false);
        }
    }

    private PlanetSoundClient() {}
}
