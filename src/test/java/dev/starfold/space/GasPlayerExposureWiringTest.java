package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;

import dev.starfold.TestArchiveResources;
import dev.starfold.chart.*;
import dev.starfold.chart.GasPlayerExposureState.Participation;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.sky.Space;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class GasPlayerExposureWiringTest {
    private static final String SERVER = "dev/starfold/space/GasPlayerExposureServer";

    private static ClassNode type(String name) throws Exception {
        var node = new ClassNode();
        try (var in = TestArchiveResources.open("/" + name + ".class")) {
            assertNotNull(in);
            new ClassReader(in).accept(node, 0);
        }
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static List<String> calls(MethodNode method) {
        var calls = new ArrayList<String>();
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        return calls;
    }

    @Test
    void serverPostObservationIsOutsideEmptyHullCohortAndRegistersAllLifecycleHooks() throws Exception {
        var server = type(SERVER);
        assertTrue(calls(method(type("dev/starfold/StarfoldServer"), "initializeServerSystems"))
                .contains(SERVER + ".initialize"));
        var completedDeath = calls(method(server, "completedDeath"));
        assertTrue(completedDeath.contains("net/minecraft/server/level/ServerPlayer.isAlive"));
        assertTrue(completedDeath.contains(SERVER + "$PlayerState.completedDeath"));
        assertTrue(completedDeath.contains("dev/starfold/space/GasPlayerExposureSave.write"));
        assertFalse(completedDeath.stream().anyMatch(c -> c.endsWith(".tick")));
        var init = method(server, "initialize");
        assertTrue(calls(init).contains("dev/starfold/network/StarfoldNetwork.playS2C"));
        assertTrue(server.methods.stream().anyMatch(m -> m.desc.contains("ServerTickEvent$Post")));
        for (String hook : List.of("PlayerLoggedInEvent", "PlayerLoggedOutEvent", "PlayerEvent$Clone", "ServerStoppedEvent"))
            assertTrue(server.methods.stream().anyMatch(m -> m.desc.contains(hook)), hook);
        var source = Files.readString(Path.of("src/main/java/" + SERVER + ".java"));
        assertTrue(source.contains("EventPriority.LOWEST"));
        var observation = calls(method(server, "observe"));
        assertTrue(observation.contains("net/minecraft/server/players/PlayerList.getPlayers"));
        assertTrue(observation.contains("net/minecraft/server/level/ServerPlayer.isAlive"));
        assertTrue(observation.contains("net/minecraft/server/level/ServerPlayer.isCreative"));
        assertTrue(observation.contains("net/minecraft/server/level/ServerPlayer.isSpectator"));
        assertFalse(calls(method(type("dev/starfold/space/SpaceServer"), "step"))
                .stream().anyMatch(c -> c.startsWith(SERVER)));
    }

    @Test
    void attemptsCommitBeforeExactlyOneOrdinaryHurtAndNeverUseSuccessCadenceOrBypass() throws Exception {
        var server = type(SERVER);
        var observation = calls(method(server, "observe"));
        int commit = observation.indexOf("dev/starfold/space/GasPlayerExposureSave.write");
        int hurt = observation.indexOf("net/minecraft/server/level/ServerPlayer.hurt");
        assertTrue(commit >= 0 && hurt > commit);
        assertEquals(1, observation.stream().filter(c -> c.endsWith(".hurt")).count());
        for (var method : server.methods)
            for (var call : calls(method))
                assertFalse(List.of("kill", "setHealth", "setInvulnerable", "destroyBlock", "getChunk",
                        "loadChunk", "getAllSubLevels", "aboard").stream().anyMatch(n -> call.endsWith("." + n))
                        || call.contains("BoundarySpan") || call.contains("BoundaryPrefix")
                        || call.contains("GasDamageCooldown") || call.contains("GasShipDamage"), call);
        var data = JsonParser.parseString(Files.readString(Path.of(
                "src/main/resources/data/starfold/damage_type/gas_pressure.json"))).getAsJsonObject();
        assertEquals("starfold.gas_pressure", data.get("message_id").getAsString());
        assertEquals("never", data.get("scaling").getAsString());
        var tags = Path.of("src/main/resources/data/minecraft/tags/damage_type");
        if (Files.exists(tags)) try (var files = Files.walk(tags)) {
            for (var file : files.filter(Files::isRegularFile).toList())
                assertFalse(Files.readString(file).contains("starfold:gas_pressure"), file.toString());
        }
    }

    @Test
    void bodyCenterIsLevelPositionNotHullOrEyeAndFrameAndLayersShareOneTime() throws Exception {
        var sample = calls(method(type(SERVER), "sample"));
        assertTrue(sample.contains("net/minecraft/server/level/ServerPlayer.getBoundingBox"));
        assertTrue(sample.contains("net/minecraft/world/phys/AABB.getCenter"));
        assertEquals(1, sample.stream().filter(c -> c.endsWith("GroundEphemerisSave.tick")).count());
        assertTrue(sample.contains("dev/starfold/space/SpaceServer.frame"));
        assertTrue(sample.contains("dev/starfold/space/GasPlayerExposureSampling.sample"));
        assertFalse(sample.stream().anyMatch(c -> c.contains("Sable") || c.endsWith(".getEyeY")));
        var conversion = calls(method(type("dev/starfold/space/GasPlayerExposureSampling"), "sample"));
        assertEquals(1, conversion.stream().filter(c -> c.endsWith("Space$Frame.universe")).count());
        assertTrue(conversion.contains("dev/starfold/chart/GroundEphemeris$Model.frame"));
    }

    @Test
    void cloneCopiesCompleteStateAndLogoutCannotEraseNewOwnerOrRebaseOnRespawn() throws Exception {
        var server = type(SERVER);
        var clone = calls(method(server, "clonePlayer"));
        assertTrue(clone.contains(SERVER + ".transferClone"));
        var transfer = calls(method(server, "transferClone"));
        assertTrue(transfer.contains("java/util/function/Supplier.get"));
        assertTrue(transfer.contains("java/util/function/Consumer.accept"));
        var source = Files.readString(Path.of("src/main/java/" + SERVER + ".java"));
        assertTrue(source.contains("old.owner == newPlayer) return"));
        assertTrue(source.contains("old.owner != oldPlayer"));
        assertTrue(transfer.contains(SERVER + "$PlayerState.cloneFor"));
        assertTrue(source.contains("GasPlayerExposureSave.read(oldPlayer.getPersistentData())"));
        assertTrue(source.contains("GasPlayerExposureSave.write(newPlayer.getPersistentData(), values)"));
        assertTrue(source.contains("value.owner != player) return"));
        assertFalse(calls(method(server, "logout")).stream().anyMatch(c -> c.endsWith(".restored")));
        assertFalse(calls(method(server, "observe")).stream().anyMatch(c -> c.endsWith(".restored")));
        var space = type("dev/starfold/space/SpaceSessions");
        assertTrue(space.methods.stream().filter(m -> m.desc.contains("PlayerEvent$Clone"))
                .flatMap(m -> calls(m).stream()).anyMatch(c -> c.endsWith("WeakReference.clear")));
        assertTrue(calls(method(space, "removeView")).contains("dev/starfold/space/SpaceViewOwner.owns"));
        assertTrue(space.methods.stream().filter(m -> m.name.equals("view"))
                .flatMap(m -> calls(m).stream()).anyMatch(c -> c.equals("dev/starfold/space/SpaceViewOwner.owns")));
        // Respawn only syncs; duplicate notifications cannot rotate the new owner's session again.
        var respawn = space.methods.stream().filter(m -> m.desc.contains("PlayerRespawnEvent"))
                .findFirst().orElseThrow();
        assertEquals(List.of("net/neoforged/neoforge/event/entity/player/PlayerEvent$PlayerRespawnEvent.getEntity",
                "dev/starfold/space/SpaceServer.sync"), calls(respawn));
    }

    @Test
    void personalViewPublishesBeforePersonalPacketAndClientRegistersWithoutHullGuard() throws Exception {
        var send = calls(method(type(SERVER), "send"));
        assertTrue(send.indexOf("dev/starfold/space/SpaceServer.exposureView")
                < send.indexOf("net/neoforged/neoforge/network/PacketDistributor.sendToPlayer"));
        assertEquals(List.of("dev/starfold/space/SpaceServer.send", "dev/starfold/space/SpaceServer.view"),
                calls(method(type("dev/starfold/space/SpaceServer"), "exposureView")));
        assertTrue(calls(method(type("dev/starfold/StarfoldClient"), "initializeClientSystems"))
                .contains("dev/starfold/space/GasPlayerExposureClient.initialize"));
    }

    @Test
    void sampledDangerPersistsAttemptAndNewViewAuthorizesHudWithoutAValidHull() {
        var bodies = new ArrayList<>(GroundSolar.defaults().bodies());
        bodies.add(GroundEphemeris.Body.gas(new GroundEphemeris.Fixed(new Point(40000, 0, 0)),
                4096, new GasGiant(1, 31, "Amber", 0x98653d, 0xead1a2, 256, 512)));
        var model = new GroundEphemeris.Model(bodies);
        var center = Space.Frame.IDENTITY.level(new Point(43584, 0, 0), model, 0);
        var sample = GasPlayerExposureSampling.sample(true, model, Space.Frame.IDENTITY, 0, center);
        var state = GasPlayerExposureState.restored(GasPlayerExposureValues.initial());
        for (int tick = 0; tick <= 100; tick++) {
            var transition = state.tick(tick, sample, Participation.ACTIVE);
            assertEquals(tick == 100, transition.damageAttempt());
            state = transition.state(); // Even when ordinary hurt is canceled, no success ack exists.
        }
        var persistent = new CompoundTag();
        GasPlayerExposureSave.write(persistent, state.values());
        var reloaded = GasPlayerExposureState.restored(GasPlayerExposureSave.read(persistent.copy()));
        assertEquals(new GasPlayerExposureValues(0, 0, 20), reloaded.values());
        assertFalse(reloaded.tick(900000, sample, Participation.ACTIVE).damageAttempt());
        assertFalse(state.tick(100, sample, Participation.ACTIVE).damageAttempt(),
                "clone must retain the complete state, including the same-tick guard");
        var dimension = ResourceLocation.parse("starfold:system");
        var cache = new GasPlayerExposureCache();
        cache.focus(new GasPlayerExposureCache.Identity(dimension, 11));
        var old = GasPlayerExposurePacket.sampled(11, dimension, 0, sample, state.values(), Participation.ACTIVE);
        assertTrue(cache.receive(old, model, 1));
        cache.invalidate();
        cache.focus(new GasPlayerExposureCache.Identity(dimension, 12));
        assertFalse(cache.receive(old, model, 2));
        var fresh = GasPlayerExposurePacket.sampled(12, dimension, 0, sample, state.values(), Participation.ACTIVE);
        assertTrue(cache.receive(fresh, model, 2));
        assertFalse(GasPlayerExposureHud.lines(cache.current(2)).isEmpty());
        assertEquals(0, cache.current(2).snapshot().orElseThrow().values().graceTicks());
    }
}
