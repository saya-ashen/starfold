package dev.starfold;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** Saved generator policy, not world contents. Unknown algorithms fail closed. */
public record NativePlanetGeneration(String type,String algorithm,int algorithmVersion,double relief,double oreBias,double caveWidth,int size,int depth,String shell,NativePlanetPalette palette,NativePlanetMinerals minerals,NativePlanetClimate climate,boolean creatures,boolean features,boolean structures) {
    /** A planet saved before it was said has neither the features of its biomes nor structures. */
    public NativePlanetGeneration(String type,String algorithm,int algorithmVersion,double relief,double oreBias,double caveWidth,int size,int depth,String shell,NativePlanetPalette palette,NativePlanetMinerals minerals,NativePlanetClimate climate,boolean creatures){this(type,algorithm,algorithmVersion,relief,oreBias,caveWidth,size,depth,shell,palette,minerals,climate,creatures,false,false);}
    /** A planet saved before it was said whether anything lives on it has the creatures of its biomes. */
    public NativePlanetGeneration(String type,String algorithm,int algorithmVersion,double relief,double oreBias,double caveWidth,int size,int depth,String shell,NativePlanetPalette palette,NativePlanetMinerals minerals,NativePlanetClimate climate){this(type,algorithm,algorithmVersion,relief,oreBias,caveWidth,size,depth,shell,palette,minerals,climate,true);}
    /** A planet saved before climates were chosen is mild. */
    public NativePlanetGeneration(String type,String algorithm,int algorithmVersion,double relief,double oreBias,double caveWidth,int size,int depth,String shell,NativePlanetPalette palette,NativePlanetMinerals minerals){this(type,algorithm,algorithmVersion,relief,oreBias,caveWidth,size,depth,shell,palette,minerals,NativePlanetClimate.MILD);}
    /** A planet saved before minerals were chosen has those of its kind. */
    public NativePlanetGeneration(String type,String algorithm,int algorithmVersion,double relief,double oreBias,double caveWidth,int size,int depth,String shell,NativePlanetPalette palette){this(type,algorithm,algorithmVersion,relief,oreBias,caveWidth,size,depth,shell,palette,NativePlanetMinerals.NONE);}
    /** A planet saved before palettes were chosen is plain rock. */
    public NativePlanetGeneration(String type,String algorithm,int algorithmVersion,double relief,double oreBias,double caveWidth,int size,int depth,String shell){this(type,algorithm,algorithmVersion,relief,oreBias,caveWidth,size,depth,shell,NativePlanetPalette.ROCK);}
    /** Blocks from the nominal surface down to the floor nobody digs through, as every planet had before depths were
     *  saved: the vanilla range, whatever the planet's size. */
    public static final int STANDARD_DEPTH=NativePlanetTerrain.SEA_LEVEL-1-NativePlanetTerrain.MIN_Y;
    /** What the floor is made of: bedrock, as every planet had before floors were chosen, or the planet's own core shell. */
    public static final String BEDROCK="bedrock",CORE_SHELL="core_shell";
    /** A planet saved before floors were chosen has a bedrock floor. */
    public NativePlanetGeneration(String type,String algorithm,int algorithmVersion,double relief,double oreBias,double caveWidth,int size,int depth){this(type,algorithm,algorithmVersion,relief,oreBias,caveWidth,size,depth,BEDROCK);}
    /** A planet saved before depths were chosen is the standard depth. */
    public NativePlanetGeneration(String type,String algorithm,int algorithmVersion,double relief,double oreBias,double caveWidth,int size){this(type,algorithm,algorithmVersion,relief,oreBias,caveWidth,size,STANDARD_DEPTH);}
    /** A planet saved before sizes were chosen is the standard size. */
    public NativePlanetGeneration(String type,String algorithm,int algorithmVersion,double relief,double oreBias,double caveWidth){this(type,algorithm,algorithmVersion,relief,oreBias,caveWidth,NativePlanetShape.STANDARD.size());}
    public static final Codec<NativePlanetGeneration> CODEC=RecordCodecBuilder.create(i->i.group(
        Codec.STRING.fieldOf("type").forGetter(NativePlanetGeneration::type),
        Codec.STRING.fieldOf("algorithm").forGetter(NativePlanetGeneration::algorithm),
        Codec.INT.fieldOf("algorithm_version").forGetter(NativePlanetGeneration::algorithmVersion),
        Codec.doubleRange(.25,2).fieldOf("relief").forGetter(NativePlanetGeneration::relief),
        Codec.doubleRange(-.1,.1).fieldOf("ore_bias").forGetter(NativePlanetGeneration::oreBias),
        Codec.doubleRange(.03,.2).fieldOf("cave_width").forGetter(NativePlanetGeneration::caveWidth),
        Codec.INT.optionalFieldOf("size",NativePlanetShape.STANDARD.size()).forGetter(NativePlanetGeneration::size),
        Codec.INT.optionalFieldOf("depth",STANDARD_DEPTH).forGetter(NativePlanetGeneration::depth),
        Codec.STRING.optionalFieldOf("shell",BEDROCK).forGetter(NativePlanetGeneration::shell),
        NativePlanetPalette.CODEC.optionalFieldOf("palette",NativePlanetPalette.ROCK).forGetter(NativePlanetGeneration::palette),
        NativePlanetMinerals.CODEC.optionalFieldOf("minerals",NativePlanetMinerals.NONE).forGetter(NativePlanetGeneration::minerals),
        NativePlanetClimate.CODEC.optionalFieldOf("climate",NativePlanetClimate.MILD).forGetter(NativePlanetGeneration::climate),
        Codec.BOOL.optionalFieldOf("creatures",true).forGetter(NativePlanetGeneration::creatures),
        Codec.BOOL.optionalFieldOf("features",false).forGetter(NativePlanetGeneration::features),
        Codec.BOOL.optionalFieldOf("structures",false).forGetter(NativePlanetGeneration::structures)
    ).apply(i,NativePlanetGeneration::new));
    public NativePlanetGeneration {
        if(type==null||algorithm==null||algorithmVersion!=1||!Double.isFinite(relief)||relief<.25||relief>2||!Double.isFinite(oreBias)||oreBias<-.1||oreBias>.1||!Double.isFinite(caveWidth)||caveWidth<.03||caveWidth>.2)throw new IllegalArgumentException("Native generation policy");
        if(depth<STANDARD_DEPTH||depth>new NativePlanetShape(size).radius()*.75)throw new IllegalArgumentException("Planet depth "+depth);
        if(!BEDROCK.equals(shell)&&!CORE_SHELL.equals(shell))throw new IllegalArgumentException("Planet shell "+shell);
        if(palette==null||!palette.equals(NativePlanetPalette.ROCK)&&!(type.equals("rocky")&&algorithm.equals("rocky_craters")))throw new IllegalArgumentException("Only a cratered rocky planet has a palette of its own");
        if(minerals==null||!minerals.equals(NativePlanetMinerals.NONE)&&!(type.equals("rocky")?algorithm.equals("rocky_craters"):algorithm.equals("ecology")||algorithm.equals("mining")))throw new IllegalArgumentException("This kind of planet has no minerals of its own");
        if(climate==null||!climate.equals(NativePlanetClimate.MILD)&&!algorithm.equals("ecology"))throw new IllegalArgumentException("Only a planet with regions has a climate of its own");
        if(type.equals("rocky")? !java.util.Set.of("rocky","rocky_craters").contains(algorithm) : !type.equals("temperate")||!java.util.Set.of("surface","mining","ecology").contains(algorithm)||relief!=1||oreBias!=0||caveWidth!=.115)throw new IllegalArgumentException("Unsupported native generation algorithm/parameters");
    }
    public static NativePlanetGeneration defaults(int version){return switch(version){
        case 2 -> temperate("surface");case 3 -> temperate("mining");case 4 -> temperate("ecology");
        case 5 -> new NativePlanetGeneration("rocky","rocky",1,1,.035,.085);
        default -> throw new IllegalArgumentException("Unsupported native terrain version: "+version);
    };}
    private static NativePlanetGeneration temperate(String algorithm){return new NativePlanetGeneration("temperate",algorithm,1,1,0,.115);}
    public void validate(int terrainVersion){var expected=defaults(terrainVersion);if(!type.equals(expected.type)||!(algorithm.equals(expected.algorithm)||terrainVersion==5&&exploration()))throw new IllegalArgumentException("Native algorithm/terrain version mismatch");}
    public boolean exploration(){return type.equals("rocky")&&algorithm.equals("rocky_craters");}
    public boolean rocky(){return type.equals("rocky");}
    public NativePlanetGeneration withClimate(NativePlanetClimate climate){return new NativePlanetGeneration(type,algorithm,algorithmVersion,relief,oreBias,caveWidth,size,depth,shell,palette,minerals,climate,creatures,features,structures);}
    public boolean coreShell(){return shell.equals(CORE_SHELL);}
    public NativePlanetShape shape(){return new NativePlanetShape(size);}
    /** The lowest layer of the planet: the floor itself. Nothing of the planet is generated below it. */
    public int floor(){return NativePlanetTerrain.SEA_LEVEL-1-depth;}
}
