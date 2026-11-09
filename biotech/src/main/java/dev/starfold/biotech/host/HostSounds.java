package dev.starfold.biotech.host;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class HostSounds {
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, "starfold_biotech");
    public static final DeferredHolder<SoundEvent, SoundEvent> IDLE = sound("idle"), HURT = sound("hurt"), HURT_SHELL = sound("hurt_shell"), HURT_OPEN = sound("hurt_open"),
            ROOTS_WARN = sound("roots_warn"), ROOTS = sound("roots"), VENT_CHARGE = sound("vent_charge"),
            VENT = sound("vent"), SPIT = sound("spit"), SLURRY_LAND = sound("slurry_land"),
            SHELL_BREAK = sound("shell_break"), CRY = sound("cry"), ENGINE = sound("engine"),
            DIVE = sound("dive"), EGG_DROP = sound("egg_drop"), DEATH = sound("death"), CRASH = sound("crash");

    private static DeferredHolder<SoundEvent, SoundEvent> sound(String name) {
        return SOUNDS.register("host." + name, () -> SoundEvent.createVariableRangeEvent(
                ResourceLocation.fromNamespaceAndPath("starfold_biotech", "host." + name)));
    }

    public static void register(IEventBus bus) { SOUNDS.register(bus); }
    private HostSounds() {}
}
