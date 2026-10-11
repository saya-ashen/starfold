package dev.starfold;

import net.minecraft.nbt.CompoundTag;
import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeSpaceServerTest {
    @Test void savedFrameRoundTripsWithoutRegistriesAndRejectsCorruption(){
        var frame=NativeSpace.Frame.keeping(NativeSpaceTest.chart(new BentStripGeometry.Point(123,456,789)),new Quaterniond().rotationXYZ(.4,.8,-.2),NativeSpaceTest.tilted(),1,1234);
        var value=new NativeSpaceServer(frame);var tag=value.save(new CompoundTag(),null);tag.putLong("epoch",1234);var loaded=NativeSpaceServer.load(tag,null);
        assertEquals(tag,loaded.save(new CompoundTag(),null));assertFalse(loaded.isDirty());
        var fresh=NativeSpaceServer.fresh(false);assertTrue(fresh.isDirty());assertEquals(new NativeSpaceServer(NativeSpace.Frame.IDENTITY).save(new CompoundTag(),null),fresh.save(new CompoundTag(),null));
        assertThrows(IllegalStateException.class,()->NativeSpaceServer.fresh(true));
        for(String key:new String[]{"qx","qy","qz","qw","x","y","z","planet","epoch"}){var bad=tag.copy();bad.remove(key);assertThrows(IllegalArgumentException.class,()->NativeSpaceServer.load(bad,null));}
        var bad=tag.copy();bad.putDouble("qw",20);assertThrows(IllegalArgumentException.class,()->NativeSpaceServer.load(bad,null));
        var invalid=tag.copy();invalid.putDouble("x",Double.NaN);assertThrows(IllegalArgumentException.class,()->NativeSpaceServer.load(invalid,null));
        for(int planet:new int[]{-1,NativePlanetStorage.MAX_PLANETS}){var wrong=tag.copy();wrong.putInt("planet",planet);assertThrows(IllegalArgumentException.class,()->NativeSpaceServer.load(wrong,null));}
        var past=tag.copy();past.putLong("epoch",-1);assertThrows(IllegalArgumentException.class,()->NativeSpaceServer.load(past,null));
    }
    @Test void anchorThresholdIsStrictOnEveryAxisAndWithinSablesDefaultBand(){
        assertEquals(-10000,NativeSpace.SABLE_MIN_Y);assertEquals(100000,NativeSpace.SABLE_MAX_Y);assertEquals(8192,NativeSpace.Y);assertEquals(12000,NativeSpace.ANCHOR_REACH);
        assertTrue(NativeSpace.Y-NativeSpace.ANCHOR_REACH>NativeSpace.SABLE_MIN_Y);assertTrue(NativeSpace.Y+NativeSpace.ANCHOR_REACH<NativeSpace.SABLE_MAX_Y);
        for(int axis=0;axis<3;axis++)for(int sign:new int[]{-1,1})for(double distance:new double[]{12000,12001}){
            var delta=new BentStripGeometry.Point(axis==0?sign*distance:0,axis==1?sign*distance:0,axis==2?sign*distance:0);var at=NativeSpace.ORIGIN.add(delta);var shift=NativeSpaceServer.anchor(at);
            if(distance==12000)assertNull(shift);else NativeSpaceTest.point(NativeSpace.ORIGIN,at.add(shift));
        }
    }
    @Test void referenceNeedsMoreThan512BlocksAdvantage(){
        var model=NativeSpaceTest.tilted();for(double tick:new double[]{0,1234,9876}){
            var a=model.at(0,tick).center();var b=model.at(1,tick).center();var axis=b.sub(a).scale(1/b.sub(a).length());var middle=a.add(b).scale(.5);
            assertFalse(NativeSpaceServer.nearer(model,middle.add(axis.scale(255)),0,1,tick));assertTrue(NativeSpaceServer.nearer(model,middle.add(axis.scale(257)),0,1,tick));assertFalse(NativeSpaceServer.nearer(model,b,1,1,tick));
        }
    }
    @Test void enteringChecksLiveRegionBodiesBeforeReplacingAndDirtyingItsOwnLevelsFrame()throws Exception{
        var type=new ClassNode();try(var in=TestArchiveResources.open("/dev/starfold/NativeSpaceServer.class")){new ClassReader(in).accept(type,0);}
        var method=type.methods.stream().filter(m->m.name.equals("entering")).findFirst().orElseThrow();var names=new java.util.ArrayList<String>();
        for(var ins:method.instructions)if(ins instanceof MethodInsnNode call)names.add(call.name);
        for(String name:new String[]{"getContainer","getAllSubLevels","isRemoved","holds","keeping","setDirty"})assertTrue(names.contains(name),name);
        assertTrue(names.indexOf("isRemoved")<names.indexOf("holds"));assertTrue(names.indexOf("holds")<names.indexOf("keeping"));assertTrue(names.indexOf("keeping")<names.indexOf("setDirty"));
        boolean returned=false;for(var ins:method.instructions){if(ins instanceof MethodInsnNode call&&call.name.equals("keeping"))break;if(ins.getOpcode()==org.objectweb.asm.Opcodes.ARETURN)returned=true;}assertTrue(returned,"occupied region returns before replacement");
        var get=type.methods.stream().filter(m->m.name.equals("get")).findFirst().orElseThrow();boolean storage=false;for(var ins:get.instructions)if(ins instanceof MethodInsnNode call){assertNotEquals("authority",call.name);if(call.name.equals("getDataStorage"))storage=true;}assertTrue(storage);
    }
}
