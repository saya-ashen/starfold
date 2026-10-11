package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Rocky algorithm v1: dry ridges, protected caves and iron-rich veins in actual chunks. */
public final class NativePlanetRock {
    private final NativePlanetTerrain terrain;private final NativePlanetGeneration policy;private final NativePlanetCraters craters;private final BlockState rock,dark,light,deep,cover;private final NativePlanetMinerals.Table minerals;
    public NativePlanetRock(NativePlanetTerrain terrain){this.terrain=terrain;policy=terrain.generation();craters=policy.exploration()?new NativePlanetCraters(terrain.seed()):null;var palette=policy.palette();rock=palette.rock().defaultBlockState();dark=palette.dark().defaultBlockState();light=palette.light().defaultBlockState();deep=palette.deep().defaultBlockState();cover=palette.cover().map(net.minecraft.world.level.block.Block::defaultBlockState).orElse(null);minerals=new NativePlanetMinerals.Table(policy.minerals());}
    public double elevation(Point p){double broad=terrain.field(p,3.2,501),ridge=1-Math.abs(terrain.field(p,8.5,502));double base=broad*16+ridge*ridge*32-9+terrain.field(p,32,503)*3;return Math.clamp((base+(craters==null?0:craters.elevation(p)))*policy.relief(),-24,48);}
    public BlockState surface(Point direction,int ground,int y){
        if(!policy.exploration())return rock;
        double region=terrain.field(direction,10,541),band=Math.abs(terrain.field(direction,85,542));
        if(region>.4&&band<.13)return (region>.65?Blocks.COPPER_ORE:Blocks.IRON_ORE).defaultBlockState();
        // A cover lies over the rock of every region; the mineral bands still break through it.
        return cover!=null?cover:region<-.25?dark:region>.2?light:rock;
    }
    public boolean cave(Point p,int ground,int y){return cave(NativePlanetTerrain.lattices(),p,ground,y);}
    boolean cave(NativePlanetTerrain.Lattice[] cells,Point p,int ground,int y){if(y<=terrain.floor()+4||y>ground-6)return false;return Math.abs(terrain.noise(cells,p,1.0/30,511))<policy.caveWidth()&&Math.abs(terrain.noise(cells,p,1.0/42,512))<policy.caveWidth();}
    public BlockState underground(Point p,int ground,int y){return underground(NativePlanetTerrain.lattices(),p,ground,y);}
    BlockState underground(NativePlanetTerrain.Lattice[] cells,Point p,int ground,int y){if(cave(cells,p,ground,y))return Blocks.AIR.defaultBlockState();y=terrain.layer(y);boolean deep=y<minerals.deepLine;
        if(minerals.ore.length>0){for(int i=0;i<minerals.ore.length;i++)if(y<=minerals.top[i]&&y>=minerals.bottom[i]&&ore(cells,p,521+i,minerals.rarity[i]))return deep?minerals.deepOre[i]:minerals.ore[i];}
        else if(ore(cells,p,521,.82))return deep?Blocks.DEEPSLATE_IRON_ORE.defaultBlockState():Blocks.IRON_ORE.defaultBlockState();
        else if(y>=0&&ore(cells,p,522,.88))return Blocks.COAL_ORE.defaultBlockState();
        else if(y>=32&&y<=112&&ore(cells,p,523,.87))return Blocks.COPPER_ORE.defaultBlockState();
        else if(y<=32&&ore(cells,p,524,.90))return deep?Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState():Blocks.GOLD_ORE.defaultBlockState();
        else if(y<=8&&ore(cells,p,525,.87))return deep?Blocks.DEEPSLATE_REDSTONE_ORE.defaultBlockState():Blocks.REDSTONE_ORE.defaultBlockState();
        else if(y<=40&&ore(cells,p,526,.92))return deep?Blocks.DEEPSLATE_LAPIS_ORE.defaultBlockState():Blocks.LAPIS_ORE.defaultBlockState();
        else if(y<=-16&&ore(cells,p,527,.96))return Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState();
        var stratum=minerals.rock(y);return stratum!=null?stratum:deep?this.deep:rock;
    }
    private boolean ore(NativePlanetTerrain.Lattice[] cells,Point p,long salt,double threshold){return terrain.noise(cells,p,1.0/5,salt)>threshold-policy.oreBias();}
}
