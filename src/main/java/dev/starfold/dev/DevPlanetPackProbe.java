package dev.starfold.dev;

import dev.starfold.chart.GroundCamera;
import dev.starfold.chart.GroundGuidance;
import dev.starfold.flight.GroundDynamics;
import dev.starfold.flight.GroundFlightServer;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.ground.GroundServer;
import dev.starfold.ground.GroundTopology;
import dev.starfold.ground.ImageStream;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetShape;
import dev.starfold.planet.PlanetSpawn;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.sky.GroundClock;
import dev.starfold.sky.GroundDaylight;
import dev.starfold.sky.GroundEphemeris;
import dev.starfold.worldgen.SystemChunkGenerator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

/** Opt-in acceptance observer. Admin moves/time skips are logged; never places signature blocks or mobs. */
final class DevPlanetPackProbe {
    static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("starfold-probe");
    static final Map<String, BlockPos> sites = new TreeMap<>();
    static final List<BlockPos> budding = new ArrayList<>();
    static Point lapPrevious, lapStart;
    static double lapAngle;
    static long lapTick;
    static int lapFace = -1, lapCrossings;

    static void lapTick(ServerPlayer p) {
        if (lapPrevious == null || PlanetSystem.planet(p) != 7) return;
        var pose = PlanetAccess.of(p.level()).body(new Point(p.getX(), p.getY(), p.getZ()));
        var shape = PlanetShape.of(7);
        var direction = shape.direction(pose.face(), pose.point().x(), pose.point().z());
        lapAngle += Math.acos(Math.clamp(lapPrevious.dot(direction), -1, 1));
        lapPrevious = direction;
        if (lapFace != pose.face().ordinal()) {
            lapCrossings++;
            lapFace = pose.face().ordinal();
            LOG.info("PROBE PACK LAP_FACE {} angle {}", lapFace, lapAngle);
        }
        if (lapAngle >= Math.PI * 2) {
            LOG.info(
                    "PROBE PACK LAP_COMPLETE size {} surfaceDistance {} ticks {} crossings {} closureDistance {} position {}",
                    shape.size(),
                    lapAngle * shape.radius(),
                    p.level().getGameTime() - lapTick,
                    lapCrossings,
                    Math.acos(Math.clamp(lapStart.dot(direction), -1, 1)) * shape.radius(),
                    p.position());
            lapPrevious = null;
        }
    }

