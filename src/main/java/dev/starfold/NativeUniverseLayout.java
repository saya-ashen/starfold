package dev.starfold;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.RegistryFileCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import java.util.List;

/** How a universe is laid out beyond its home system, defined by a data pack
 *  (data/<namespace>/starfold/universe/<name>.json): how many further suns there are, how far apart they
 *  stand, how many planets each has, how far apart those circle, and which types of planet there are and how often.
 *  A world's seed turns a layout into a universe once, when the world is made; see NativeUniverse. */
public record NativeUniverseLayout(int minStars,int maxStars,int minPlanets,int maxPlanets,double gap,double ring,List<Choice> types,double density) {
    /** An endless universe has a sun in each cell of its grid with this chance, found as someone comes near; one
     *  without a density has the number of suns it says, all there from the start. */
    public boolean endless(){return density>0;}
    public static final ResourceKey<Registry<NativeUniverseLayout>> KEY=ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath("starfold","universe"));
    /** A type of planet and how often it is chosen against the others. */
    public record Choice(Holder<NativePlanetType> type,int weight,java.util.Optional<ResourceLocation> typeId){
        public Choice(Holder<NativePlanetType> type,int weight){this(type,weight,java.util.Optional.empty());}
        public java.util.Optional<ResourceLocation> typeId(){return type.unwrapKey().map(ResourceKey::location).or(()->typeId);}
        static final Codec<Choice> CODEC=RecordCodecBuilder.create(i->i.group(savedInPlace(NativePlanetType.KEY,NativePlanetType.CODEC).fieldOf("type").forGetter(Choice::type),Codec.INT.optionalFieldOf("weight",1).forGetter(Choice::weight),ResourceLocation.CODEC.optionalFieldOf("type_id").forGetter(Choice::typeId)).apply(i,Choice::new));
        public Choice{if(type==null||weight<1||weight>1000)throw new IllegalArgumentException("Planet choice weight "+weight);}
    }
    private record Range(int min,int max){static final Codec<Range> CODEC=RecordCodecBuilder.create(i->i.group(Codec.INT.fieldOf("min").forGetter(Range::min),Codec.INT.fieldOf("max").forGetter(Range::max)).apply(i,Range::new));}
    public static final Codec<NativeUniverseLayout> CODEC=RecordCodecBuilder.create(i->i.group(
        Range.CODEC.optionalFieldOf("stars",new Range(0,0)).forGetter(l->new Range(l.minStars,l.maxStars)),
        Range.CODEC.fieldOf("planets").forGetter(l->new Range(l.minPlanets,l.maxPlanets)),
        Codec.DOUBLE.optionalFieldOf("star_gap",NativePlanetSystem.STAR_GAP).forGetter(NativeUniverseLayout::gap),
        Codec.DOUBLE.optionalFieldOf("ring",NativePlanetSystem.RING).forGetter(NativeUniverseLayout::ring),
        Choice.CODEC.listOf().fieldOf("types").forGetter(NativeUniverseLayout::types),
        Codec.doubleRange(0,1).optionalFieldOf("density",0.0).forGetter(NativeUniverseLayout::density)
    ).apply(i,(stars,planets,gap,ring,types,density)->new NativeUniverseLayout(stars.min(),stars.max(),planets.min(),planets.max(),gap,ring,types,density)));
    /** A system names its layout, or carries one written in its place; it is saved written out, so that a world
     *  does not depend on the pack it was made with. */
    public static final Codec<Holder<NativeUniverseLayout>> HOLDER=savedInPlace(KEY,CODEC);
    /** Read as a name or written in place; always saved written in place. */
    static <T> Codec<Holder<T>> savedInPlace(ResourceKey<Registry<T>> key,Codec<T> direct){
        var read=RegistryFileCodec.create(key,direct);
        return new Codec<>(){
            @Override public <D> com.mojang.serialization.DataResult<com.mojang.datafixers.util.Pair<Holder<T>,D>> decode(com.mojang.serialization.DynamicOps<D> ops,D input){return ops.getMap(input).result().isPresent()?direct.decode(ops,input).map(pair->pair.mapFirst(Holder::direct)):read.decode(ops,input);}
            @Override public <D> com.mojang.serialization.DataResult<D> encode(Holder<T> holder,com.mojang.serialization.DynamicOps<D> ops,D prefix){return direct.encode(holder.value(),ops,prefix);}
        };
    }
    public NativeUniverseLayout {
        types=List.copyOf(types);
        if(!(density>=0&&density<=1)||minStars<0||maxStars<minStars||maxStars>64||density>0&&maxStars>0||minPlanets<1||maxPlanets<minPlanets||maxPlanets>8||types.isEmpty())throw new IllegalArgumentException("Universe counts");
        // Planets of neighbouring suns must stay clear of each other, and neighbours on one sun's rings of one another.
        // Suns are never nearer than half a gap: that must hold both their outermost rings and room between them.
        if(!Double.isFinite(ring)||ring<8192||!Double.isFinite(gap)||gap<minimumGap(maxPlanets,ring)||gap>1e6)throw new IllegalArgumentException("Universe distances: suns "+gap+" apart with rings of "+ring+" need at least "+minimumGap(maxPlanets,ring));
    }
    public static double minimumGap(int planets,double ring){return 4*(GroundSolar.defaults().radius()+(planets-1)*ring)+16384;}
    public static void initialize(net.neoforged.bus.api.IEventBus modBus){modBus.addListener((net.neoforged.neoforge.registries.DataPackRegistryEvent.NewRegistry event)->event.dataPackRegistry(KEY,CODEC));}
}
