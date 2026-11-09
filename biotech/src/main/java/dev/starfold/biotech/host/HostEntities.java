package dev.starfold.biotech.host;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class HostEntities {
    private static final DeferredRegister<EntityType<?>> TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, "starfold_biotech");
    public static final DeferredHolder<EntityType<?>, EntityType<HostEntity>> HOST = TYPES.register("host", () ->
            EntityType.Builder.of(HostEntity::new, MobCategory.MONSTER).sized(10 * HostRules.SCALE, 9 * HostRules.SCALE).eyeHeight(6 * HostRules.SCALE)
                    .clientTrackingRange(16).updateInterval(2).build("starfold_biotech:host"));
    public static final DeferredHolder<EntityType<?>, EntityType<HostSac>> SAC = TYPES.register("host_sac", () ->
            EntityType.Builder.of(HostSac::new, MobCategory.MONSTER).sized(2.5f, 3)
                    .clientTrackingRange(12).build("starfold_biotech:host_sac"));
    public static final DeferredHolder<EntityType<?>, EntityType<HostDrone>> DRONE = TYPES.register("host_drone", () ->
            EntityType.Builder.of(HostDrone::new, MobCategory.MONSTER).sized(.9f, .8f)
                    .clientTrackingRange(10).updateInterval(2).build("starfold_biotech:host_drone"));
    public static final DeferredHolder<EntityType<?>, EntityType<HostAcid>> ACID = TYPES.register("host_acid", () ->
            EntityType.Builder.<HostAcid>of(HostAcid::new, MobCategory.MISC).sized(.5f, .5f)
                    .clientTrackingRange(10).updateInterval(1).build("starfold_biotech:host_acid"));

    public static void register(IEventBus bus) {
        TYPES.register(bus);
        bus.addListener((EntityAttributeCreationEvent event) -> {
            event.put(HOST.get(), HostEntity.attributes().build());
            event.put(SAC.get(), HostSac.attributes().build());
            event.put(DRONE.get(), HostDrone.attributes().build());
        });
    }

    private HostEntities() {}
}