    static void initialize() {
        if (!Boolean.getBoolean("starfold.packProbe")) return;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.RegisterCommandsEvent e) ->
                        e.getDispatcher()
                                .register(
                                        net.minecraft.commands.Commands.literal("packprobe")
                                                .requires(s -> s.hasPermission(2))
                                                .then(
                                                        net.minecraft.commands.Commands.argument(
                                                                        "args",
                                                                        com.mojang.brigadier
                                                                                .arguments
                                                                                .StringArgumentType
                                                                                .greedyString())
                                                                .executes(
                                                                        c -> {
                                                                            run(
                                                                                    c.getSource()
                                                                                            .getPlayerOrException(),
                                                                                    com.mojang
                                                                                            .brigadier
                                                                                            .arguments
                                                                                            .StringArgumentType
                                                                                            .getString(
                                                                                                    c,
                                                                                                    "args"));
                                                                            return 1;
                                                                        }))));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) -> {
                    for (var p : e.getServer().getPlayerList().getPlayers())
                        if (GroundServer.is(p.level())) lapTick(p);
                    if (e.getServer().getTickCount() % 20 == 0)
                        for (var p : e.getServer().getPlayerList().getPlayers())
                            if (GroundServer.is(p.level()))
                                for (var mob : p.serverLevel().getAllEntities())
                                    if (mob
                                            instanceof
                                            net.minecraft.world.entity.monster.warden.Warden)
                                        LOG.info(
                                                "PROBE PACK WARDEN tick {} id {} pos {} pose {} playerMode {} difficulty {}",
                                                p.level().getGameTime(),
                                                mob.getUUID(),
                                                mob.position(),
                                                mob.getPose(),
                                                p.gameMode.getGameModeForPlayer(),
                                                p.level().getDifficulty());
                });
    }

    static void run(ServerPlayer p, String args) {
        LOG.info("PROBE PACK COMMAND {}", args);
        var a = args.split(" ");
        var level = p.serverLevel();
        var gen = (SystemChunkGenerator) level.getChunkSource().getGenerator();
        var clock = GroundClock.get(level);
        switch (a[0]) {
            case "visit", "orbit", "descent" -> {
                int id = Integer.parseInt(a[1]);
                var shape = PlanetShape.of(id);
                var site =
                        PlanetSpawn.site(
                                level,
                                id,
                                a.length > 2 ? Integer.parseInt(a[2]) : Face.SOUTH.ordinal());
                if (a[0].equals("visit")) {
                    sites.clear();
                    noon(p, BlockPos.containing(site.x(), site.y(), site.z()), id);
                }
                var pose = PlanetAccess.of(level).body(site);
                boolean orbit = !a[0].equals("visit");
                if (orbit)
                    pose =
                            new GroundTopology.Pose(
                                    pose.face(),
                                    new Point(
                                            shape.half(),
                                            128
                                                    + (a[0].equals("descent")
                                                            ? 1530
                                                            : shape.radius()
                                                                    * (a.length > 3
                                                                            ? Double.parseDouble(
                                                                                    a[3])
                                                                            : 3.7)),
                                            shape.half()));
                var state =
                        GroundDynamics.local(
                                clock.clock.tick(),
                                id,
                                pose,
                                GroundEphemeris.ZERO,
                                GroundCamera.capture(0, 15),
                                orbit,
                                new GroundGuidance.Options(id, true, false));
                p.getAbilities().flying = orbit;
                p.onUpdateAbilities();
                GroundFlightServer.enter(p, clock, state);
                LOG.info(
                        "PROBE PACK VISIT id {} size {} seed {} site {} orbit {} elevation {} oceanGridShare {} generated {} nanos {}",
                        id,
                        shape.size(),
                        gen.planet(id).seed(),
                        site,
                        orbit,
                        GroundDaylight.elevation(
                                level, BlockPos.containing(site.x(), site.y(), site.z())),
                        gen.planet(id).terrain().oceanShare(),
                        SystemChunkGenerator.GENERATED.sum(),
                        SystemChunkGenerator.NANOS.sum());
            }
            case "lightaudit" -> ImageStream.auditLight(p);
            case "surfaceaudit" -> {
                var t = gen.planet(0).terrain();
                for (int z : new int[] {64, 416, 1312}) {
                    int x = 64;
                    var f = Face.SOUTH;
                    int y = t.surfaceY(f, x, z);
                    var pos =
                            PlanetStorage.block(0, new CubeSurfaceTopology.Cell(f, x, y - 128, z));
                    level.getChunkAt(pos);
                    var actual = level.getBlockState(pos);
                    var expected = t.surfaceState(f, x, z);
                    var biome = level.getBiome(pos).value();
                    LOG.info(
                            "PROBE PORT SERVER pos {} actual {} expected {} equal {} biome {} grass {} foliage {} water {}",
                            pos,
                            actual,
                            expected,
                            actual.equals(expected),
                            level.getBiome(pos).unwrapKey(),
                            Integer.toHexString(biome.getGrassColor(pos.getX(), pos.getZ())),
                            Integer.toHexString(biome.getFoliageColor()),
                            Integer.toHexString(biome.getWaterColor()));
                }
            }
            case "steep" -> {
                int id = PlanetSystem.planet(p);
                var t = gen.planet(id).terrain();
                int bx = 64, bz = 64, best = -1;
                for (int x = 64; x < t.shape().size() - 80; x += 16)
                    for (int z = 64; z < t.shape().size() - 80; z += 16) {
                        int y = t.groundY(Face.SOUTH, x, z);
                        int score =
                                Math.abs(y - t.groundY(Face.SOUTH, x + 16, z))
                                        + Math.abs(y - t.groundY(Face.SOUTH, x, z + 16));
                        if (score > best) {
                            best = score;
                            bx = x;
                            bz = z;
                        }
                    }
                int y = t.surfaceY(Face.SOUTH, bx, bz) + 3;
                var at =
                        PlanetStorage.block(
                                id, new CubeSurfaceTopology.Cell(Face.SOUTH, bx, y - 128, bz));
                LOG.info("PROBE PORT STEEP id {} score {} pos {}", id, best, at);
                run(p, "at " + (at.getX() + .5) + " " + y + " " + (at.getZ() + .5));
            }
            case "survey" -> survey(p, Integer.parseInt(a[1]));
            case "geodepath" -> {
                for (var bud : List.copyOf(budding)) {
                    var queue = new ArrayDeque<BlockPos>();
                    var seen = new HashSet<BlockPos>();
                    for (var dir : net.minecraft.core.Direction.values()) {
                        var n = bud.relative(dir);
                        if (level.getBlockState(n).isAir()) {
                            queue.add(n);
                            seen.add(n);
                        }
                    }
                    BlockPos exit = null;
                    while (!queue.isEmpty() && seen.size() < 12000) {
                        var at = queue.remove();
                        boolean sky =
                                level.getHeight(Heightmap.Types.WORLD_SURFACE, at.getX(), at.getZ())
                                        <= at.getY();
                        if (sky) {
                            exit = at;
                            break;
                        }
                        for (var dir : net.minecraft.core.Direction.values()) {
                            var n = at.relative(dir);
                            if (Math.abs(n.getX() - bud.getX()) > 16
                                    || Math.abs(n.getZ() - bud.getZ()) > 16
                                    || Math.abs(n.getY() - bud.getY()) > 20
                                    || seen.contains(n)
                                    || !level.getBlockState(n).isAir()) continue;
                            seen.add(n);
                            queue.add(n);
                        }
                    }
                    if (exit != null) {
                        sites.put("geode", bud);
                        sites.put("geode_exit", exit);
                        LOG.info(
                                "PROBE PACK GEODE_OPEN budding {} exit {} reachedAirCells {} budNeighbours {}",
                                bud,
                                exit,
                                seen.size(),
                                java.util.Arrays.stream(net.minecraft.core.Direction.values())
                                        .map(d -> d + "=" + level.getBlockState(bud.relative(d)))
                                        .toList());
                        break;
                    }
                }
                if (!sites.containsKey("geode"))
                    LOG.info(
                            "PROBE PACK GEODE_NO_OPEN_PATH candidates {} maxAirCells12000 radius16 height20",
                            budding.size());
            }
            case "at" -> {
                var feet =
                        new Point(
                                Double.parseDouble(a[1]),
                                Double.parseDouble(a[2]),
                                Double.parseDouble(a[3]));
                int id = PlanetAccess.of(level).storagePlanet(feet.x(), feet.z());
                var pose = PlanetAccess.of(level).body(feet);
                boolean flying = a.length > 4 && a[4].equals("fly");
                p.getAbilities().flying = flying;
                p.onUpdateAbilities();
                GroundFlightServer.enter(
                        p,
                        clock,
                        GroundDynamics.local(
                                clock.clock.tick(),
                                id,
                                pose,
                                GroundEphemeris.ZERO,
                                GroundCamera.capture(0, 10),
                                flying,
                                GroundDynamics.OFF));
            }
            case "lapstart" -> {
                var pose = PlanetAccess.of(level).body(new Point(p.getX(), p.getY(), p.getZ()));
                lapPrevious =
                        PlanetShape.of(7)
                                .direction(pose.face(), pose.point().x(), pose.point().z());
                lapStart = lapPrevious;
                lapAngle = 0;
                lapCrossings = 0;
                lapFace = pose.face().ordinal();
                lapTick = level.getGameTime();
                LOG.info("PROBE PACK LAP_START {} tick {}", p.position(), lapTick);
            }
            case "lapstop" -> {
                LOG.info(
                        "PROBE PACK LAP_STOP remaining {} distance {} crossings {}",
                        lapPrevious != null,
                        lapAngle * PlanetShape.of(7).radius(),
                        lapCrossings);
                lapPrevious = null;
            }
            case "deep" -> {
                var counts = new TreeMap<String, Integer>();
                int columns = 0;
                for (int dx = -32; dx < 48; dx += 2)
                    for (int dz = -32; dz < 48; dz += 2) {
                        int x = p.getBlockX() + dx, z = p.getBlockZ() + dz;
                        var chunk = level.getChunk(x >> 4, z >> 4);
                        int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
                        columns++;
                        for (int y = gen.planet(PlanetSystem.planet(p)).terrain().floor();
                                y <= top;
                                y++) {
                            var s = chunk.getBlockState(new BlockPos(x, y, z));
                            if (s.is(Blocks.BLUE_ICE)
                                    || s.is(Blocks.PACKED_ICE)
                                    || s.is(Blocks.NETHER_GOLD_ORE)
                                    || s.is(Blocks.NETHER_QUARTZ_ORE))
                                counts.merge(s.toString(), 1, Integer::sum);
                        }
                    }
                LOG.info(
                        "PROBE PACK DEEP id {} columns {} stride 2 counts {}",
                        PlanetSystem.planet(p),
                        columns,
                        counts);
            }
            case "seekore" -> {
                int id = PlanetSystem.planet(p);
                var t = gen.planet(id).terrain();
                boolean found = false;
                for (int u = 64; u < t.shape().size() - 64 && !found; u += 8)
                    for (int v = 64; v < t.shape().size() - 64; v += 8) {
                        int y = t.groundY(Face.SOUTH, u, v);
                        var s = t.state(Face.SOUTH, u, y, v);
                        if (s.is(Blocks.IRON_ORE) || s.is(Blocks.COPPER_ORE)) {
                            var at =
                                    PlanetStorage.block(
                                            id,
                                            new CubeSurfaceTopology.Cell(
                                                    Face.SOUTH, u, y - 128, v));
                            level.getChunkAt(at);
                            if (level.getBlockState(at).equals(s)) {
                                sites.put("ore", at);
                                LOG.info("PROBE PACK SEEK_ORE {} {}", at, s);
                                found = true;
                                break;
                            }
                        }
                    }
            }
            case "geodeinside" -> {
                var bud = sites.get("geode");
                if (bud == null) return;
                BlockPos stand = null;
                double best = 1e9;
                for (var at : BlockPos.betweenClosed(bud.offset(-5, -6, -5), bud.offset(5, 3, 5))) {
                    if (!level.getBlockState(at).isAir()
                            || !level.getBlockState(at.above()).isAir()
                            || level.getBlockState(at.below()).isAir()) continue;
                    double dist = at.distSqr(bud);
                    if (dist > 3 && dist < best) {
                        stand = at.immutable();
                        best = dist;
                    }
                }
                if (stand == null) {
                    LOG.info("PROBE PACK GEODE_NO_STAND {}", bud);
                    return;
                }
                var feet = new Point(stand.getX() + .5, stand.getY(), stand.getZ() + .5);
                int id = PlanetSystem.planet(p);
                GroundFlightServer.enter(
                        p,
                        clock,
                        GroundDynamics.local(
                                clock.clock.tick(),
                                id,
                                PlanetAccess.of(level).body(feet),
                                GroundEphemeris.ZERO,
                                GroundCamera.capture(0, 0),
                                false,
                                GroundDynamics.OFF));
                LOG.info(
                        "PROBE PACK GEODE_INSIDE feet {} budding {} headroom {} {} floor {}",
                        feet,
                        bud,
                        level.getBlockState(stand),
                        level.getBlockState(stand.above()),
                        level.getBlockState(stand.below()));
            }
            case "wardenaudit" -> {
                LOG.info(
                        "PROBE PACK WARDEN_PLAYER {}",
                        p.saveWithoutId(new net.minecraft.nbt.CompoundTag()));
                for (var entry : sites.entrySet())
                    if (entry.getKey().equals("shrieker")) {
                        var be = level.getBlockEntity(entry.getValue());
                        LOG.info(
                                "PROBE PACK SHRIEKER_NBT {} {}",
                                entry.getValue(),
                                be == null
                                        ? "absent"
                                        : be.saveWithFullMetadata(level.registryAccess()));
                    }
            }
            case "site" -> {
                var at = sites.get(a[1]);
                if (at == null) {
                    LOG.info("PROBE PACK SITE MISSING {}", a[1]);
                    return;
                }
                var near = at.offset(2, 0, 2);
                level.getChunkAt(near);
                int y =
                        level.getHeight(
                                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                near.getX(),
                                near.getZ());
                var feet = new Point(near.getX() + .5, y, near.getZ() + .5);
                int id = PlanetAccess.of(level).storagePlanet(near);
                var pose = PlanetAccess.of(level).body(feet);
                p.getAbilities().flying = false;
                p.onUpdateAbilities();
                GroundFlightServer.enter(
                        p,
                        clock,
                        GroundDynamics.local(
                                clock.clock.tick(),
                                id,
                                pose,
                                GroundEphemeris.ZERO,
                                GroundCamera.capture(135, 25),
                                false,
                                GroundDynamics.OFF));
                LOG.info(
                        "PROBE PACK SITE {} target {} feet {} state {}",
                        a[1],
                        at,
                        feet,
                        level.getBlockState(at));
            }
            case "home" -> {
                int id = PlanetSystem.planet(p);
                LOG.info(
                        "PROBE PACK HOME id {} pos {} dimension {}",
                        id,
                        p.position(),
                        level.dimension().location());
                var t = gen.planet(0).terrain();
                for (int d : new int[] {-8, 0, 8}) {
                    var pos = p.blockPosition().offset(d, 0, d);
                    var cell = PlanetAccess.of(level).cell(pos);
                    int y = t.groundY(cell.face(), cell.u(), cell.v());
                    boolean same = true;
                    for (int h = y - 12; h <= y; h++)
                        same &=
                                level.getBlockState(pos.atY(h))
                                        .equals(t.state(cell.face(), cell.u(), h, cell.v()));
                    LOG.info(
                            "PROBE PACK HOME_COLUMN {} ground {} baselineMatch {} top {}",
                            pos,
                            y,
                            same,
                            level.getBlockState(pos.atY(y)));
                }
            }
            default -> throw new IllegalArgumentException(args);
        }
    }

    static void noon(ServerPlayer p, BlockPos site, int id) {
        var clock = GroundClock.get(p.serverLevel());
        long now = clock.clock.tick(), best = 0;
        double elevation = -2;
        long period = (long) (id < 2 ? 48000 : clock.clock.model.more().get(id - 2).period());
        for (long ahead = 0; ahead <= period; ahead += 100) {
            double e = GroundDaylight.elevation(p.serverLevel(), site, now + ahead);
            if (e > elevation) {
                elevation = e;
                best = ahead;
            }
        }
        clock.skip(best);
        LOG.info(
                "PROBE PACK NOON id {} skipped {} elevation {} skyTick {}",
                id,
                best,
                elevation,
                clock.clock.tick());
    }

    static void survey(ServerPlayer p, int radius) {
        budding.clear();
        if (radius < 0 || radius > 3)
            throw new IllegalArgumentException("survey radius 0..3 chunks");
        var level = p.serverLevel();
        int id = PlanetSystem.planet(p);
        int cx = p.getBlockX() >> 4, cz = p.getBlockZ() >> 4;
        var counts = new TreeMap<String, Integer>();
        long before = SystemChunkGenerator.GENERATED.sum(), ns = SystemChunkGenerator.NANOS.sum();
        int sea = 0, columns = 0;
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++) {
                var chunk = level.getChunk(cx + dx, cz + dz);
                int shriekers = 0, summoning = 0, sensors = 0;
                for (int x = chunk.getPos().getMinBlockX(); x <= chunk.getPos().getMaxBlockX(); x++)
                    for (int z = chunk.getPos().getMinBlockZ();
                            z <= chunk.getPos().getMaxBlockZ();
                            z++) {
                        int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
                        columns++;
                        if (level.getBlockState(new BlockPos(x, 127, z)).is(Blocks.WATER)) sea++;
                        for (int y = Math.max(level.getMinBuildHeight(), top - 24);
                                y <= top + 1;
                                y++) {
                            var at = new BlockPos(x, y, z);
                            var s = chunk.getBlockState(at);
                            if (s.isAir()) continue;
                            String name = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
                            counts.merge(name, 1, Integer::sum);
                            if (s.is(Blocks.SCULK_SHRIEKER)) {
                                shriekers++;
                                if (s.getValue(BlockStateProperties.CAN_SUMMON)) {
                                    summoning++;
                                    sites.putIfAbsent("shrieker", at);
                                    LOG.info(
                                            "PROBE PACK SUMMONING chunk {} pos {} state {}",
                                            chunk.getPos(),
                                            at,
                                            s);
                                }
                            }
                            if (s.is(Blocks.SCULK_SENSOR)) {
                                sensors++;
                                LOG.info("PROBE PACK SENSOR {}", at);
                            }
                            if (s.is(Blocks.BUDDING_AMETHYST)) {
                                budding.add(at);
                                boolean exposed = false;
                                for (var dir : net.minecraft.core.Direction.values())
                                    if (level.getBlockState(at.relative(dir)).isAir()
                                            && level.canSeeSky(at.relative(dir))) exposed = true;
                                if (exposed) {
                                    sites.putIfAbsent("geode", at);
                                    LOG.info("PROBE PACK EXPOSED_BUDDING {}", at);
                                }
                            }
                            if (s.is(Blocks.CACTUS)) sites.putIfAbsent("cactus", at);
                            if (s.is(Blocks.JUNGLE_LOG)) sites.putIfAbsent("jungle", at);
                            if (s.is(Blocks.RED_MUSHROOM_BLOCK)
                                    || s.is(Blocks.BROWN_MUSHROOM_BLOCK))
                                sites.putIfAbsent("mushroom", at);
                            if (s.is(Blocks.CHORUS_PLANT)) sites.putIfAbsent("chorus", at);
                            if (s.is(Blocks.MAGMA_BLOCK) && y >= top - 1)
                                sites.putIfAbsent("magma", at);
                            if ((s.is(Blocks.IRON_ORE) || s.is(Blocks.COPPER_ORE)) && y == top)
                                sites.putIfAbsent("ore", at);
                        }
                    }
                LOG.info(
                        "PROBE PACK CHUNK id {} chunk {} shriekers {} canSummon {} sensors {}",
                        id,
                        chunk.getPos(),
                        shriekers,
                        summoning,
                        sensors);
            }
        var mobs = new TreeMap<String, Integer>();
        for (var e : level.getAllEntities())
            if (e.distanceToSqr(p) < 128 * 128)
                mobs.merge(
                        BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),
                        1,
                        Integer::sum);
        LOG.info(
                "PROBE PACK SURVEY id {} radius {} columns {} waterColumns {} fraction {} counts {} sites {} mobs {} fillChunksDelta {} fillMillisDelta {}",
                id,
                radius,
                columns,
                sea,
                sea / (double) columns,
                counts,
                sites,
                mobs,
                SystemChunkGenerator.GENERATED.sum() - before,
                (SystemChunkGenerator.NANOS.sum() - ns) / 1e6);
    }
}
