package dev.starfold;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/** Local geometry: Sable's BER dispatcher already applies the body's render pose, exactly once. */
public final class NativeThrusterRenderer implements BlockEntityRenderer<NativeThruster.Thruster> {
    static boolean probeVisible=true; // Only the explicitly enabled DevProbe can toggle the visual for paired GPU measurements.
    private final java.util.Map<NativeThruster.Thruster,Spool> spools=new java.util.WeakHashMap<>();
    private static final int SIDES=12;
    private static final float[] COS=new float[SIDES+1],SIN=new float[SIDES+1];
    private static final float[] DISTANCES={0,.18f,.60f,1},RADII={1,1.4f,.72f,0},ALPHAS={1,.85f,.35f,0};
    static {for(int i=0;i<=SIDES;i++){COS[i]=(float)Math.cos(i*Math.PI*2/SIDES);SIN[i]=(float)Math.sin(i*Math.PI*2/SIDES);}}
    private static final class Spool {double time;float power,boost;Spool(double time){this.time=time;}}
    private static final class Material extends RenderStateShard {
        private Material(){super("thruster_plume",()->{},()->{});}
        // No texture, normals or lightmap. Sorted alpha shells, depth tested but never depth writing.
        static final RenderType JET=RenderType.create("starfold_thruster_plume",DefaultVertexFormat.POSITION_COLOR,VertexFormat.Mode.QUADS,8192,false,true,RenderType.CompositeState.builder().setShaderState(RENDERTYPE_LIGHTNING_SHADER).setTransparencyState(TRANSLUCENT_TRANSPARENCY).setCullState(NO_CULL).setWriteMaskState(COLOR_WRITE).createCompositeState(false));
    }
    @Override public void render(NativeThruster.Thruster entity,float partial,PoseStack stack,MultiBufferSource buffers,int light,int overlay){
        var level=entity.getLevel();if(level==null)return;var state=entity.getBlockState();int power=NativeHelmInputClient.power(level,entity.getBlockPos(),state);
        double now=level.getGameTime()+partial;var spool=spools.computeIfAbsent(entity,key->new Spool(now));double elapsed=Math.clamp(now-spool.time,0,4);spool.time=now;
        float ease=(float)(1-Math.exp(-elapsed/1.5));spool.power+=(power/15f-spool.power)*ease;spool.boost+=((NativeThrusterClient.boosted(level,entity.getBlockPos())?1:0)-spool.boost)*ease;
        if(spool.power<.005f||!probeVisible)return;
        float flicker=1+.025f*(float)Math.sin(now*2.7+(entity.getBlockPos().asLong()&1023));float length=(2.5f+3.5f*spool.boost)*spool.power*flicker;
        stack.pushPose();stack.translate(.5,.5,.5);stack.mulPose(state.getValue(BlockStateProperties.FACING).getRotation());
        // Direction.getRotation maps local +Y onto FACING. Recess is 2px behind the lip, so the core meets it.
        // Emissive nozzle overlay also responds to commands without mutating the redstone block state.
        stack.translate(0,.376,0);var glow=buffers.getBuffer(Material.JET);var gm=stack.last().pose();float ga=Math.min(1,spool.power);glow.addVertex(gm,-.24f,0,-.24f).setColor(80,205,255,(int)(255*ga));glow.addVertex(gm,.24f,0,-.24f).setColor(80,205,255,(int)(255*ga));glow.addVertex(gm,.24f,0,.24f).setColor(80,205,255,(int)(255*ga));glow.addVertex(gm,-.24f,0,.24f).setColor(80,205,255,(int)(255*ga));var out=buffers.getBuffer(Material.JET);var matrix=stack.last().pose();float brightness=(.65f+.35f*spool.boost)*spool.power;
        shell(out,matrix,length,.25f,45,175,255,.34f*brightness);
        shell(out,matrix,length*.86f,.18f,95,220,255,.30f*brightness);
        shell(out,matrix,length*.72f,.105f,225,250,255,.45f*brightness);
        stack.popPose();
    }
    private static void shell(VertexConsumer out,Matrix4f matrix,float length,float radius,int r,int g,int b,float alpha){
        // Three frusta rather than a pointed opaque spike: narrow throat, expanding flame, smoothly fading tip.
        for(int ring=0;ring<3;ring++)for(int side=0;side<SIDES;side++){
            vertex(out,matrix,side,RADII[ring]*radius,DISTANCES[ring]*length,r,g,b,ALPHAS[ring]*alpha);vertex(out,matrix,side+1,RADII[ring]*radius,DISTANCES[ring]*length,r,g,b,ALPHAS[ring]*alpha);
            vertex(out,matrix,side+1,RADII[ring+1]*radius,DISTANCES[ring+1]*length,r,g,b,ALPHAS[ring+1]*alpha);vertex(out,matrix,side,RADII[ring+1]*radius,DISTANCES[ring+1]*length,r,g,b,ALPHAS[ring+1]*alpha);
        }
    }
    private static void vertex(VertexConsumer out,Matrix4f matrix,int side,float radius,float y,int r,int g,int b,float alpha){out.addVertex(matrix,COS[side]*radius,y,SIN[side]*radius).setColor(r,g,b,(int)(255*alpha));}
    @Override public int getViewDistance(){return 160;}
    @Override public AABB getRenderBoundingBox(NativeThruster.Thruster entity){var n=entity.getBlockState().getValue(BlockStateProperties.FACING).getNormal();return new AABB(entity.getBlockPos()).expandTowards(n.getX()*6.5,n.getY()*6.5,n.getZ()*6.5);}
    // Sable culls block-entity *sections* before consulting individual bounds; its global-BER path avoids that.
    @Override public boolean shouldRenderOffScreen(NativeThruster.Thruster entity){return true;}
}
