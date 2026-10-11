package dev.starfold;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import java.util.Optional;

/** What a rocky planet is made of: its rock and the darker and lighter rock of its regions, the rock of its depths,
 *  and a cover over its surface if it has one. Saved with a planet's generation, so a world keeps what it was made of. */
public record NativePlanetPalette(Block rock,Block dark,Block light,Block deep,Optional<Block> cover) {
    /** Every rocky planet before palettes were saved. */
    public static final NativePlanetPalette ROCK=new NativePlanetPalette(Blocks.STONE,Blocks.BASALT,Blocks.ANDESITE,Blocks.DEEPSLATE,Optional.empty());
    private static final Codec<Block> BLOCK=BuiltInRegistries.BLOCK.byNameCodec();
    public static final Codec<NativePlanetPalette> CODEC=RecordCodecBuilder.create(i->i.group(
        BLOCK.fieldOf("rock").forGetter(NativePlanetPalette::rock),
        BLOCK.fieldOf("dark").forGetter(NativePlanetPalette::dark),
        BLOCK.fieldOf("light").forGetter(NativePlanetPalette::light),
        BLOCK.fieldOf("deep").forGetter(NativePlanetPalette::deep),
        BLOCK.optionalFieldOf("cover").forGetter(NativePlanetPalette::cover)
    ).apply(i,NativePlanetPalette::new));
    public NativePlanetPalette {
        if(rock==null||dark==null||light==null||deep==null||cover==null)throw new IllegalArgumentException("Planet palette");
        for(var block:new Block[]{rock,dark,light,deep,cover.orElse(rock)})if(block.defaultBlockState().isAir()||!block.defaultBlockState().getFluidState().isEmpty())throw new IllegalArgumentException("A planet is made of solid blocks, not "+BuiltInRegistries.BLOCK.getKey(block));
    }
    public void write(FriendlyByteBuf buffer){for(var block:new Block[]{rock,dark,light,deep})buffer.writeResourceLocation(BuiltInRegistries.BLOCK.getKey(block));buffer.writeBoolean(cover.isPresent());if(cover.isPresent())buffer.writeResourceLocation(BuiltInRegistries.BLOCK.getKey(cover.get()));}
    public static NativePlanetPalette read(FriendlyByteBuf buffer){var rock=block(buffer.readResourceLocation());var dark=block(buffer.readResourceLocation());var light=block(buffer.readResourceLocation());var deep=block(buffer.readResourceLocation());return new NativePlanetPalette(rock,dark,light,deep,buffer.readBoolean()?Optional.of(block(buffer.readResourceLocation())):Optional.empty());}
    private static Block block(ResourceLocation id){return BuiltInRegistries.BLOCK.getOptional(id).orElseThrow(()->new IllegalArgumentException("Unknown palette block "+id));}
}
