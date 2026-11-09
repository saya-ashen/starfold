package dev.starfold.space;

import dev.starfold.chart.GasPlayerExposure;
import dev.starfold.chart.GasPlayerExposureState;
import dev.starfold.chart.GasPlayerExposureState.Participation;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.network.StarfoldNetwork;
import dev.starfold.sky.GroundClock;
import dev.starfold.sky.Space;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Online personal observations, independent of the loaded ship cohort and C1 write cadence. */
public final class GasPlayerExposureServer {
    private static final ResourceKey<DamageType> PRESSURE = ResourceKey.create(
            Registries.DAMAGE_TYPE, ResourceLocation.parse("starfold:gas_pressure"));

    static final class PlayerState<P> {
        final P owner;
        GasPlayerExposureState exposure;
        long session, sequence = -1;

        PlayerState(P owner, GasPlayerExposureState exposure) {
            this.owner = owner;
            this.exposure = exposure;
        }

        boolean completedDeath(P caller, boolean alive) {
            if (owner != caller || alive) return false;
            exposure = interruptRecovery(exposure);
            return true;
        }

        PlayerState<P> cloneFor(P newOwner, boolean death) {
            return new PlayerState<>(newOwner, death ? interruptRecovery(exposure) : exposure);
        }
    }

    private static GasPlayerExposureState interruptRecovery(GasPlayerExposureState state) {
        var values = state.values();
        if (values.safeTicks() == 0) return state;
        return new GasPlayerExposureState(new dev.starfold.chart.GasPlayerExposureValues(
                values.graceTicks(), 0, values.attemptTicks()), state.lastTick());
    }

    private static final Map<UUID, PlayerState<ServerPlayer>> players = new HashMap<>();
    // A long session clock, not MinecraftServer's wrapping int or a dimension's gameTime.
    private static long tick;

    public static void initialize() {
        StarfoldNetwork.playS2C(GasPlayerExposurePacket.TYPE, GasPlayerExposurePacket.CODEC);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) ->
                state((ServerPlayer) e.getEntity()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.Clone e) ->
                clonePlayer((ServerPlayer) e.getOriginal(), (ServerPlayer) e.getEntity(), e.isWasDeath()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) ->
                logout((ServerPlayer) e.getEntity()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> {
            players.clear();
            tick = 0;
        });
        // All level physics, player movement and frame folds have completed. GroundClock advances
        // at ServerTick.Pre; neither sampling nor the conversion below advances that clock.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,
                (ServerTickEvent.Post e) -> observe(e.getServer()));
    }

    private static PlayerState<ServerPlayer> state(ServerPlayer player) {
        var old = players.get(player.getUUID());
        if (old != null && old.owner == player) return old;
        var value = new PlayerState<>(player, GasPlayerExposureState.restored(
                GasPlayerExposureSave.read(player.getPersistentData())));
        players.put(player.getUUID(), value);
        return value;
    }

    private static void clonePlayer(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean death) {
        transferClone(players, oldPlayer.getUUID(), oldPlayer, newPlayer, death,
                () -> GasPlayerExposureState.restored(
                        GasPlayerExposureSave.read(oldPlayer.getPersistentData())),
                values -> GasPlayerExposureSave.write(newPlayer.getPersistentData(), values));
    }

    static <P> void transferClone(Map<UUID, PlayerState<P>> states, UUID uuid,
            P oldPlayer, P newPlayer, boolean death,
            java.util.function.Supplier<GasPlayerExposureState> restore,
            java.util.function.Consumer<dev.starfold.chart.GasPlayerExposureValues> persist) {
        var old = states.get(uuid);
        if (old != null && old.owner == newPlayer) return; // Duplicate event must not roll back.
        if (old != null && old.owner != oldPlayer) return; // Stale source is not missing state.
        if (old == null)
            old = new PlayerState<>(oldPlayer, restore.get());
        var copy = old.cloneFor(newPlayer, death);
        persist.accept(copy.exposure.values());
        states.put(uuid, copy);
    }

    /** Called only by the existing ServerPlayer.die TAIL host, after a completed death. */
    public static void completedDeath(ServerPlayer player) {
        if (player.isAlive()) return;
        var value = players.get(player.getUUID());
        if (value == null) value = state(player);
        if (!value.completedDeath(player, false)) return;
        GasPlayerExposureSave.write(player.getPersistentData(), value.exposure.values());
    }

    private static void logout(ServerPlayer player) {
        var value = players.get(player.getUUID());
        if (value == null || value.owner != player) return; // Delayed old-object cleanup.
        GasPlayerExposureSave.write(player.getPersistentData(), value.exposure.values());
        players.remove(player.getUUID());
    }

    private static void observe(MinecraftServer server) {
        if (tick == Long.MAX_VALUE) throw new IllegalStateException("Personal gas tick exhausted");
        long now = ++tick;
        for (var player : List.copyOf(server.getPlayerList().getPlayers())) {
            var value = state(player);
            var participation = !player.isAlive() ? Participation.DEAD
                    : player.isCreative() || player.isSpectator()
                            ? Participation.EXEMPT : Participation.ACTIVE;
            var sample = sample(player);
            var transition = value.exposure.tick(now, sample, participation);
            value.exposure = transition.state();
            // Commit before hurt: canceled/mitigated attempts consume the cycle. Entity.save writes
            // this NeoForgeData even if logout follows a death or a synchronous damage callback.
            GasPlayerExposureSave.write(player.getPersistentData(), value.exposure.values());
            send(player, value, sample, participation);
            if (transition.damageAttempt())
                player.hurt(new DamageSource(player.registryAccess()
                        .registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(PRESSURE)),
                        GasPlayerExposureState.DAMAGE);
        }
    }

    private static GasPlayerExposure.Sample sample(ServerPlayer player) {
        // Entity's bounding box is already in Level coordinates, even for a carried passenger.
        // Do not run it through a Sable hull/plot transform a second time.
        var center = player.getBoundingBox().getCenter();
        var point = new Point(center.x, center.y, center.z);
        if (!Space.in(player))
            return GasPlayerExposureSampling.sample(false, null, null, 0, point);
        try {
            var level = player.serverLevel();
            var clock = GroundClock.get(level).clock;
            var model = clock.model;
            long at = clock.tick();
            var frame = SpaceServer.frame(level);
            return GasPlayerExposureSampling.sample(true, model, frame, at, point);
        } catch (IllegalArgumentException | IllegalStateException unavailableFrame) {
            return GasPlayerExposure.unknown();
        }
    }

    private static void send(ServerPlayer player, PlayerState<ServerPlayer> state,
            GasPlayerExposure.Sample sample, Participation participation) {
        if (!Space.in(player) || !player.connection.hasChannel(GasPlayerExposurePacket.TYPE)) return;
        long session;
        try {
            session = SpaceServer.exposureView(player);
        } catch (IllegalArgumentException | IllegalStateException unavailableView) {
            return; // Unknown frame freezes timers; absence of a View cannot authorize a packet.
        }
        if (session == 0) return;
        if (state.session != session) {
            state.session = session;
            state.sequence = -1;
        }
        if (state.sequence == Long.MAX_VALUE) return; // Never wrap an ordered stream.
        PacketDistributor.sendToPlayer(player, GasPlayerExposurePacket.sampled(session,
                player.serverLevel().dimension().location(), ++state.sequence,
                sample, state.exposure.values(), participation));
    }

    private GasPlayerExposureServer() {}
}
