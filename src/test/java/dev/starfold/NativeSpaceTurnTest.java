package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import dev.ryanhcode.sable.companion.math.Pose3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import static dev.starfold.NativeSpaceTest.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeSpaceTurnTest {
    @Test void pivotAndTurnedLinearVelocityKeepTheirUniverseValues(){
        var model=tilted();var pivot=NativeSpace.ORIGIN.add(new Point(12,34,56));var delta=new Quaterniond().rotationXYZ(.1,-.2,.3);
        for(int planet=0;planet<2;planet++)for(double tick:new double[]{0,1234,9876}){
            var old=new NativeSpace.Frame(new Quaterniond().rotationXYZ(.4,.5,.6),NativeSpace.ORIGIN,planet);var next=old.turned(delta,pivot);
            point(old.universe(pivot,model,tick),next.universe(pivot,model,tick));aim(new Quaterniond(delta).mul(old.rotation()),next.rotation());
            var velocity=new Point(2,-3,4);var v=delta.transform(vector(old.levelVelocity(velocity,model,tick)));
            point(velocity,next.universeVelocity(new Point(v.x,v.y,v.z),model,tick));
        }
    }
    @Test void boundedStepsConvergeWithoutOvershootWhileReferenceMoves(){
        assertEquals(30,NativeSpaceServer.ALIGN_TURN);var model=tilted();var pivot=NativeSpace.ORIGIN;
        for(int planet=0;planet<2;planet++){
            var frame=new NativeSpace.Frame(new Quaterniond().rotationXYZ(.2,.5,.7),pivot.sub(new Point(300,-400,500)),planet);double previous=Math.PI;
            for(int tick=0;tick<800;tick++){
                var up=frame.rotation().transform(NativeSpace.up(model,planet,frame.universe(pivot,model,tick),tick));double angle=Math.atan2(Math.hypot(up.x,up.z),up.y);
                assertTrue(angle<=previous+1e-10);var delta=NativeSpace.Frame.turn(frame,model,tick,pivot,Math.toRadians(NativeSpaceServer.ALIGN_TURN)/20);
                assertTrue(delta.angle()<=Math.toRadians(NativeSpaceServer.ALIGN_TURN)/20+1e-10);var next=frame.turned(delta,pivot);point(frame.universe(pivot,model,tick),next.universe(pivot,model,tick));frame=next;previous=angle;
            }
            assertTrue(previous<1e-9);aim(new Quaterniond(),NativeSpace.Frame.turn(frame,model,9000,pivot,Math.toRadians(NativeSpaceServer.ALIGN_TURN)/20));
        }
    }
    @Test void oppositeUsesStableAxisAndSmallAnglesDoNotOvershoot(){
        var model=tilted();var pivot=NativeSpace.ORIGIN;
        for(double angle:new double[]{0,5e-10,2e-9,.001,Math.PI-1e-8,Math.PI}){
            var up=new Quaterniond().rotationAxis(angle,1,0,0).transform(new Vector3d(0,1000,0));var frame=new NativeSpace.Frame(new Quaterniond(),pivot.sub(new Point(up.x,up.y,up.z)),1);
            var delta=NativeSpace.Frame.turn(frame,model,1234,pivot,Math.PI);var next=frame.turned(delta,pivot);
            var radial=vector(pivot.sub(next.shift())).normalize();assertTrue(radial.distance(new Vector3d(0,1,0))<1e-9);
            if(angle<=1e-9)assertEquals(new Quaterniond(),delta,"angle "+angle+" up "+up+" frame "+frame);
            if(angle==Math.PI){assertTrue(delta.x>0);assertEquals(0,delta.y);assertEquals(0,delta.z);}
        }
        var frame=new NativeSpace.Frame(new Quaterniond(),pivot.sub(new Point(1,0,0)),0);
        assertEquals(new Quaterniond(),NativeSpace.Frame.turn(frame,model,0,pivot,0));
        for(double invalid:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY})assertThrows(IllegalArgumentException.class,()->NativeSpace.Frame.turn(frame,model,0,pivot,invalid));
    }
    @Test void otherHullBothPosesAndRiderAreRigidlyRelabelled(){
        var model=tilted();var old=new NativeSpace.Frame(new Quaterniond().rotationXYZ(.4,.5,.6),NativeSpace.ORIGIN,1);var delta=new Quaterniond().rotationAxis(.02,1,0,0);var next=old.turned(delta,NativeSpace.ORIGIN);
        var m=chart(NativeSpace.ORIGIN.add(new Point(200,300,-400)));var moved=next.level(old.universe(m,model,1234),model,1234);motion(old.universe(m,model,1234),next.universe(moved,model,1234));
        var before=new Pose3d(vector(m.position()),m.orientation(),new Vector3d(4,5,6),new Vector3d(1,1,1));var now=new Pose3d(before);var last=new Pose3d(before);last.position().add(-1,2,3);var lastBefore=new Vector3d(last.position());
        NativeSableFrames.carry(before,moved,now);NativeSableFrames.carry(before,moved,last);
        point(old.universe(new Point(lastBefore.x,lastBefore.y,lastBefore.z),model,1234),next.universe(new Point(last.position().x,last.position().y,last.position().z),model,1234));
        var feet=before.transformPosition(new Vector3d(2,3,4));var carried=NativeSableFrames.riderPoint(before,now,feet);
        vector(new Vector3d(2,3,4),now.transformPositionInverse(carried,new Vector3d()));
        point(old.universe(new Point(feet.x,feet.y,feet.z),model,1234),next.universe(new Point(carried.x,carried.y,carried.z),model,1234));
    }
    @Test void alignedLevelDeckArrivesUprightInEveryChart(){
        var model=tilted();for(var shape:SHAPES)for(int planet=0;planet<2;planet++)for(var face:CubeSurfaceTopology.Face.values())for(double tick:new double[]{0,1234,9876}){
            var pose=new NativeGroundTopology.Pose(face,new Point(shape.size()*.17,1500,shape.size()*.81));var universe=NativeSpace.out(model,planet,shape,pose,resting(pose.point()),tick);
            var frame=new NativeSpace.Frame(new Quaterniond(),NativeSpace.ORIGIN,planet);var position=frame.level(universe.position(),model,tick);
            frame=frame.turned(NativeSpace.Frame.turn(frame,model,tick,position,Math.PI),position);
            var arriving=NativeSpace.in(model,planet,shape,frame.universe(resting(position),model,tick),tick);
            assertTrue(arriving.motion().orientation().transform(new Vector3d(0,1,0)).y>.999);
        }
    }
    @Test void interpolationSlerpsAxesAndLerpsCameraUniversePositionNotShift(){
        var model=tilted();var old=new NativeSpace.Frame(new Quaterniond().rotationXYZ(.1,.2,.3),NativeSpace.ORIGIN,1);var next=old.turned(new Quaterniond().rotationZ(.8),NativeSpace.ORIGIN.add(new Point(20,30,40)));var eye=NativeSpace.ORIGIN.add(new Point(100,200,300));
        for(double tick:new double[]{0,1234,9876})for(double a:new double[]{0,.1,.5,.9,1}){
            var frame=NativeSpace.Frame.interpolate(old,next,eye,a,model,tick);aim(old.rotation().slerp(next.rotation(),a),frame.rotation());
            point(old.universe(eye,model,tick).scale(1-a).add(next.universe(eye,model,tick).scale(a)),frame.universe(eye,model,tick));
        }
    }
    @Test void turnHistoryHasNoReceiptStepAndFinishesAfterOneClientTick(){
        var frames=new NativeSpaceView.Frames();var eye=NativeSpace.ORIGIN.add(new Point(5,2,7));var old=NativeSpace.Frame.IDENTITY;var next=old.turned(new Quaterniond().rotationAxis(.1,1,0,0),NativeSpace.ORIGIN);
        frames.install(new NativeSpacePackets.Frame(old,12),eye,100,tilted(),1234);frames.install(new NativeSpacePackets.Frame(next,13,true),eye,101.3,tilted(),1234);
        motion(old.universe(chart(eye),tilted(),1234),frames.sample(eye,101.3,tilted(),1234).universe(chart(eye),tilted(),1234));
        var half=frames.sample(eye,101.8,tilted(),1234);var later=next.turned(new Quaterniond().rotationAxis(.1,1,0,0),NativeSpace.ORIGIN);
        frames.install(new NativeSpacePackets.Frame(later,14,true),eye,101.8,tilted(),1234);aim(half.rotation(),frames.sample(eye,101.8,tilted(),1234).rotation());point(half.shift(),frames.sample(eye,101.8,tilted(),1234).shift());
        aim(later.rotation(),frames.sample(eye,102.8,tilted(),1234).rotation());point(later.shift(),frames.sample(eye,102.8,tilted(),1234).shift());
    }
    @Test void freeAttitudeFramesStillBlendWithoutArrivalFlag(){
        var eye=NativeSpace.ORIGIN;var old=NativeSpace.Frame.IDENTITY;var next=old.turned(new Quaterniond().rotationX(.1),eye);var frames=new NativeSpaceView.Frames();frames.install(new NativeSpacePackets.Frame(old,1,false),eye,1,tilted(),1234);frames.install(new NativeSpacePackets.Frame(next,2,false),eye,2,tilted(),1234);aim(old.rotation(),frames.sample(eye,2,tilted(),1234).rotation());aim(next.rotation(),frames.sample(eye,3,tilted(),1234).rotation());
    }
    @Test void reanchorCarryAndDisconnectSnapRatherThanBlend(){
        var eye=NativeSpace.ORIGIN;var old=NativeSpace.Frame.IDENTITY;var turned=old.turned(new Quaterniond().rotationAxis(.1,1,0,0),eye);
        for(var packet:new NativeSpacePackets.Frame[]{new NativeSpacePackets.Frame(turned.shifted(new Point(NativeSpace.ANCHOR_REACH+1,0,0)),13),new NativeSpacePackets.Frame(turned.shifted(new Point(0,NativeSpace.ANCHOR_REACH+1,0)),12)}){
            var frames=new NativeSpaceView.Frames();frames.install(new NativeSpacePackets.Frame(old,12),eye,100,tilted(),1234);frames.install(packet,eye,101,tilted(),1234);
            aim(packet.frame().rotation(),frames.sample(eye,101,tilted(),1234).rotation());point(packet.frame().shift(),frames.sample(eye,101,tilted(),1234).shift());
            frames.clear();frames.install(new NativeSpacePackets.Frame(turned,12,true),eye,101.5,tilted(),1234);assertEquals(turned,frames.current());aim(turned.rotation(),frames.sample(eye,101.5,tilted(),1234).rotation());
            frames.install(new NativeSpacePackets.Frame(null,0),eye,102,tilted(),1234);assertNull(frames.sample(eye,102,tilted(),1234));
        }
    }
    @Test void turnFlagRoundTripsAndDefaultPacketsSnap(){
        var b=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY);try{
            var p=new NativeSpacePackets.Frame(NativeSpace.Frame.IDENTITY,12,true);NativeSpacePackets.Frame.CODEC.encode(b,p);assertEquals(p,NativeSpacePackets.Frame.CODEC.decode(b));assertEquals(0,b.readableBytes());assertFalse(new NativeSpacePackets.Frame(p.frame(),12).turning());
        }finally{b.release();}
    }
    @Test void serverTurnIsGyroGatedBetweenReferenceAndAnchorAndNeverMovesLead()throws Exception{
        var node=new ClassNode();try(var in=TestArchiveResources.open("/dev/starfold/NativeSpaceServer.class")){new ClassReader(in).accept(node,0);}
        var method=node.methods.stream().filter(m->m.name.equals("step")).findFirst().orElseThrow();var calls=new java.util.ArrayList<String>();for(var ins:method.instructions)if(ins instanceof MethodInsnNode c)calls.add(c.name);
        assertTrue(calls.subList(calls.indexOf("toward"),calls.indexOf("turned")).contains("steadied"));assertTrue(calls.indexOf("turned")<calls.indexOf("anchor"));
        var source=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/starfold/NativeSpaceServer.java"));var turn=source.substring(source.indexOf("if(NativeThruster.steadied(lead"),source.indexOf("var delta=anchor(point)"));
        assertTrue(turn.contains("if(body!=lead)"));assertTrue(source.contains("send(player,true)"));assertFalse(turn.contains("addLinearAndAngularVelocity"));assertFalse(turn.contains("rider.set"));assertFalse(turn.contains("teleport"));assertFalse(turn.contains("resetVelocity"));
    }
}
