package dev.starfold.chart;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.nbt.CompoundTag;

/** Lifetime identity, independent of catalog order and surface storage address. */
public record BodyId(int region, int index) {
    public static final Codec<BodyId> CODEC =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            Codec.intRange(0, Integer.MAX_VALUE)
                                                    .fieldOf("region")
                                                    .forGetter(BodyId::region),
                                            Codec.intRange(0, Integer.MAX_VALUE)
                                                    .fieldOf("index")
                                                    .forGetter(BodyId::index))
                                    .apply(i, BodyId::new));

    public BodyId {
        if (region < 0 || index < 0) throw new IllegalArgumentException("Body identity");
    }

    public CompoundTag tag() {
        var tag = new CompoundTag();
        tag.putInt("region", region);
        tag.putInt("index", index);
        return tag;
    }

    public static BodyId read(CompoundTag tag) {
        if (!tag.contains("region", 3) || !tag.contains("index", 3))
            throw new IllegalArgumentException("Body identity fields");
        return new BodyId(tag.getInt("region"), tag.getInt("index"));
    }
}
