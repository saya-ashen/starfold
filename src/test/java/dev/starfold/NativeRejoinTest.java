package dev.starfold;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Regression contracts do not stand in for a real disconnect and client collision reacquisition. */
class NativeRejoinTest {
    private static ClassNode type(String name)throws Exception{var node=new ClassNode();try(var in=TestArchiveResources.open("/dev/starfold/"+name+".class")){new ClassReader(in).accept(node,0);}return node;}
    private static String source(String name)throws Exception{return Files.readString(Path.of("src/main/java/dev/starfold/"+name+".java"));}
    private static List<String> calls(String name,String method)throws Exception{var out=new ArrayList<String>();for(var m:type(name).methods)if(m.name.equals(method))for(var ins:m.instructions)if(ins instanceof MethodInsnNode c)out.add(c.name);return out;}
    @Test void retainedJobsNeverReadTheCurrentObserverMask()throws Exception{
        var job=type("NativePlanetRemoteRenderer$Job");
        assertTrue(job.fields.stream().anyMatch(f->f.name.equals("mask")&&(f.access&org.objectweb.asm.Opcodes.ACC_FINAL)!=0));
        for(var method:job.methods)for(var ins:method.instructions)if(ins instanceof MethodInsnNode call)assertFalse(Set.of("dev/starfold/NativeSpaceClient","dev/starfold/NativeGroundFlightClient").contains(call.owner),"a retained job must not consult the new observer");
        assertTrue(calls("NativePlanetRemoteRenderer$Job","<init>").contains("requireNonNull"));
        var s=source("NativePlanetRemoteRenderer");assertTrue(s.contains("void work(long deadline){if(mask==null)return;"));assertTrue(s.contains("new Job(building,cursor,mask)"));assertTrue(s.contains("body.mask=mask(profile.session())"));assertTrue(s.contains("if(mask!=null)body.mask=mask"));assertTrue(s.contains("mask=null;view=new NativePlanetRemoteView()"));
    }
    @Test void savedAttachmentRejectsMissingOrNonfiniteFeet(){
        var tag=new CompoundTag();assertFalse(NativeSableBoarding.valid(tag));tag.putUUID("body",UUID.randomUUID());
        for(String field:List.of("x","y","z"))tag.putDouble(field,1);assertTrue(NativeSableBoarding.valid(tag));
        for(String field:List.of("x","y","z")){for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}){tag.putDouble(field,bad);assertFalse(NativeSableBoarding.valid(tag));}tag.putDouble(field,1);}
        tag.remove("body");assertFalse(NativeSableBoarding.valid(tag));assertEquals(200,NativeSableBoarding.LIMIT);
    }
    @Test void saveIsBeforePlayerSerializationAndRecordsPoseInverseFeet()throws Exception{
        var hook=source("mixin/NativeSablePlayerSaveMixin");assertTrue(hook.contains("method=\"saveWithoutId\",at=@At(\"HEAD\")"));assertTrue(hook.contains("instanceof ServerPlayer player"));
        var c=calls("NativeSableBoarding","save");for(String call:List.of("aboard","getPersistentData","transformPositionInverse","getUniqueId","putUUID","putDouble","remove"))assertTrue(c.contains(call),call);
        var s=source("NativeSableBoarding");for(String event:List.of("PlayerLoggedInEvent","PlayerLoggedOutEvent","PlayerChangedDimensionEvent","PlayerRespawnEvent","ServerStoppedEvent","PlayerTickEvent.Post"))assertTrue(s.contains(event));
        assertTrue(Files.readString(Path.of("src/main/resources/starfold.mixins.json")).contains("NativeSablePlayerSaveMixin"));
    }
    @Test void onlyRecordedPlayersAreHeldAndReleaseIsBoundedWithoutMutatingBaseGravity()throws Exception{
        var s=source("NativeSableBoarding");assertTrue(s.contains("if(!player.getPersistentData().contains(KEY))return"));assertTrue(s.contains("if(age==null)return"));assertTrue(s.contains("age>=LIMIT"));assertTrue(s.contains("player.isSpectator()||player.getAbilities().flying"));assertTrue(s.contains("NativeSableFrames.tracked(player,body)"));
        for(String name:List.of("addTransientModifier","removeModifier"))assertTrue(calls("NativeSableBoarding","weight").contains(name));
        for(String forbidden:List.of("setNoGravity","setBaseValue","addPermanentModifier","sable$setTrackingSubLevel"))assertFalse(s.contains(forbidden));
        var c=calls("NativeSableBoarding","tick");assertTrue(c.containsAll(List.of("getSubLevel","transformPosition","setPos","teleport","resetPosition","raw")));assertTrue(c.indexOf("teleport")<c.indexOf("raw"));
        assertTrue(calls("NativeSableBoarding","release").containsAll(List.of("remove","weight")));
    }
    @Test void boardingGravityIsTransientAndIndependentOfRegionAndOtherMods(){
        var gravity=new net.minecraft.world.entity.ai.attributes.AttributeInstance(net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY,h->{});gravity.setBaseValue(.12);
        var other=new net.minecraft.world.entity.ai.attributes.AttributeModifier(net.minecraft.resources.ResourceLocation.parse("test:gravity"),.04,net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE);gravity.addPermanentModifier(other);var saved=gravity.save();double normal=gravity.getValue();
        NativeSableBoarding.weight(gravity,true);NativeSableBoarding.weight(gravity,true);assertEquals(0,gravity.getValue());assertEquals(saved,gravity.save());assertEquals(2,gravity.getModifiers().size());
        NativeSpaceServer.weight(gravity,true);NativeSableBoarding.weight(gravity,false);assertEquals(0,gravity.getValue());NativeSpaceServer.weight(gravity,false);assertEquals(normal,gravity.getValue());assertEquals(.12,gravity.getBaseValue());assertTrue(gravity.hasModifier(other.id()));NativeSableBoarding.weight((net.minecraft.world.entity.ai.attributes.AttributeInstance)null,true);
    }
    @Test void restoreUsesCarryFenceButDoesNotChangeNormalSeamCarrySemantics()throws Exception{
        var s=source("NativeSableCarryClient");assertTrue(s.contains("apply(packet,false)"));assertTrue(s.contains("apply(packet.carry(),true)"));assertTrue(s.contains("if(boarding)return;throw new IllegalStateException"));assertTrue(s.contains("boarding?new Vector3d(packet.player().x()"));assertTrue(s.contains("if(!boarding)NativeSpaceClient.carried(packet.region())"));
        assertTrue(calls("NativeSableCarryClient","apply").containsAll(List.of("riderPoint","clear","forceUpdateBounds","setPos")));
        assertTrue(source("NativeSableBoardingPacket").contains("NativeSableCarry.CODEC"));
    }
}
