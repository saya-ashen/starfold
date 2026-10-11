package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

/** The anomaly that stands on each three-face corner, where the cube's 270° cannot close: the look of its
 *  core and the ground it reshapes around itself. New terrain has a real immutable closure beneath the virtual mask. */
public final class NativeAnomaly {
    /** The closure's shell and lit seams, then the field's ground and rings. The first two are only ever drawn as the
     *  closure; all four are real blocks, so the field is ordinary chunk content that can be mined and built on. */
    private static final DeferredRegister.Blocks BLOCKS=DeferredRegister.createBlocks("starfold");
    private static final DeferredRegister.Items ITEMS=DeferredRegister.createItems("starfold");
    public static Block SHELL_BLOCK,GLOW_BLOCK,GROUND_BLOCK,RING_BLOCK,CORE_BLOCK;
    private static Block block(String name,int light,MapColor color){
        var id=ResourceLocation.fromNamespaceAndPath("starfold",name);var made=new Block(BlockBehaviour.Properties.of().mapColor(color).strength(3.5f,1200).sound(SoundType.NETHERITE_BLOCK).lightLevel(s->light).noLootTable());
        return made;
    }
    /** Actual block fields become available at block registration, before world generation. */
    public static void initialize(IEventBus modBus){
        BLOCKS.register("anomaly_shell",()->SHELL_BLOCK=block("anomaly_shell",0,MapColor.COLOR_BLACK));BLOCKS.register("anomaly_glow",()->GLOW_BLOCK=block("anomaly_glow",15,MapColor.DIAMOND));BLOCKS.register("anomaly_ground",()->GROUND_BLOCK=block("anomaly_ground",0,MapColor.DEEPSLATE));BLOCKS.register("anomaly_ring",()->RING_BLOCK=block("anomaly_ring",10,MapColor.DIAMOND));
        ITEMS.register("anomaly_shell",()->new BlockItem(SHELL_BLOCK,new Item.Properties()));ITEMS.register("anomaly_glow",()->new BlockItem(GLOW_BLOCK,new Item.Properties()));ITEMS.register("anomaly_ground",()->new BlockItem(GROUND_BLOCK,new Item.Properties()));ITEMS.register("anomaly_ring",()->new BlockItem(RING_BLOCK,new Item.Properties()));
        BLOCKS.register("anomaly_core",()->CORE_BLOCK=new NativeAnomalyBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(-1,3600000).sound(SoundType.NETHERITE_BLOCK).lightLevel(s->s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)?15:0).pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK).isValidSpawn((s,l,p,e)->false).noLootTable()));
        ITEMS.register("anomaly_core",()->new BlockItem(CORE_BLOCK,new Item.Properties()));
        BLOCKS.register(modBus);ITEMS.register(modBus);
    }
    public static BlockState core(int y){return CORE_BLOCK.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT,band(y-NativeGroundCoordinates.FLOOR));}
    public static BlockState shell(){return SHELL_BLOCK.defaultBlockState();}
    public static BlockState glow(){return GLOW_BLOCK.defaultBlockState();}
    /** Vertex tints, RGB. The shell's texture is its own colour; lit seams and the spire share one light. */
    public static final int SHELL_TINT=0xffffff,GLOW_TINT=0x78f0ff;
    /** A lit seam of BAND_HEIGHT blocks closes every BAND blocks of the shell. */
    public static final int BAND=16,BAND_HEIGHT=1;
    /** Each face draws its quarter of the spire of light that rises from the true vertex above the closure:
     *  BEAM_WIDTH blocks along each edge at its foot, narrowing to nothing BEAM_HEIGHT higher. Near and far
     *  views build it from these same numbers, so it is one landmark from the ground and from orbit. */
    public static final int BEAM_WIDTH=12,BEAM_HEIGHT=640;
    /** Longest edge of any quad of the near mesh. */
    public static final int QUAD=4;
    public static double beamWidth(double above){return BEAM_WIDTH*Math.clamp(1-above/BEAM_HEIGHT,0,1);}
    /** Angle on the sphere that one block along a face edge covers at a vertex; distances from a vertex are
     *  counted in these edge-equivalent blocks so that all three faces measure the same circle. */
    public static final double BLOCK_ANGLE=Math.sqrt(2)/(3*1024);
    /** Terrain is drawn to a level shelf inside PLATEAU_INNER and is untouched beyond PLATEAU_OUTER. */
    public static final double PLATEAU=14,PLATEAU_INNER=40,PLATEAU_OUTER=88;
    /** The reshaped field: its own ground within FIELD, a lit ring every RING blocks, nothing growing within CLEAR. */
    public static final double FIELD=48,CLEAR=56;public static final int RING=8,DEPTH=3;
    public static BlockState ground(){return GROUND_BLOCK.defaultBlockState();}
    public static BlockState ring(){return RING_BLOCK.defaultBlockState();}
    /** The ground is wrung around the vertex: a direction at the rim of the field is where it is, and one nearer the
     *  vertex takes its terrain from up to TWIST radians further round. Relief of up to WARP blocks rises with it. */
    public static final double TWIST=5,WARP=14;
    public static double twist(double distance){double t=Math.clamp(1-distance/PLATEAU_OUTER,0,1);return TWIST*t*t;}
    public static double warp(double distance){double t=Math.clamp(1-distance/PLATEAU_OUTER,0,1);return WARP*t*Math.sqrt(t);}
    /** The direction turned about its nearest vertex by twist(distance); its distance from the vertex is unchanged. */
    public static Point twisted(Point unit,double distance){
        double k=1/Math.sqrt(3),ax=Math.copySign(k,unit.x()),ay=Math.copySign(k,unit.y()),az=Math.copySign(k,unit.z()),angle=twist(distance),c=Math.cos(angle),s=Math.sin(angle),dot=(ax*unit.x()+ay*unit.y()+az*unit.z())*(1-c);
        return new Point(unit.x()*c+(ay*unit.z()-az*unit.y())*s+ax*dot,unit.y()*c+(az*unit.x()-ax*unit.z())*s+ay*dot,unit.z()*c+(ax*unit.y()-ay*unit.x())*s+az*dot);
    }
    /** Players within ZONE edge-equivalent blocks of a vertex, and no higher than ZONE_HEIGHT above the chart floor, are inside the anomaly. */
    public static final double ZONE=PLATEAU_OUTER;public static final int ZONE_HEIGHT=320;
    public static double distance(Point unit){return distance(unit,NativePlanetShape.STANDARD);}
    /** Distance from the nearest vertex of a planet of this shape, in its edge-equivalent blocks. */
    public static double distance(Point unit,NativePlanetShape shape){double c=(Math.abs(unit.x())+Math.abs(unit.y())+Math.abs(unit.z()))/Math.sqrt(3);return Math.acos(Math.min(1,c))/(Math.sqrt(2)/(3*shape.half()));}
    /** Elevation of the shelf blended over the generated one. */
    public static double elevation(double generated,double distance){double t=Math.clamp((distance-PLATEAU_INNER)/(PLATEAU_OUTER-PLATEAU_INNER),0,1);return PLATEAU+(generated-PLATEAU)*t*t*(3-2*t);}
    /** Top block of the field at this distance, or null outside it. */
    public static BlockState surface(double distance){return distance>=FIELD?null:(int)Math.floor(distance)%RING==0?ring():ground();}
    /** Is this chart cell coordinate within core cells of either end of the face, counted on both sides of the edge?
     *  On an unfolded plane two such coordinates select the whole closed column: three faces' squares and the wedge between. */
    public static boolean closed(int cell,int core){return closed(NativePlanetShape.STANDARD,cell,core);}
    public static boolean closed(NativePlanetShape shape,int cell,int core){int size=shape.size();return Math.min(cell>=0?cell:-cell-1,cell<size?size-1-cell:cell-size)<core;}
    /** The closed column at each vertex of a planet of this shape: GroundSpace's mask for a planet of any size. */
    public static boolean column(NativePlanetShape shape,GroundSpace.Config mask,CubeSurfaceTopology.Cell cell){int last=shape.size()-1;return cell!=null&&shape.topology().owned(cell)&&Math.min(cell.u(),last-cell.u())<mask.core()&&Math.min(cell.v(),last-cell.v())<mask.core();}
    public static boolean mask(NativePlanetShape shape,GroundSpace.Config mask,CubeSurfaceTopology.Cell cell){return cell!=null&&cell.h()>=GroundSpace.MIN&&cell.h()<mask.height()&&column(shape,mask,cell);}
    public static boolean band(long row){return Math.floorMod(row,BAND)>=BAND-BAND_HEIGHT;}
    private NativeAnomaly(){}
}
