package dev.starfold;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.starfold.BentStripGeometry.Point;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.*;
import org.joml.Vector3d;

/** Reference-relative motes: every render transforms their universe position through the current frame. */
final class NativeSpaceDustParticle extends TextureSheetParticle {
    private static long probeTick;
    private final Point origin,reference;private final int planet;
    NativeSpaceDustParticle(ClientLevel level,double x,double y,double z,SpriteSet sprites){super(level,x,y,z);pickSprite(sprites);hasPhysics=false;lifetime=NativeSpaceDustClient.LIFETIME;var frame=NativeSpaceClient.frame();planet=frame.planet();var model=NativeGroundClient.ephemeris();double time=NativeSpaceClient.time();origin=frame.universe(new Point(x,y,z),model,time);reference=model.at(planet,time).center();}
    private Point position(){var frame=NativeSpaceClient.frame();var model=NativeGroundClient.ephemeris();double time=NativeSpaceClient.time();return frame.level(origin.add(model.at(planet,time).center().sub(reference)),model,time);}
    @Override public void tick(){xo=x;yo=y;zo=z;if(age++>=lifetime||!NativeSpaceClient.active()){remove();return;}var at=position();setPos(at.x(),at.y(),at.z());}
    @Override public net.minecraft.world.phys.AABB getRenderBoundingBox(float partial){if(!NativeSpaceClient.active())return super.getRenderBoundingBox(partial);var at=position();double r=NativeSpaceDust.MAX_LENGTH/2;return new net.minecraft.world.phys.AABB(at.x()-r,at.y()-r,at.z()-r,at.x()+r,at.y()+r,at.z()+r);}
    @Override public ParticleRenderType getRenderType(){return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;}
    @Override public void render(VertexConsumer out,Camera camera,float partial){if(!NativeSpaceClient.active())return;var at=position();var eye=camera.getPosition();var centre=new Vector3d(at.x()-eye.x,at.y()-eye.y,at.z()-eye.z);if(centre.lengthSquared()<36||NativeSpaceDustClient.inside(at.x(),at.y(),at.z()))return;
        var frame=NativeSpaceClient.frame();var model=NativeGroundClient.ephemeris();double time=NativeSpaceClient.time();var drift=frame.levelVelocity(model.at(planet,time).velocity(),model,time);var motion=new Vector3d(drift.x(),drift.y(),drift.z());double speed=motion.length()*20;if(speed<1e-6)return;
        // Project the motion into the view-facing plane. Foreshortening is real: radial travel has a vanishing point.
        var normal=new Vector3d(centre).normalize();var along=new Vector3d(motion).normalize().mul(NativeSpaceDust.length(speed));along.fma(-along.dot(normal),normal);var side=new Vector3d(along).cross(normal);if(side.lengthSquared()<1e-12)return;side.normalize().mul(NativeSpaceDust.width(speed)/2);along.mul(.5);
        var inverse=new org.joml.Quaterniond(camera.rotation()).conjugate();var start=new Vector3d(centre).sub(along).rotate(inverse);var end=new Vector3d(centre).add(along).rotate(inverse);if(start.z>=0||end.z>=0)return;var mc=net.minecraft.client.Minecraft.getInstance();var projection=mc.gameRenderer.getProjectionMatrix(mc.options.fov().get());if(DevProbe.active()&&probeTick!=level.getGameTime()){probeTick=level.getGameTime();org.slf4j.LoggerFactory.getLogger("starfold-probe").info("PROBE DUST_PROJECT tick {} start {} end {} scale {} {}",probeTick,start,end,projection.m00(),projection.m11());}var middle=new Vector3d(centre).rotate(inverse);double fade=NativeSpaceDust.peripheral(middle.x*projection.m00()/-middle.z,middle.y*projection.m11()/-middle.z);
        float alpha=(float)(fade*NativeSpaceDust.alpha(speed,age+partial,lifetime+1));var a=new Vector3d(centre).sub(along).sub(side);var b=new Vector3d(centre).sub(along).add(side);var c=new Vector3d(centre).add(along).add(side);var d=new Vector3d(centre).add(along).sub(side);
        vertex(out,d,getU1(),getV1(),alpha);vertex(out,c,getU1(),getV0(),alpha);vertex(out,b,getU0(),getV0(),alpha);vertex(out,a,getU0(),getV1(),alpha);
    }
    private static void vertex(VertexConsumer out,Vector3d p,float u,float v,float alpha){out.addVertex((float)p.x,(float)p.y,(float)p.z).setUv(u,v).setColor(.72f,.86f,1f,alpha).setLight(0xf000f0);}
}
