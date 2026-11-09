package dev.starfold.ship;

import net.minecraft.core.BlockPos;

/** O(1) resumable chunk/section/cell cursor; caller charges each cell or empty-region skip. */
final class GasDamageScan {
    static final int MAX_VISITS = 256;

    record Domain(int minX, int maxX, int minZ, int maxZ, int minSection, int maxSection) {
        Domain {
            if (minX > maxX
                    || minZ > maxZ
                    || minSection > maxSection
                    || minX < (Integer.MIN_VALUE >> 4)
                    || maxX > (Integer.MAX_VALUE >> 4)
                    || minZ < (Integer.MIN_VALUE >> 4)
                    || maxZ > (Integer.MAX_VALUE >> 4)
                    || minSection < (Integer.MIN_VALUE >> 4)
                    || maxSection > (Integer.MAX_VALUE >> 4))
                throw new IllegalArgumentException("Gas scan domain");
        }
    }

    private Domain domain;
    private int chunkX, chunkZ, section, cell;

    GasDamageScan(Domain domain) {
        reset(domain);
    }

    void reset(Domain next) {
        if (next == null) throw new IllegalArgumentException("Gas scan domain");
        if (next.equals(domain)) return;
        domain = next;
        chunkX = next.minX();
        chunkZ = next.minZ();
        section = next.minSection();
        cell = 0;
    }

    int chunkX() {
        return chunkX;
    }

    int chunkZ() {
        return chunkZ;
    }

    int section() {
        return section;
    }

    BlockPos position() {
        return new BlockPos(
                (chunkX << 4) + (cell & 15),
                (section << 4) + (cell >> 8),
                (chunkZ << 4) + ((cell >> 4) & 15));
    }

    void nextCell() {
        if (++cell == 4096) nextSection();
    }

    void nextSection() {
        cell = 0;
        if (section == domain.maxSection()) nextChunk();
        else section++;
    }

    void nextChunk() {
        cell = 0;
        section = domain.minSection();
        if (chunkX < domain.maxX()) chunkX++;
        else {
            chunkX = domain.minX();
            if (chunkZ < domain.maxZ()) chunkZ++;
            else chunkZ = domain.minZ();
        }
    }
}
