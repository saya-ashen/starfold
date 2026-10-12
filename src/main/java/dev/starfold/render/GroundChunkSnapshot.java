package dev.starfold.render;

import dev.starfold.ground.GroundCoordinates;
import dev.starfold.planet.PlanetAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.*;

/** Bounded, display-only native BlockState image. Never a save, placement or inventory ledger. */
public final class GroundChunkSnapshot {
    public static final int MIN_Y = -64, HEIGHT = 384, VALUES = 16 * 16 * HEIGHT;
    private final int face, u, v;
    private final long revision;
    private final int[] palette;
    private final SimpleBitStorage data;
    private final byte[] lights;
    private final String fingerprint;
    private final boolean[] solid;
    private final int top;

    private static byte[] fullSky() {
        var lights = new byte[VALUES];
        Arrays.fill(lights, (byte) 240);
        return lights;
    }

    public GroundChunkSnapshot(
            int face, int u, int v, long revision, int[] palette, long[] packed) {
        this(face, u, v, revision, palette, packed, fullSky());
    }

    public GroundChunkSnapshot(
            int face, int u, int v, long revision, int[] palette, long[] packed, byte[] lights) {
        if (face < 0
                || face >= 6
                || u < 0
                || u >= 128
                || v < 0
                || v >= 128
                || revision < 0
                || palette == null
                || palette.length == 0
                || palette.length > VALUES)
            throw new IllegalArgumentException("Native chunk image header");
        this.face = face;
        this.u = u;
        this.v = v;
        this.revision = revision;
        this.palette = palette.clone();
        var ids = new HashSet<Integer>();
        for (int id : this.palette)
            if (id < 0 || Block.BLOCK_STATE_REGISTRY.byId(id) == null || !ids.add(id))
                throw new IllegalArgumentException("Native chunk palette");
        solid = new boolean[this.palette.length];
        for (int i = 0; i < solid.length; i++) {
            var state = Block.BLOCK_STATE_REGISTRY.byId(this.palette[i]);
            solid[i] =
                    !state.getBlock().hasDynamicShape()
                            && state.isSolidRender(
                                    net.minecraft.world.level.EmptyBlockGetter.INSTANCE,
                                    BlockPos.ZERO);
        }
        int bits = bits(palette.length), words = (VALUES + (64 / bits) - 1) / (64 / bits);
        if (packed == null || packed.length != words)
            throw new IllegalArgumentException("Native chunk image length");
        this.data = new SimpleBitStorage(bits, VALUES, packed.clone());
        var air = new boolean[this.palette.length];
        for (int i = 0; i < air.length; i++)
            air[i] = Block.BLOCK_STATE_REGISTRY.byId(this.palette[i]).isAir();
        int highest = -1;
        for (int i = 0; i < VALUES; i++) {
            int value = data.get(i);
            if (value >= palette.length)
                throw new IllegalArgumentException("Native chunk palette index");
            if (!air[value]) highest = i;
        }
        top = highest < 0 ? MIN_Y - 1 : MIN_Y + highest / 256;
        if (lights == null || lights.length != VALUES)
            throw new IllegalArgumentException("Native light image length");
        this.lights = lights.clone();
        var bytes =
                java.nio.ByteBuffer.allocate(
                        12 + this.palette.length * 4 + data.getRaw().length * 8 + VALUES);
        bytes.putInt(face).putInt(u).putInt(v);
        for (int id : this.palette) bytes.putInt(id);
        for (long word : data.getRaw()) bytes.putLong(word);
        bytes.put(this.lights);
        try {
            fingerprint =
                    HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(bytes.array()));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static int bits(int size) {
        return Math.max(1, 32 - Integer.numberOfLeadingZeros(size - 1));
    }

    public int face() {
        return face;
    }

    public int u() {
        return u;
    }

    public int v() {
        return v;
    }

    public long revision() {
        return revision;
    }

    public long key() {
        return u + 128L * (v + 128L * face);
    }

    /** Highest non-air layer (MIN_Y-1 if empty): meshing an unedited image never scans the sky above it. */
    public int top() {
        return top;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public int memoryBytes() {
        return palette.length * 4 + solid.length + data.getRaw().length * 8 + lights.length;
    }

    public int[] palette() {
        return palette.clone();
    }

    public long[] packed() {
        return data.getRaw().clone();
    }

    public boolean baseline() {
        for (int y = MIN_Y; y < MIN_Y + HEIGHT; y++) {
            var expected =
                    y == -64
                            ? Blocks.BEDROCK.defaultBlockState()
                            : y < 124
                                    ? Blocks.STONE.defaultBlockState()
                                    : y < 127
                                            ? Blocks.DIRT.defaultBlockState()
                                            : y == 127
                                                    ? Blocks.GRASS_BLOCK.defaultBlockState()
                                                    : Blocks.AIR.defaultBlockState();
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) if (state(x, y, z) != expected) return false;
        }
        return true;
    }

    public byte[] lights() {
        return lights.clone();
    }

    public int light(net.minecraft.world.level.LightLayer layer, BlockPos canonical) {
        if (!owns(canonical)) throw new IllegalArgumentException("Light image owner");
        if (canonical.getY() >= MIN_Y + HEIGHT)
            return layer == net.minecraft.world.level.LightLayer.SKY ? 15 : 0;
        if (canonical.getY() < MIN_Y) return 0;
        int bits =
                lights[index(canonical.getX() & 15, canonical.getY(), canonical.getZ() & 15)] & 255;
        return layer == net.minecraft.world.level.LightLayer.SKY ? bits >> 4 : bits & 15;
    }

    private static int index(int x, int y, int z) {
        return x + 16 * (z + 16 * (y - MIN_Y));
    }

    public BlockState state(int x, int y, int z) {
        if (x < 0 || x >= 16 || z < 0 || z >= 16 || y < MIN_Y || y >= MIN_Y + HEIGHT)
            return Blocks.AIR.defaultBlockState();
        return Block.BLOCK_STATE_REGISTRY.byId(palette[data.get(index(x, y, z))]);
    }

    /** Skip only sealed interior voxels; never skip the chunk boundary, cave walls or dynamic shapes. */
    public boolean enclosed(int x, int y, int z) {
        if (x <= 0 || x >= 15 || z <= 0 || z >= 15 || y <= MIN_Y || y >= MIN_Y + HEIGHT - 1)
            return false;
        int i = index(x, y, z);
        return solid[data.get(i)]
                && solid[data.get(i - 1)]
                && solid[data.get(i + 1)]
                && solid[data.get(i - 16)]
                && solid[data.get(i + 16)]
                && solid[data.get(i - 256)]
                && solid[data.get(i + 256)];
    }

    public boolean owns(BlockPos canonical) {
        return canonical.getX() >> 4 == face * GroundCoordinates.STRIDE / 16 + u
                && canonical.getZ() >> 4 == v;
    }

    public BlockState state(BlockPos canonical) {
        if (!owns(canonical)) throw new IllegalArgumentException("Image owner mismatch");
        return state(canonical.getX() & 15, canonical.getY(), canonical.getZ() & 15);
    }

    public static GroundChunkSnapshot capture(LevelChunk chunk, long revision) {
        int x = chunk.getPos().x * 16, z = chunk.getPos().z * 16;
        var access = PlanetAccess.of(chunk.getLevel());
        int face = access.face(x);
        var origin = access.originAt(x, z);
        if (!access.shape(x, z).owned(x - origin.x(), z - origin.z()))
            throw new IllegalArgumentException("Capture only canonical native chunks");
        var values = new int[VALUES];
        var lights = new byte[VALUES];
        var pos = new BlockPos.MutableBlockPos();
        var engine = chunk.getLevel().getLightEngine();
        var sky = engine.getLayerListener(net.minecraft.world.level.LightLayer.SKY);
        var block = engine.getLayerListener(net.minecraft.world.level.LightLayer.BLOCK);
        // Native owner sections need neither topology remapping nor 196K Level light wrappers.
        for (int sy = MIN_Y; sy < MIN_Y + HEIGHT; sy += 16) {
            var section = chunk.getSection(chunk.getSectionIndex(sy));
            var at = net.minecraft.core.SectionPos.of(chunk.getPos(), sy >> 4);
            var skyData = sky.getDataLayerData(at);
            var blockData = block.getDataLayerData(at);
            for (int y = sy; y < sy + 16; y++)
                for (int a = 0; a < 16; a++)
                    for (int b = 0; b < 16; b++) {
                        int i = index(a, y, b);
                        values[i] =
                                Block.BLOCK_STATE_REGISTRY.getId(
                                        section.getBlockState(a, y & 15, b));
                        pos.set(x + a, y, z + b);
                        int
                                s =
                                        skyData == null
                                                ? sky.getLightValue(pos)
                                                : skyData.get(a, y & 15, b),
                                l =
                                        blockData == null
                                                ? block.getLightValue(pos)
                                                : blockData.get(a, y & 15, b);
                        lights[i] = (byte) (s * 16 + l);
                    }
        }
        var base =
                fromStates(
                        face,
                        (int) (x - origin.x()) / 16,
                        (int) (z - origin.z()) / 16,
                        revision,
                        values);
        return new GroundChunkSnapshot(
                base.face, base.u, base.v, revision, base.palette, base.data.getRaw(), lights);
    }

    public static GroundChunkSnapshot fromStates(
            int face, int u, int v, long revision, int[] states) {
        if (states == null || states.length != VALUES)
            throw new IllegalArgumentException("Native state count");
        var indices = new LinkedHashMap<Integer, Integer>();
        int seen = -1;
        for (int id : states) {
            if (id == seen) continue;
            if (id < 0 || Block.BLOCK_STATE_REGISTRY.byId(id) == null)
                throw new IllegalArgumentException("Native block state id");
            indices.computeIfAbsent(id, key -> indices.size());
            seen = id;
        }
        var packed = new SimpleBitStorage(bits(indices.size()), VALUES);
        int last = -1, index = 0;
        for (int i = 0; i < VALUES; i++) {
            if (states[i] != last) {
                last = states[i];
                index = indices.get(last);
            }
            packed.set(i, index);
        }
        var palette = indices.keySet().stream().mapToInt(Integer::intValue).toArray();
        return new GroundChunkSnapshot(face, u, v, revision, palette, packed.getRaw());
    }

    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeByte(face);
        buffer.writeByte(u);
        buffer.writeByte(v);
        buffer.writeVarLong(revision);
        buffer.writeVarIntArray(palette);
        buffer.writeLongArray(data.getRaw());
        buffer.writeByteArray(lights);
    }

    public static GroundChunkSnapshot read(RegistryFriendlyByteBuf buffer) {
        int face = buffer.readUnsignedByte(),
                u = buffer.readUnsignedByte(),
                v = buffer.readUnsignedByte();
        long revision = buffer.readVarLong();
        int[] palette = buffer.readVarIntArray(VALUES);
        long[] packed = buffer.readLongArray(new long[0], (VALUES + 2) / 3);
        return new GroundChunkSnapshot(
                face, u, v, revision, palette, packed, buffer.readByteArray(VALUES));
    }
}
