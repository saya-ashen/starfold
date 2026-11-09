package dev.starfold.biotech.host;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.universe.SystemChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashSet;
import java.util.UUID;

public final class HostEncounter {
    public static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.isSpectator()
                || player.tickCount % 20 != 0 || !PlanetLevels.is(player.level())) return;
        ServerLevel level = player.serverLevel();
        if (!(level.getChunkSource().getGenerator() instanceof SystemChunkGenerator system)) return;
        int slot = PlanetStorage.find(player.getX(), player.getZ());
        if (slot < 0 || slot >= system.count()) return;
        var planet = system.planet(slot);
        if(!planet.planReady())return;
        var nest=planet.terrain().terrainPlan().landforms().stream().filter(f->f.type().equals("starfold:hive")).findFirst().orElse(null);
        if(nest==null)return;
        var data = HostSave.get(level);
        if (data.spawned() || data.defeated()) return;
        var shape = planet.terrain().shape();
        var pose = shape.locate(nest.center().scale(shape.radius()));
        int u = (int) pose.point().x(), v = (int) pose.point().z();
        var origin = PlanetStorage.origin(slot);
        var chart = dev.starfold.planet.GroundCoordinates.world(pose.face(), new Point(u + .5,
                planet.terrain().groundY(pose.face(), u, v) + 1 - dev.starfold.planet.GroundCoordinates.FLOOR, v + .5)).add(origin);
        var centre = new Vec3(chart.x(), chart.y(), chart.z());
        double dx = player.getX() - centre.x, dz = player.getZ() - centre.z;
        if (!HostRules.shouldSpawn(data.spawned(), data.defeated(), dx * dx + dz * dz,
                player.getY() - centre.y, nest.radius())) return;

        // Materialize the small arena before entities are saved into its chunks.
        var host = HostEntities.HOST.get().create(level);
        var floor = floor(level, centre.x, centre.z);
        host.moveTo(floor.x, floor.y, floor.z, 0, 0);
        host.setArena(floor);
        if (!level.addFreshEntity(host)) return;
        var sacs = new HashSet<UUID>();
        double ring = Math.max(nest.radius() * .38, 12 * HostRules.SCALE + 3);
        for (int i = 0; i < HostRules.SAC_COUNT; i++) {
            double angle = i * Math.PI * 2 / HostRules.SAC_COUNT;
            var pos = floor(level, centre.x + Math.cos(angle) * ring, centre.z + Math.sin(angle) * ring);
            var sac = HostEntities.SAC.get().create(level);
            sac.setHost(host.getUUID());
            sac.moveTo(pos.x, pos.y, pos.z, 0, 0);
            if (level.addFreshEntity(sac)) sacs.add(sac.getUUID());
        }
        data.spawn(host.getUUID(), sacs);
    }

    private static Vec3 floor(ServerLevel level, double x, double z) {
        var pos = BlockPos.containing(x, 0, z);
        level.getChunkAt(pos);
        return new Vec3(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()), z);
    }

    private HostEncounter() {}
}
