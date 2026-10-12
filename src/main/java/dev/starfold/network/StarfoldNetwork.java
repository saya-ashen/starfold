package dev.starfold.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.LinkedHashMap;
import java.util.Map;

/** Joins common payload codecs with separately initialized, dist-safe receivers. */
public final class StarfoldNetwork {
    private static final Map<ResourceLocation, Entry<?>> entries = new LinkedHashMap<>();
    private static final Map<ResourceLocation, IPayloadHandler<?>> clients = new LinkedHashMap<>(),
            servers = new LinkedHashMap<>();

    private record Entry<T extends CustomPacketPayload>(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            boolean toClient) {
        void register(PayloadRegistrar registrar) {
            if (toClient)
                registrar.playToClient(
                        type,
                        codec,
                        (payload, context) -> receiver(clients, type).handle(payload, context));
            else
                registrar.playToServer(
                        type,
                        codec,
                        (payload, context) -> receiver(servers, type).handle(payload, context));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> IPayloadHandler<T> receiver(
            Map<ResourceLocation, IPayloadHandler<?>> receivers, CustomPacketPayload.Type<T> type) {
        var handler = receivers.get(type.id());
        if (handler == null)
            throw new IllegalStateException("Missing payload receiver: " + type.id());
        return (IPayloadHandler<T>) handler;
    }

    public static <T extends CustomPacketPayload> void playS2C(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        type(type, codec, true);
    }

    public static <T extends CustomPacketPayload> void playC2S(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        type(type, codec, false);
    }

    private static <T extends CustomPacketPayload> void type(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            boolean toClient) {
        if (entries.putIfAbsent(type.id(), new Entry<>(type, codec, toClient)) != null)
            throw new IllegalStateException("Duplicate payload: " + type.id());
    }

    public static <T extends CustomPacketPayload> void clientReceiver(
            CustomPacketPayload.Type<T> type, IPayloadHandler<T> handler) {
        if (clients.putIfAbsent(type.id(), handler) != null)
            throw new IllegalStateException("Duplicate client receiver: " + type.id());
    }

    public static <T extends CustomPacketPayload> void serverReceiver(
            CustomPacketPayload.Type<T> type, IPayloadHandler<T> handler) {
        if (servers.putIfAbsent(type.id(), handler) != null)
            throw new IllegalStateException("Duplicate server receiver: " + type.id());
    }

    /** v3 carries every planet's saved identity in Shapes; reject old peers before decoding. */
    public static final String PROTOCOL = "3";

    public static void initialize(IEventBus modBus) {
        modBus.addListener(
                (RegisterPayloadHandlersEvent event) -> {
                    var registrar = event.registrar(PROTOCOL);
                    for (var entry : entries.values()) entry.register(registrar);
                });
    }

    private StarfoldNetwork() {}
}
