package dev.starfold;

import dev.starfold.mixin.CubicSpriteAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Resource-pack texture albedo × vanilla block/biome tint, never MapColor.
 * Atlas images are borrowed read-only; averages are discarded on resource reload. */
public final class NativeGroundAlbedo {
    private static final Map<TextureAtlasSprite,int[]> pixels=new IdentityHashMap<>();
    private static final Map<TextureAtlasSprite,Map<Integer,Integer>> colors=new IdentityHashMap<>();
    public static void clear(){pixels.clear();colors.clear();}
    public static int color(BlockState state,BlockPos pos){return color(state,pos,Minecraft.getInstance().level);}
    public static int color(BlockState state,BlockPos pos,net.minecraft.world.level.BlockAndTintGetter view){
        var mc=Minecraft.getInstance();var fluid=state.getFluidState();var handler=fluid.isEmpty()?null:net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions.of(fluid.getType());if(handler==net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions.DEFAULT)handler=null;
        TextureAtlasSprite sprite;int tint=0xffffff;
        if(handler!=null){sprite=mc.getTextureAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS).apply(handler.getStillTexture(fluid,view,pos));tint=handler.getTintColor(fluid,view,pos)&0xffffff;}
        else{var model=mc.getBlockRenderer().getBlockModel(state);var tops=model.getQuads(state,Direction.UP,RandomSource.create(0));if(tops.isEmpty())tops=model.getQuads(state,null,RandomSource.create(0));var quad=tops.isEmpty()?null:tops.getFirst();sprite=quad==null?model.getParticleIcon():quad.getSprite();if(quad!=null&&quad.isTinted()){int value=mc.getBlockColors().getColor(state,view,pos,quad.getTintIndex());if(value!=-1)tint=value&0xffffff;}}
        int rgb=tint;return colors.computeIfAbsent(sprite,s->new HashMap<>()).computeIfAbsent(rgb,t->average(pixels.computeIfAbsent(sprite,s->((CubicSpriteAccess)(Object)s.contents()).starfold$cubicImage().getPixelsRGBA()),t));
    }
    /** ABGR pixels, alpha-weighted; tint is applied before averaging in linear light. */
    public static int average(int[] abgr,int tint){double[] sum=new double[3];double weight=0;for(int pixel:abgr){double alpha=(pixel>>>24)/255.0;if(alpha==0)continue;weight+=alpha;for(int j=0;j<3;j++)sum[j]+=alpha*CubicColor.linear((pixel>>(8*j)&255)/255.0*(tint>>(16-8*j)&255)/255.0);}if(weight==0)return 0xff00ff;int out=0;for(double channel:sum)out=out<<8|(int)Math.round(Math.clamp(CubicColor.encoded(channel/weight),0,1)*255);return out;}
    private NativeGroundAlbedo(){}
}
