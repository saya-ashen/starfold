package dev.starfold.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;


import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Optional;

/** Recipe mineral tables, normalized to the nominal surface and floor. */
public record PlanetMinerals(List<Ore> ores, List<Stratum> strata) {
    public static final int SURFACE = PlanetTerrain.SEA_LEVEL - 1,
            FLOOR = SURFACE - PlanetGeneration.STANDARD_DEPTH,
            MAX_ORES = 16,
            MAX_STRATA = 8;

    public static int layer(double share) {
        return (int) Math.round(SURFACE - share * PlanetGeneration.STANDARD_DEPTH);
    }

    public static double share(int layer) {
        return (SURFACE - layer) / (double) PlanetGeneration.STANDARD_DEPTH;
    }

    private static final Codec<Block> BLOCK = BuiltInRegistries.BLOCK.byNameCodec();
    private static final Codec<Double> SHARE = Codec.doubleRange(0, 1);

    /** An ore between two layers, the higher first: the rarer, the nearer its rarity is to 1. Below the first stratum
     *  it is its deep form, if it has one. Earlier ores are found first where two would lie in one block. */
    public record Ore(Block block, Optional<Block> deep, int top, int bottom, double rarity) {
        static final Codec<Ore> CODEC =
                RecordCodecBuilder.create(
                        i ->
                                i.group(
                                                BLOCK.fieldOf("block").forGetter(Ore::block),
                                                BLOCK.optionalFieldOf("deep").forGetter(Ore::deep),
                                                SHARE.optionalFieldOf("from", 0.0)
                                                        .forGetter(o -> share(o.top)),
                                                SHARE.optionalFieldOf("to", 1.0)
                                                        .forGetter(o -> share(o.bottom)),
                                                Codec.doubleRange(.5, .999)
                                                        .fieldOf("rarity")
                                                        .forGetter(Ore::rarity))
                                        .apply(
                                                i,
                                                (block, deep, from, to, rarity) ->
                                                        new Ore(
                                                                block,
                                                                deep,
                                                                layer(from),
                                                                layer(to),
                                                                rarity)));

        public Ore {
            if (block == null
                    || deep == null
                    || top < bottom
                    || top > SURFACE
                    || bottom < FLOOR
                    || !(rarity >= .5 && rarity <= .999))
                throw new IllegalArgumentException("Planet ore");
            solid(block);
            deep.ifPresent(PlanetMinerals::solid);
        }
    }

    /** The rock from a layer down to the next stratum. The first begins at the surface and is also the rock of
     *  whatever stands above it. */
    public record Stratum(Block block, int from) {
        static final Codec<Stratum> CODEC =
                RecordCodecBuilder.create(
                        i ->
                                i.group(
                                                BLOCK.fieldOf("block").forGetter(Stratum::block),
                                                SHARE.optionalFieldOf("from", 0.0)
                                                        .forGetter(s -> share(s.from)))
                                        .apply(
                                                i,
                                                (block, from) -> new Stratum(block, layer(from))));

        public Stratum {
            if (block == null || from > SURFACE || from < FLOOR)
                throw new IllegalArgumentException("Planet stratum");
            solid(block);
        }
    }

    public static final Codec<PlanetMinerals> CODEC =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            Ore.CODEC
                                                    .listOf()
                                                    .optionalFieldOf("ores", List.of())
                                                    .forGetter(PlanetMinerals::ores),
                                            Stratum.CODEC
                                                    .listOf()
                                                    .optionalFieldOf("strata", List.of())
                                                    .forGetter(PlanetMinerals::strata))
                                    .apply(i, PlanetMinerals::new));

    public PlanetMinerals {
        ores = List.copyOf(ores);
        strata = List.copyOf(strata);
        if (ores.size() > MAX_ORES || strata.size() > MAX_STRATA)
            throw new IllegalArgumentException("Planet minerals: too many ores or strata");
        if (!strata.isEmpty() && strata.get(0).from() != SURFACE)
            throw new IllegalArgumentException("The first stratum begins at the surface");
        for (int at = 1; at < strata.size(); at++)
            if (strata.get(at).from() >= strata.get(at - 1).from())
                throw new IllegalArgumentException("Strata are written from the top down");
    }

    private static void solid(Block block) {
        if (block.defaultBlockState().isAir()
                || !block.defaultBlockState().getFluidState().isEmpty())
            throw new IllegalArgumentException(
                    "A planet is made of solid blocks, not "
                            + BuiltInRegistries.BLOCK.getKey(block));
    }

    /** Read by the generators for every block of a column: the tables as arrays of states. */
    public static final class Table {
        final net.minecraft.world.level.block.state.BlockState[] ore, deepOre, rock;
        final int[] top, bottom, from;
        final double[] rarity;
        final int deepLine;

        public Table(PlanetMinerals minerals) {
            int n = minerals.ores.size();
            ore = new net.minecraft.world.level.block.state.BlockState[n];
            deepOre = new net.minecraft.world.level.block.state.BlockState[n];
            top = new int[n];
            bottom = new int[n];
            rarity = new double[n];
            for (int i = 0; i < n; i++) {
                var o = minerals.ores.get(i);
                ore[i] = o.block().defaultBlockState();
                deepOre[i] = o.deep().orElse(o.block()).defaultBlockState();
                top[i] = o.top();
                bottom[i] = o.bottom();
                rarity[i] = o.rarity();
            }
            int m = minerals.strata.size();
            rock = new net.minecraft.world.level.block.state.BlockState[m];
            from = new int[m];
            for (int i = 0; i < m; i++) {
                rock[i] = minerals.strata.get(i).block().defaultBlockState();
                from[i] = minerals.strata.get(i).from();
            }
            deepLine = m > 1 ? from[1] : 0;
        }

        /** The stratum a layer lies in, or null where the planet has no strata of its own. */
        public net.minecraft.world.level.block.state.BlockState rock(int layer) {
            if (rock.length == 0) return null;
            int at = 0;
            while (at + 1 < rock.length && layer < from[at + 1]) at++;
            return rock[at];
        }
    }
}
