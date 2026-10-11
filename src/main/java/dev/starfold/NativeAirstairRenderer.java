package dev.starfold;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/** Sable supplies the hull pose once. Cancel rotation, then orient the plumb flight in chart gravity. */
public final class NativeAirstairRenderer implements BlockEntityRenderer<NativeAirstair.Stair> {
    static void initialize(net.neoforged.bus.api.IEventBus bus){
        bus.addListener((net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers e)->e.registerBlockEntityRenderer(NativeAirstair.type,c->new NativeAirstairRenderer()));
        LocalNet.clientReceiver(NativeAirstair.State.TYPE,(p,c)->{var level=Minecraft.getInstance().level;if(level!=null&&level.getBlockEntity(p.pos()) instanceof NativeAirstair.Stair stair){stair.length=p.length();stair.width=p.width();stair.published.set(p.x(),p.y(),p.z());stair.publishedOut.set(p.ox(),0,p.oz());stair.received=level.getGameTime();}});
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post e)->{var level=Minecraft.getInstance().level;if(level!=null)NativeAirstair.tick(level);});
    }
    private final java.util.Map<NativeAirstair.Stair,Double> lengths=new java.util.WeakHashMap<>();
    private static RenderType material(String name){return RenderType.entityCutoutNoCull(ResourceLocation.parse("starfold:textures/block/airstair_"+name+".png"));}
    @Override public void render(NativeAirstair.Stair stair,float partial,PoseStack stack,MultiBufferSource buffers,int light,int overlay){
        var level=stair.getLevel();if(level==null||stair.width==0||!(Sable.HELPER.getContaining(level,stair.getBlockPos()) instanceof ClientSubLevel body))return;
        double fraction=stair.oldVisual+(stair.visual-stair.oldVisual)*partial;if(fraction<=0)return;
        if(stair.length>0)lengths.put(stair,stair.length);double drop=lengths.getOrDefault(stair,1d);int steps=(int)Math.ceil(drop/.5-1e-8);double half=stair.width*.5;
        var local=NativeAirstair.localTop(stair);var p=stair.getBlockPos();var pose=body.renderPose(partial);var top=pose.transformPosition(new Vector3d(local));
        var facing=stair.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING);var out=pose.orientation().transform(new Vector3d(facing.getStepX(),0,facing.getStepZ()));
        stack.pushPose();stack.translate(local.x-p.getX(),local.y-p.getY(),local.z-p.getZ());stack.mulPose(new Quaternionf(pose.orientation()).conjugate());
        // Lowered, the flight is drawn where the server planted it, so a hull that trembles on its gear does not shake the treads.
        if(stair.length>0&&stair.publishedOut.lengthSquared()>.5&&top.distance(stair.published)<1){stack.translate(stair.published.x-top.x,stair.published.y-top.y,stair.published.z-top.z);out.set(stair.publishedOut);top.set(stair.published);}
        stack.mulPose(new Quaternionf().rotationY((float)Math.atan2(out.x,out.z)));
        double ease=fraction*fraction*(3-2*fraction);stack.mulPose(new Quaternionf().rotationX((float)((1-ease)*Math.PI/4)));stack.scale(1,(float)(.12+.88*ease),(float)(.12+.88*ease));
        box(stack,buffers.getBuffer(material("plate")),-half,-.125,0,half,0,.125,light,overlay);
        for(int i=0;i<steps;i++){
            double y=-Math.min(drop,(i+1)*.5)+(i==steps-1?.0125:0),z=i*.5;int sampled=net.minecraft.client.renderer.LevelRenderer.getLightColor(level,BlockPos.containing(top.x+out.x*z,top.y+y,top.z+out.z*z));
            box(stack,buffers.getBuffer(material(i==steps-1?"hazard":"plate")),-half,y-.125,z,half,y,z+.5,sampled,overlay);
            box(stack,buffers.getBuffer(material("cyan")),-half+.045,y-.035,z+.464,half-.045,y+.006,z+.493,0xf000f0,overlay);
        }
        var metal=buffers.getBuffer(material("metal"));int railLight=net.minecraft.client.renderer.LevelRenderer.getLightColor(level,BlockPos.containing(top.x,top.y,top.z));
        double end=steps*.5-.12;
        for(double side:new double[]{-half+.035,half-.035}){
            stack.pushPose();stack.translate(side,-.16,0);stack.mulPose(new Quaternionf().rotationX((float)Math.atan2(drop,steps*.5)));box(stack,metal,-.055,-.10,0,.055,.10,Math.hypot(drop,steps*.5),railLight,overlay);stack.popPose();
            // Six-sided tubular rails; no invisible rail collision. Posts on alternate treads and at the foot.
            for(int i=0;i<steps;i+=2){double y=-Math.min(drop,(i+1)*.5),z=i*.5+.12;tube(stack,metal,new Vector3f((float)side,(float)y,(float)z),new Vector3f((float)side,(float)(.62-z),(float)z),.032f,railLight,overlay);}
            tube(stack,metal,new Vector3f((float)side,(float)-drop,(float)end),new Vector3f((float)side,(float)(.62-end),(float)end),.032f,railLight,overlay);
            tube(stack,metal,new Vector3f((float)side,.5f,.12f),new Vector3f((float)side,(float)(.62-end),(float)end),.045f,railLight,overlay);
        }stack.popPose();
    }
    private static void tube(PoseStack stack,VertexConsumer out,Vector3f a,Vector3f b,float radius,int light,int overlay){var axis=new Vector3f(b).sub(a);float length=axis.length();if(length<.001)return;stack.pushPose();stack.translate(a.x,a.y,a.z);stack.mulPose(new Quaternionf().rotationTo(new Vector3f(0,1,0),axis.normalize()));for(int i=0;i<8;i++){double t=i*Math.PI/4,u=(i+1)*Math.PI/4;float x=(float)Math.cos(t)*radius,z=(float)Math.sin(t)*radius,xx=(float)Math.cos(u)*radius,zz=(float)Math.sin(u)*radius;quad(stack,out,new float[][]{{x,0,z},{xx,0,zz},{xx,length,zz},{x,length,z}},(float)Math.cos((t+u)/2),0,(float)Math.sin((t+u)/2),light,overlay);}stack.popPose();}
    private static void box(PoseStack s,VertexConsumer o,double x,double y,double z,double X,double Y,double Z,int light,int overlay){float a=(float)x,b=(float)y,c=(float)z,d=(float)X,e=(float)Y,f=(float)Z;quad(s,o,new float[][]{{a,e,c},{a,e,f},{d,e,f},{d,e,c}},0,1,0,light,overlay);quad(s,o,new float[][]{{a,b,c},{d,b,c},{d,b,f},{a,b,f}},0,-1,0,light,overlay);quad(s,o,new float[][]{{a,b,c},{a,e,c},{d,e,c},{d,b,c}},0,0,-1,light,overlay);quad(s,o,new float[][]{{a,b,f},{d,b,f},{d,e,f},{a,e,f}},0,0,1,light,overlay);quad(s,o,new float[][]{{a,b,c},{a,b,f},{a,e,f},{a,e,c}},-1,0,0,light,overlay);quad(s,o,new float[][]{{d,b,c},{d,e,c},{d,e,f},{d,b,f}},1,0,0,light,overlay);}
    private static void quad(PoseStack stack,VertexConsumer out,float[][] vertices,float nx,float ny,float nz,int light,int overlay){var pose=stack.last();for(int i=0;i<4;i++){var v=vertices[i];out.addVertex(pose.pose(),v[0],v[1],v[2]).setColor(255,255,255,255).setUv(i==0||i==3?0:1,i<2?0:1).setOverlay(overlay).setLight(light).setNormal(pose,nx,ny,nz);}}
    @Override public boolean shouldRenderOffScreen(NativeAirstair.Stair stair){return true;}
    @Override public int getViewDistance(){return 160;}
    @Override public net.minecraft.world.phys.AABB getRenderBoundingBox(NativeAirstair.Stair stair){return new net.minecraft.world.phys.AABB(stair.getBlockPos()).inflate(13);}
}
