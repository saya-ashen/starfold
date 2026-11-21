package dev.starfold.mining;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.fml.ModList;

import org.slf4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Optional, pinned CDG 1.3.15 boundary. No CDG class is linked by Mining or its solid miner.
 * Verified with javap: load(ServerLevel) -> SavedData "cdg_oil_chunks";
 * getChunkOilAmount(ChunkPos) -> mB or MAX_VALUE; setChunkAmount(ChunkPos,int) -> setDirty().
 * Never call removeChunk: an absent entry would re-generate the original reservoir.
 */
final class CdgOilReservoir {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static Method load, read, write;
    private static boolean attempted, broken;
    private static Fluid oil;

    static OilStatus availability() {
        if (!ModList.get().isLoaded("createdieselgenerators")) return OilStatus.CDG_MISSING;
        if (!attempted) {
            attempted = true;
            try {
                Class<?> type =
                        Class.forName("com.jesz.createdieselgenerators.world.OilChunksSavedData");
                load = type.getMethod("load", ServerLevel.class);
                read = type.getMethod("getChunkOilAmount", ChunkPos.class);
                write = type.getMethod("setChunkAmount", ChunkPos.class, int.class);
                oil =
                        BuiltInRegistries.FLUID.get(
                                ResourceLocation.parse("createdieselgenerators:crude_oil"));
                if (oil == Fluids.EMPTY || read.getReturnType() != int.class)
                    throw new IllegalStateException("CDG oil API mismatch");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                fail(failure);
            }
        }
        return broken ? OilStatus.CDG_ERROR : null;
    }

    static Fluid fluid() {
        if (availability() != null) throw new IllegalStateException("CDG oil is unavailable");
        return oil;
    }

    static OilCycle.Reservoir at(ServerLevel level, ChunkPos chunk) {
        if (availability() != null || !level.hasChunk(chunk.x, chunk.z))
            throw new IllegalStateException(
                    "Unavailable CDG reservoir or unloaded canonical chunk");
        Object data = invoke(load, null, level);
        return new OilCycle.Reservoir() {
            // One synchronous machine operation owns this adapter. There are no external
            // callbacks between preflight and private-tank commitment; the next operation
            // gets a fresh adapter and observes other machines' actual CDG writes.
            private Integer observed;

            @Override
            public int read() {
                if (observed == null) observed = Math.max(0, (Integer) invoke(read, data, chunk));
                return observed;
            }

            @Override
            public void write(int amount) {
                if (amount < 0) throw new IllegalArgumentException("Negative reservoir");
                invoke(write, data, chunk, amount);
                observed = amount;
            }
        };
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            if (broken) throw new IllegalStateException("CDG adapter disabled after failure");
            return method.invoke(target, args);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            Throwable cause =
                    failure instanceof InvocationTargetException invocation
                            ? invocation.getCause()
                            : failure;
            fail(cause);
            throw new IllegalStateException(
                    "CDG reservoir operation failed; extraction disabled", cause);
        }
    }

    private static void fail(Throwable failure) {
        if (!broken)
            LOGGER.error("Starfold oil extractor disabled: pinned CDG oil adapter failed", failure);
        broken = true;
    }

    private CdgOilReservoir() {}
}
