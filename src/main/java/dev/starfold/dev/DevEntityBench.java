package dev.starfold.dev;

import com.mojang.brigadier.arguments.StringArgumentType;

import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;

import java.util.*;

/** Disposable moving-cow fixture and server tick sampler, opt-in only. */
public final class DevEntityBench {
    private static final List<Cow> cows = new ArrayList<>();
    private static final List<net.minecraft.world.phys.Vec3> starts = new ArrayList<>();
    private static final long[] samples = new long[600];
    private static ServerPlayer reader;
    private static String label;
    private static int count;
    private static long begin;

    public static void initialize() {
        if (!Boolean.getBoolean("starfold.entityProbe")) return;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.RegisterCommandsEvent e) ->
                        e.getDispatcher()
                                .register(
                                        Commands.literal("entitybench")
                                                .requires(s -> s.hasPermission(2))
                                                .then(
                                                        Commands.argument(
                                                                        "args",
                                                                        StringArgumentType
                                                                                .greedyString())
                                                                .executes(
                                                                        c ->
                                                                                run(
                                                                                        c.getSource()
                                                                                                .getPlayerOrException(),
                                                                                        StringArgumentType
                                                                                                .getString(
                                                                                                        c,
                                                                                                        "args"))))));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.tick.ServerTickEvent.Pre e) ->
                        begin = System.nanoTime());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) -> sample());
    }

    private static int run(ServerPlayer p, String args) {
        if (!PlanetLevels.is(p.level())) throw new IllegalStateException("Planet level required");
        var a = args.split("\\s+");
        if (a[0].equals("setup")) setup(p, Integer.parseInt(a[1]), Integer.parseInt(a[2]));
        else if (a[0].equals("measure")) {
            reader = p;
            label = a[1];
            count = 0;
            starts.clear();
            for (var cow : cows) starts.add(cow.position());
        } else throw new IllegalArgumentException(args);
        return 1;
    }

    private static void setup(ServerPlayer p, int n, int slot) {
        if (n != 0 && n != 200 && n != 800 || slot < 0 || slot > 1)
            throw new IllegalArgumentException("Counts 0/200/800, slots 0/1");
        for (var cow : cows) cow.discard();
        cows.clear();
        var access = PlanetAccess.of(p.level());
        for (int id = 0; id < 2; id++) {
            var middle = access.middle(id, 0);
            int x = (int) middle.x(), z = (int) middle.z();
            for (int dx = -40; dx <= 40; dx += 16)
                for (int dz = -40; dz <= 40; dz += 16) {
                    var chunk = new ChunkPos(new BlockPos(x + dx, 249, z + dz));
                    p.serverLevel().setChunkForced(chunk.x, chunk.z, true);
                }
            for (int dx = -32; dx <= 32; dx++)
                for (int dz = -32; dz <= 32; dz++) {
                    p.level()
                            .setBlock(
                                    new BlockPos(x + dx, 249, z + dz),
                                    Blocks.STONE.defaultBlockState(),
                                    3);
                    if (Math.abs(dx) == 32 || Math.abs(dz) == 32)
                        for (int y = 250; y <= 253; y++)
                            p.level()
                                    .setBlock(
                                            new BlockPos(x + dx, y, z + dz),
                                            Blocks.GLASS.defaultBlockState(),
                                            3);
                }
        }
        var home = access.middle(0, 0);
        p.teleportTo(p.serverLevel(), home.x(), 250, home.z(), Set.of(), 0, 0);
        var at = access.middle(slot, 0);
        var random = new Random(20261009);
        for (int i = 0; i < n; i++) {
            var cow = EntityType.COW.create(p.level());
            cow.moveTo(
                    at.x() + random.nextDouble(-29, 29),
                    250,
                    at.z() + random.nextDouble(-29, 29),
                    0,
                    0);
            cow.setInvulnerable(true);
            cow.setPersistenceRequired();
            p.serverLevel().addFreshEntity(cow);
            cows.add(cow);
        }
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info("PROBE ENTITY_SETUP count {} slot {} home {} pen {}", n, slot, home, at);
    }

    private static void sample() {
        if (reader == null) return;
        samples[count++] = System.nanoTime() - begin;
        if (count != samples.length) return;
        int alive = 0, moved = 0;
        for (int i = 0; i < cows.size(); i++) {
            if (cows.get(i).isAlive()) alive++;
            if (cows.get(i).position().distanceToSqr(starts.get(i)) > .01) moved++;
        }
        double mean = Arrays.stream(samples).average().orElseThrow() / 1e6;
        Arrays.sort(samples);
        String result =
                String.format(
                        Locale.ROOT,
                        "PROBE ENTITY_BENCH %s ticks %d mean_ms %.6f p95_ms %.6f alive %d moved %d",
                        label,
                        count,
                        mean,
                        samples[(int) Math.ceil(count * .95) - 1] / 1e6,
                        alive,
                        moved);
        org.slf4j.LoggerFactory.getLogger("starfold-probe").info(result);
        reader.sendSystemMessage(Component.literal(result));
        reader = null;
    }

    private DevEntityBench() {}
}
