package dev.starfold;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.starfold.BentStripGeometry.Point;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.List;
import java.util.Optional;

/** What lies under a planet's surface: its strata from the top down and the ores in them. Either list left empty is
 *  what its kind of planet has always had. Depths are written as shares of the planet's depth, 0 at its nominal
 *  surface and 1 at its floor, so that one table suits a planet of any size; they are kept as the layers of the
 *  standard depth (127 down to -64) that every planet's own layers are stretched over. Shallow andesite is an
 *  independent opt-in stone patch policy in actual blocks below local ground; it does not replace either list. */
public record NativePlanetMinerals(List<Ore> ores,List<Stratum> strata,boolean shallowAndesite) {
    /** Missing in saved policies means no patches, including chunks generated later in that world. */
    public NativePlanetMinerals(List<Ore> ores,List<Stratum> strata){this(ores,strata,false);}
    static final int SHALLOW_DEPTH=43;
    public static final NativePlanetMinerals NONE=new NativePlanetMinerals(List.of(),List.of());
    public static final int SURFACE=NativePlanetTerrain.SEA_LEVEL-1,FLOOR=SURFACE-NativePlanetGeneration.STANDARD_DEPTH,MAX_ORES=16,MAX_STRATA=8;
    static int layer(double share){return (int)Math.round(SURFACE-share*NativePlanetGeneration.STANDARD_DEPTH);}
    static double share(int layer){return (SURFACE-layer)/(double)NativePlanetGeneration.STANDARD_DEPTH;}
    private static final Codec<Block> BLOCK=BuiltInRegistries.BLOCK.byNameCodec();
    private static final Codec<Double> SHARE=Codec.doubleRange(0,1);
    /** An ore between two layers, the higher first: the rarer, the nearer its rarity is to 1. Below the first stratum
     *  it is its deep form, if it has one. Earlier ores are found first where two would lie in one block. */
    public record Ore(Block block,Optional<Block> deep,int top,int bottom,double rarity){
        static final Codec<Ore> CODEC=RecordCodecBuilder.create(i->i.group(
            BLOCK.fieldOf("block").forGetter(Ore::block),BLOCK.optionalFieldOf("deep").forGetter(Ore::deep),
            SHARE.optionalFieldOf("from",0.0).forGetter(o->share(o.top)),SHARE.optionalFieldOf("to",1.0).forGetter(o->share(o.bottom)),
            Codec.doubleRange(.5,.999).fieldOf("rarity").forGetter(Ore::rarity)
        ).apply(i,(block,deep,from,to,rarity)->new Ore(block,deep,layer(from),layer(to),rarity)));
        public Ore{if(block==null||deep==null||top<bottom||top>SURFACE||bottom<FLOOR||!(rarity>=.5&&rarity<=.999))throw new IllegalArgumentException("Planet ore");solid(block);deep.ifPresent(NativePlanetMinerals::solid);}
    }
    /** The rock from a layer down to the next stratum. The first begins at the surface and is also the rock of
     *  whatever stands above it. */
    public record Stratum(Block block,int from){
        static final Codec<Stratum> CODEC=RecordCodecBuilder.create(i->i.group(BLOCK.fieldOf("block").forGetter(Stratum::block),SHARE.optionalFieldOf("from",0.0).forGetter(s->share(s.from))).apply(i,(block,from)->new Stratum(block,layer(from))));
        public Stratum{if(block==null||from>SURFACE||from<FLOOR)throw new IllegalArgumentException("Planet stratum");solid(block);}
    }
    public static final Codec<NativePlanetMinerals> CODEC=RecordCodecBuilder.create(i->i.group(
        Ore.CODEC.listOf().optionalFieldOf("ores",List.of()).forGetter(NativePlanetMinerals::ores),
        Stratum.CODEC.listOf().optionalFieldOf("strata",List.of()).forGetter(NativePlanetMinerals::strata),
        Codec.BOOL.optionalFieldOf("shallow_andesite",false).forGetter(NativePlanetMinerals::shallowAndesite)
    ).apply(i,NativePlanetMinerals::new));
    public NativePlanetMinerals {
        ores=List.copyOf(ores);strata=List.copyOf(strata);if(ores.size()>MAX_ORES||strata.size()>MAX_STRATA)throw new IllegalArgumentException("Planet minerals: too many ores or strata");
        if(!strata.isEmpty()&&strata.get(0).from()!=SURFACE)throw new IllegalArgumentException("The first stratum begins at the surface");
        for(int at=1;at<strata.size();at++)if(strata.get(at).from()>=strata.get(at-1).from())throw new IllegalArgumentException("Strata are written from the top down");
    }
    /** Ordinary stone only, after ores/caves/soil have been decided. Actual blocks below local ground, not the
     *  normalized ore layers: forty stone layers beneath the four-block soil cap, also exposed on bare slopes.
     *  One smooth 3D field in planet coordinates, with no face, chunk or generation-order salt. */
    BlockState shallow(NativePlanetTerrain terrain,NativePlanetTerrain.Lattice[] cells,Point direction,int ground,int y,BlockState state){
        if(!shallowAndesite||!state.is(Blocks.STONE)||y<=terrain.floor()||y>ground||y<ground-SHALLOW_DEPTH)return state;
        return terrain.noise(cells,direction.scale(terrain.shape().half()+y-128),1.0/16,231)>.8?Blocks.ANDESITE.defaultBlockState():state;
    }
    private static void solid(Block block){if(block.defaultBlockState().isAir()||!block.defaultBlockState().getFluidState().isEmpty())throw new IllegalArgumentException("A planet is made of solid blocks, not "+BuiltInRegistries.BLOCK.getKey(block));}
    public void write(FriendlyByteBuf buffer){
        buffer.writeVarInt(ores.size());for(var ore:ores){buffer.writeResourceLocation(BuiltInRegistries.BLOCK.getKey(ore.block()));buffer.writeBoolean(ore.deep().isPresent());if(ore.deep().isPresent())buffer.writeResourceLocation(BuiltInRegistries.BLOCK.getKey(ore.deep().get()));buffer.writeVarInt(ore.top()-FLOOR);buffer.writeVarInt(ore.bottom()-FLOOR);buffer.writeDouble(ore.rarity());}
        buffer.writeVarInt(strata.size());for(var stratum:strata){buffer.writeResourceLocation(BuiltInRegistries.BLOCK.getKey(stratum.block()));buffer.writeVarInt(stratum.from()-FLOOR);}buffer.writeBoolean(shallowAndesite);
    }
    public static NativePlanetMinerals read(FriendlyByteBuf buffer){
        int count=buffer.readVarInt();if(count<0||count>MAX_ORES)throw new IllegalArgumentException("Planet minerals");var ores=new java.util.ArrayList<Ore>();
        for(int i=0;i<count;i++){var block=block(buffer);Optional<Block> deep=buffer.readBoolean()?Optional.of(block(buffer)):Optional.empty();ores.add(new Ore(block,deep,buffer.readVarInt()+FLOOR,buffer.readVarInt()+FLOOR,buffer.readDouble()));}
        count=buffer.readVarInt();if(count<0||count>MAX_STRATA)throw new IllegalArgumentException("Planet minerals");var strata=new java.util.ArrayList<Stratum>();for(int i=0;i<count;i++)strata.add(new Stratum(block(buffer),buffer.readVarInt()+FLOOR));
        return new NativePlanetMinerals(ores,strata,buffer.readBoolean());
    }
    private static Block block(FriendlyByteBuf buffer){var id=buffer.readResourceLocation();return BuiltInRegistries.BLOCK.getOptional(id).orElseThrow(()->new IllegalArgumentException("Unknown mineral block "+id));}
    /** Read by the generators for every block of a column: the tables as arrays of states. */
    static final class Table {
        final net.minecraft.world.level.block.state.BlockState[] ore,deepOre,rock;final int[] top,bottom,from;final double[] rarity;final int deepLine;
        Table(NativePlanetMinerals minerals){
            int n=minerals.ores.size();ore=new net.minecraft.world.level.block.state.BlockState[n];deepOre=new net.minecraft.world.level.block.state.BlockState[n];top=new int[n];bottom=new int[n];rarity=new double[n];
            for(int i=0;i<n;i++){var o=minerals.ores.get(i);ore[i]=o.block().defaultBlockState();deepOre[i]=o.deep().orElse(o.block()).defaultBlockState();top[i]=o.top();bottom[i]=o.bottom();rarity[i]=o.rarity();}
            int m=minerals.strata.size();rock=new net.minecraft.world.level.block.state.BlockState[m];from=new int[m];for(int i=0;i<m;i++){rock[i]=minerals.strata.get(i).block().defaultBlockState();from[i]=minerals.strata.get(i).from();}
            deepLine=m>1?from[1]:0;
        }
        /** The stratum a layer lies in, or null where the planet has no strata of its own. */
        net.minecraft.world.level.block.state.BlockState rock(int layer){if(rock.length==0)return null;int at=0;while(at+1<rock.length&&layer<from[at+1])at++;return rock[at];}
    }
}
