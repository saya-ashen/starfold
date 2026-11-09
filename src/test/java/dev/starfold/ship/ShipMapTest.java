package dev.starfold.ship;

import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.space.MapNavigation;
import dev.starfold.chart.CelestialTarget;
import dev.starfold.space.CelestialNavigation;
import dev.starfold.planet.GroundTopology;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ShipMapTest {
    @Test void ownershipIsPersistentPrivateAndCannotBeTakenOverByAnotherPilot() {
        var save = ShipSaveTest.fixture();
        var a = UUID.randomUUID(); var b = UUID.randomUUID();
        var first = save.list().getFirst(); var second = save.list().getLast();
        assertTrue(save.owned(a).isEmpty());
        assertTrue(save.claim(first.uuid(), a)); assertFalse(save.claim(first.uuid(), b));
        assertTrue(save.claim(second.uuid(), b)); assertFalse(save.claim(UUID.randomUUID(), a));
        var loaded = ShipSave.load(save.save(new CompoundTag(), null), null);
        assertEquals(List.of(first), loaded.owned(a)); assertEquals(List.of(second), loaded.owned(b));
        assertTrue(loaded.owned(UUID.randomUUID()).isEmpty());
        loaded.remove(first.uuid()); assertFalse(loaded.owns(a, first.uuid()));
    }
    @Test void locationWireRoundTripsBothStatesWithoutLeakingStoragePointers() {
        var save = ShipSaveTest.fixture();
        var first = save.list().getFirst();
        var pose = new PlanetShape.Pose(Face.SOUTH, new Point(300, 40, 700));
        save.refresh(first.uuid(), Optional.of("船一"), new ShipRecord.OnSlot(1, Optional.of(new ShipRecord.ChartPlace(
                PlanetAccess.SHARED.bodyAnchor(1, pose), zero(), new Quaterniond(), 123))));
        save.pointerSaved(first.uuid(), new ShipRecord.Locator(1,2,(short)3,(short)4));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            ShipMapPackets.Ships.CODEC.encode(buffer, new ShipMapPackets.Ships(save.list()));
            var decoded = ShipMapPackets.Ships.CODEC.decode(buffer).records();
            assertEquals(2, decoded.size()); assertEquals(save.list().getFirst().state(), decoded.getFirst().state());
            assertEquals(save.list().getLast().state(), decoded.getLast().state());
            assertTrue(decoded.stream().allMatch(r -> r.locator().isEmpty())); assertEquals(0, buffer.readableBytes());
            assertEquals("船一", decoded.getFirst().name().orElseThrow());
            for (var target : List.of(new ShipMapPackets.Target(7,null), new ShipMapPackets.Target(-1,first.uuid()))) {
                buffer.clear(); ShipMapPackets.Target.CODEC.encode(buffer,target);
                assertEquals(target,ShipMapPackets.Target.CODEC.decode(buffer)); assertEquals(0,buffer.readableBytes());
            }
        } finally { buffer.release(); }
    }
    private static Point zero() { return new Point(0,0,0); }
    @Test void serverAlwaysFiltersSnapshotsAndAuthorizesShipTargetsBeforeResolvingPositions() throws Exception {
        var server = dev.starfold.Sources.text("MapNavigationServer");
        assertTrue(server.contains("new ShipMapPackets.Ships(save.owned(player.getUUID()))"));
        assertTrue(server.contains("if(!save.owns(player.getUUID(),intent.ship()))return"));
        assertFalse(server.contains("new ShipMapPackets.Ships(save.list())"));
        // Recovery may load an owned hull, but ownership and a resolvable position must precede it.
        int wake = server.indexOf("ShipDormancy.requestWake(player.serverLevel(),ship.uuid())");
        assertTrue(wake > server.indexOf("if(!save.owns(player.getUUID(),intent.ship()))return"));
        assertTrue(wake > server.indexOf("MapNavigation.position(model,ship,"));
        assertTrue(server.contains("if(Space.in(player)){dev.starfold.ship.ShipDormancy.requestWake"));
        assertTrue(wake < server.indexOf("SpaceServer.targetShip(player,ship.uuid())"));
        assertThrows(IllegalArgumentException.class, () -> new ShipMapPackets.Target(-1, null));
        assertThrows(IllegalArgumentException.class, () -> new ShipMapPackets.Target(0, UUID.randomUUID()));
    }
    @Test void untrustedLocationCountsDuplicatesAndMissingRecordsAreRefused() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            for (int count : new int[]{-1,4097}) {
                buffer.clear(); buffer.writeVarInt(count);
                assertThrows(IllegalArgumentException.class, () -> ShipMapPackets.Ships.CODEC.decode(buffer));
            }
            buffer.clear(); buffer.writeVarInt(1); buffer.writeNbt(null);
            assertThrows(IllegalArgumentException.class, () -> ShipMapPackets.Ships.CODEC.decode(buffer));
            buffer.clear(); var ship = ShipSaveTest.fixture().list().getFirst();
            ShipMapPackets.Ships.CODEC.encode(buffer,new ShipMapPackets.Ships(List.of(ship,ship)));
            assertThrows(IllegalArgumentException.class, () -> ShipMapPackets.Ships.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
    @Test void surfaceAndSpaceShipCoordinatesFollowTheMovingBodyNotTheStoragePlot() {
        var model = ShipParkingTest.model(); var save = ShipSaveTest.fixture(); var ship = save.list().getFirst();
        var pose = new PlanetShape.Pose(Face.NORTH,new Point(500,42,900));
        save.refresh(ship.uuid(),Optional.empty(),new ShipRecord.OnSlot(1,Optional.of(new ShipRecord.ChartPlace(
                PlanetAccess.SHARED.bodyAnchor(1,pose),zero(),new Quaterniond(),100))));
        ship=save.find(ship.uuid()); assertEquals(pose,MapNavigation.surface(ship));
        for (double tick : new double[]{100,10000}) {
            var expected=model.at(1,tick).world(GroundTopology.planet(PlanetShape.of(1),pose));
            assertEquals(0,expected.sub(MapNavigation.position(model,ship,tick)).length(),1e-8);
        }
        var place=ShipParking.capture(model,3,zero(),new Point(30000,2000,5000),new Quaterniond(),100);
        save.refresh(ship.uuid(),Optional.empty(),new ShipRecord.InSpace(place)); ship=save.find(ship.uuid());
        assertNull(MapNavigation.surface(ship));
        assertEquals(0,ShipParking.evaluate(model,place,10000).position().sub(MapNavigation.position(model,ship,10000)).length(),1e-8);
        assertEquals(0,MapNavigation.system(model,3)); assertEquals(2,MapNavigation.system(model,2));
        assertEquals(25000 + 8192, MapNavigation.systemRadius(model, 0), 1e-9);
        assertEquals(8192, MapNavigation.systemRadius(model, 2), 1e-9);
        assertEquals(2,MapNavigation.nearestSystem(model,new Point(-80000,0,1),100));
    }
    @Test void explicitStarTargetsDoNotBecomeLandingSlotsOrEnterTheLegacyCycle() {
        var model=ShipParkingTest.model(); var target=CelestialTarget.resolve(model,2);
        assertTrue(CelestialNavigation.knownTarget(model,2)); assertTrue(target.surfaceSlot(model).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> target.requireSurfaceSlot(model));
        assertFalse(CelestialNavigation.targets(model).contains(target));
        assertEquals(model.frame(2,150).center(),target.frame(model,150).center());
        assertFalse(CelestialNavigation.knownTarget(model,-1)); assertFalse(CelestialNavigation.knownTarget(model,10000));
    }
}
