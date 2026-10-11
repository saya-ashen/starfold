package dev.starfold;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

/** CPU sampling-policy checks, not a GPU/driver or the user's actual grass texture test. */
class GroundTextureTest {
    static double footprint(double x,double y,double u,double v){double a=x*x+y*y,b=x*u+y*v,c=u*u+v*v;return Math.sqrt(.5*(a+c+Math.sqrt((a-c)*(a-c)+4*b*b)));}
    static double detail(double footprint,int last,int size){double capacity=Math.pow(2,Math.min(last,Math.floor(Math.log(size)/Math.log(2))));return 1-GroundGap.smooth(.5*capacity,capacity,footprint);}
    String source(String n)throws Exception{return Files.readString(Path.of("src/main/java/dev/starfold/"+n+".java"));}
    @Test void majorFootprintIsRotationInvariantAndCoversObliquePixels(){assertEquals(4,footprint(4,0,0,1),1e-10);assertEquals(4,footprint(0,4,-1,0),1e-10);assertEquals(Math.sqrt(2),footprint(1,0,1,0),1e-10);for(double t=0;t<6;t+=.1)assertEquals(4,footprint(4*Math.cos(t),4*Math.sin(t),-Math.sin(t),Math.cos(t)),1e-9);}
    @Test void absentAndPartialMipChainsFallBackBeforeUnsupportedFrequencies(){assertEquals(1,detail(.25,0,16));assertEquals(0,detail(1,0,16));assertEquals(1,detail(1,4,16));assertEquals(0,detail(16,4,16));assertEquals(0,detail(16,8,16));assertEquals(0,detail(4,2,256));for(int level=0;level<=4;level++){double prev=1;for(double f=0;f<40;f+=.1){double d=detail(f,level,16);assertTrue(d<=prev+1e-12&&d>=0&&d<=1);prev=d;}}}
    @Test void mipAwareUvInsetNeverSamplesAnAdjacentSprite(){for(int size:new int[]{16,32,256})for(int level=0;level<=4;level++)for(double f:new double[]{.1,1,2,4,12,32}){double last=Math.min(level,Math.floor(Math.log(size)/Math.log(2))),lod=Math.clamp(Math.ceil(Math.log(Math.max(1,f))/Math.log(2)),0,last);double margin=Math.min(size*.5,.5*Math.pow(2,lod));for(double uv:new double[]{0,.0001,.25,.9999,1}){double sample=Math.clamp(uv*size,margin,size-margin);assertTrue(sample-margin>=-1e-9&&sample+margin<=size+1e-9);}}}
    @Test void movingCurvedCheckerSamplingLosesSpuriousLowFrequencyBands()throws Exception{
        int w=480,h=270;var raw=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);var filtered=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);double oldEnergy=0,newEnergy=0,oldTemporal=0,newTemporal=0;
        // A repeated one-texel checker has exact 0.5 box-filtered mip levels from LOD1 on.
        // This deliberately isolates minification; no geometry/depth changes can hide it.
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){double u=x*(2.0+y*.0008)+.001*y*y,v=y*2.03;double f=footprint(2.0+y*.0008,0,.0008*x+.002*y,2.03);double before=checker(u,v),moved=checker(u+.37,v+.23);double lod=Math.max(0,Math.log(f)/Math.log(2));double mip=lod>=1?.5:before*(1-lod)+.5*lod;double nextMip=lod>=1?.5:moved*(1-lod)+.5*lod;double weight=detail(f,4,16),after=.5+(mip-.5)*weight,next=.5+(nextMip-.5)*weight;oldEnergy+=(before-.5)*(before-.5);newEnergy+=(after-.5)*(after-.5);oldTemporal+=(before-moved)*(before-moved);newTemporal+=(after-next)*(after-next);raw.setRGB(x,y,color(before));filtered.setRGB(x,y,color(after));}
        assertTrue(oldEnergy>10000&&oldTemporal>1000);assertTrue(newEnergy<oldEnergy*.01&&newTemporal<oldTemporal*.01);var out=Path.of("build/ground-texture");Files.createDirectories(out);ImageIO.write(raw,"png",out.resolve("synthetic-raw.png").toFile());ImageIO.write(filtered,"png",out.resolve("synthetic-filtered.png").toFile());Files.writeString(out.resolve("sampling.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("runtime_verified",false,"synthetic_checker_not_minecraft",true,"raw_error",oldEnergy,"filtered_error",newEnergy,"raw_motion_error",oldTemporal,"filtered_motion_error",newTemporal))+"\n");
    }
    static double checker(double u,double v){return (((int)Math.floor(u)+(int)Math.floor(v))&1);}
    static int color(double v){return ((int)(60+45*v)<<16)|((int)(100+60*v)<<8)|(int)(30+20*v);}
}
