package dev.starfold.render;

import net.minecraft.world.level.block.Block;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;

/** Versioned full-height sparse columns, capped at 32 non-air material runs per column. */
final class GroundSurfaceCodec {
    static final int MAX_RUNS = dev.starfold.worldgen.lod.VoxelColumn.MAX_RUNS;
    static final int MAX_BYTES = 2 + 256 * MAX_RUNS * 4 + 256 + 256 * MAX_RUNS * 6;

    static byte[] pack(GroundSurface surface) {
        return surface.packed();
    }

    static byte[] pack(GroundSurface.Run[][] columns) {
        if (columns.length != 256) throw new IllegalArgumentException("Surface columns");
        var palette = new LinkedHashMap<Integer, Integer>();
        int runs = 0;
        for (var column : columns) {
            if (column == null || column.length < 1 || column.length > MAX_RUNS)
                throw new IllegalArgumentException("Surface run count");
            int previous = Integer.MAX_VALUE;
            for (var run : column) {
                if (run.top() < GroundSurface.LOWEST
                        || run.top() >= GroundChunkSnapshot.MIN_Y + GroundChunkSnapshot.HEIGHT
                        || run.bottom() < GroundSurface.LOWEST
                        || run.bottom() > run.top()
                        || run.top() >= previous
                        || run.state() < 0
                        || run.state() >= Block.BLOCK_STATE_REGISTRY.size())
                    throw new IllegalArgumentException("Surface run");
                previous = run.bottom();
                palette.computeIfAbsent(run.state(), k -> palette.size());
                runs++;
            }
        }
        boolean wide = palette.size() > 256;
        var out = ByteBuffer.allocate(2 + palette.size() * 4 + 256 + runs * (wide ? 6 : 5));
        out.putShort((short) palette.size());
        palette.keySet().forEach(out::putInt);
        for (var column : columns) {
            out.put((byte) column.length);
            for (var run : column) {
                out.putShort((short) run.top()).putShort((short) run.bottom());
                int slot = palette.get(run.state());
                if (wide) out.putShort((short) slot);
                else out.put((byte) slot);
            }
        }
        return out.array();
    }

    static GroundSurface unpack(long revision, byte[] packed) {
        if (packed.length < 1542 || packed.length > MAX_BYTES)
            throw new IllegalArgumentException("Surface byte count");
        try {
            var in = ByteBuffer.wrap(packed);
            int count = Short.toUnsignedInt(in.getShort());
            if (count < 1 || count > 256 * MAX_RUNS)
                throw new IllegalArgumentException("Surface palette length");
            var palette = new int[count];
            for (int i = 0; i < count; i++) {
                palette[i] = in.getInt();
                if (palette[i] < 0 || palette[i] >= Block.BLOCK_STATE_REGISTRY.size())
                    throw new IllegalArgumentException("Surface palette state");
            }
            var columns = new GroundSurface.Run[256][];
            for (int i = 0; i < 256; i++) {
                int n = Byte.toUnsignedInt(in.get());
                if (n < 1 || n > MAX_RUNS) throw new IllegalArgumentException("Surface run count");
                columns[i] = new GroundSurface.Run[n];
                for (int r = 0; r < n; r++) {
                    int top = in.getShort(), bottom = in.getShort();
                    int slot =
                            count > 256
                                    ? Short.toUnsignedInt(in.getShort())
                                    : Byte.toUnsignedInt(in.get());
                    if (slot >= count) throw new IllegalArgumentException("Surface palette slot");
                    columns[i][r] = new GroundSurface.Run(top, bottom, palette[slot]);
                }
            }
            if (in.hasRemaining()) throw new IllegalArgumentException("Surface trailing bytes");
            return new GroundSurface(revision, columns);
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("Surface length", e);
        }
    }

    private GroundSurfaceCodec() {}
}
