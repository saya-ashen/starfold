package dev.starfold.atlas;

import java.io.*;
import java.util.BitSet;

/** Compact per-body fog. Only explored 4-block cells unlock the detailed raster. */
public final class SurveyRecord {
    public static final int STEP = 4;
    final int size, width;
    private final BitSet explored = new BitSet();
    private boolean revealed;
    private int revision;
    public SurveyRecord(int size) { this.size = size; width = (size + STEP - 1) / STEP; }
    public int revision() { return revision; }
    public boolean revealed() { return revealed; }
    public boolean explored(int face, int u, int v) { return explored.get((face * width + v) * width + u); }
    public void explore(int face, int u, int v) {
        int index = (face * width + v) * width + u;
        if (!explored.get(index)) { explored.set(index); revision++; }
    }
    public void reveal() { if (!revealed) { revealed = true; revision++; } }
    public void write(DataOutput out) throws IOException {
        out.writeInt(1); out.writeInt(size); out.writeBoolean(revealed);
        byte[] bits = explored.toByteArray(); out.writeInt(bits.length); out.write(bits);
    }
    public static SurveyRecord read(DataInput in, int size) throws IOException {
        if (in.readInt() != 1 || in.readInt() != size) throw new IOException("Survey format/face size");
        var record = new SurveyRecord(size);
        record.revealed = in.readBoolean();
        int length = in.readInt();
        if (length < 0 || length > (6 * record.width * record.width + 7) / 8) throw new IOException("Survey bit count");
        byte[] bits = new byte[length]; in.readFully(bits);
        record.explored.or(BitSet.valueOf(bits));
        if (record.explored.length() > 6 * record.width * record.width) throw new IOException("Survey cell range");
        return record;
    }
}
