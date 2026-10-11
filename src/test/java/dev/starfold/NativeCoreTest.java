package dev.starfold;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import org.junit.jupiter.api.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeCoreTest {
    @BeforeAll static void boot(){NativePlanetTerrainTest.registry();}
    private static ClassNode type(String name)throws Exception{var node=new ClassNode();try(var in=TestArchiveResources.open("/dev/starfold/"+name+".class")){assertNotNull(in);new ClassReader(in).accept(node,0);}return node;}
    private static String source(String name)throws Exception{return Files.readString(Path.of("src/main/java/dev/starfold/"+name+".java"));}
    @Test void guardIsRequiredAtTheLiveChunkHeadBeforeAnyMutation()throws Exception{
        var node=type("mixin/NativeCoreChunkMixin");var target=node.invisibleAnnotations.stream().filter(a->a.desc.endsWith("/Mixin;")).findFirst().orElseThrow();assertTrue(target.values.toString().contains("Lnet/minecraft/world/level/chunk/LevelChunk;"));
        var method=node.methods.stream().filter(m->m.name.equals("starfold$coreWrite")).findFirst().orElseThrow();var inject=method.visibleAnnotations.stream().filter(a->a.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        assertEquals(List.of("setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;"),inject.values.get(inject.values.indexOf("method")+1));assertEquals(true,inject.values.get(inject.values.indexOf("cancellable")+1));
        var at=(AnnotationNode)((List<?>)inject.values.get(inject.values.indexOf("at")+1)).getFirst();assertEquals("HEAD",at.values.get(at.values.indexOf("value")+1));assertFalse(inject.values.contains("require"));
        var config=com.google.gson.JsonParser.parseString(Files.readString(Path.of("src/main/resources/starfold.mixins.json"))).getAsJsonObject();assertTrue(config.get("required").getAsBoolean());assertEquals(1,config.getAsJsonObject("injectors").get("defaultRequire").getAsInt());assertTrue(config.getAsJsonArray("mixins").asList().stream().anyMatch(v->v.getAsString().equals("NativeCoreChunkMixin")));
    }
    @Test void decisionReadsServerPlotDestinationAndExistingStateNotTheWriterOrFloor()throws Exception{
        var method=type("mixin/NativeCoreChunkMixin").methods.stream().filter(m->m.name.equals("starfold$coreWrite")).findFirst().orElseThrow();var calls=new ArrayList<String>();var fields=new ArrayList<String>();
        for(var i:method.instructions){if(i instanceof MethodInsnNode c)calls.add(c.owner+"."+c.name);if(i instanceof FieldInsnNode f)fields.add(f.owner+"."+f.name);if(i instanceof VarInsnNode v)assertNotEquals(3,v.var,"moved is not a bypass");}
        assertEquals(List.of("net/minecraft/world/level/chunk/LevelChunk.getLevel","net/minecraft/world/level/block/state/BlockState.is","dev/ryanhcode/sable/api/sublevel/SubLevelContainer.getContainer","net/minecraft/world/level/chunk/LevelChunk.getPos","dev/ryanhcode/sable/api/sublevel/SubLevelContainer.getPlot","org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable.setReturnValue","dev/starfold/NativeGroundServer.is","net/minecraft/world/level/chunk/LevelChunk.getBlockState","net/minecraft/world/level/block/state/BlockState.is","org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable.setReturnValue"),calls);
        assertEquals(List.of("net/minecraft/world/level/Level.isClientSide","dev/starfold/NativeCoreBlock.IMMUTABLE","dev/starfold/NativeCoreBlock.IMMUTABLE"),fields);
        var text=source("mixin/NativeCoreChunkMixin");assertTrue(text.contains("if(level.isClientSide)return;"));assertTrue(text.contains("if(!NativeGroundServer.is(level))return;"));assertTrue(text.contains("if(old.is(NativeCoreBlock.IMMUTABLE)&&old!=state)ci.setReturnValue(null);"));
        var call=Arrays.stream(method.instructions.toArray()).filter(i->i instanceof MethodInsnNode c&&c.name.equals("setReturnValue")).findFirst().orElseThrow();assertEquals(Opcodes.ACONST_NULL,call.getPrevious().getOpcode());
    }
    @Test void haloForwardsToTheOwnerAndPropagatesARefusedWrite()throws Exception{
        var text=source("NativeGhostChunk");assertTrue(text.contains("var old=owner.setBlockState(at(i,pos.getY()),ChartOrientation.state(state,turns(i)),moved);"));assertTrue(text.contains("return old==null?null:ChartOrientation.state(old,-turns(i));"));
    }
    @Test void shellVetoesEntitiesAndForcedExplosionCallbacksWithoutTouchingTheWorld()throws Exception{
        var tag=com.google.gson.JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/starfold/tags/block/immutable.json"))).getAsJsonObject();assertEquals(List.of("starfold:core_shell","starfold:anomaly_core"),tag.getAsJsonArray("values").asList().stream().map(v->v.getAsString()).toList());
        for(var shell:List.of(NativeCore.shell(),NativeAnomaly.core(128),NativeAnomaly.core(143))){assertInstanceOf(NativeCoreBlock.class,shell.getBlock());assertFalse(shell.canEntityDestroy(null,BlockPos.ZERO,null));assertDoesNotThrow(()->shell.onBlockExploded(null,BlockPos.ZERO,null));
        assertEquals(-1,shell.getBlock().defaultDestroyTime());assertTrue(shell.getBlock().getExplosionResistance()>=Blocks.BEDROCK.getExplosionResistance());assertSame(net.minecraft.world.level.material.PushReaction.BLOCK,shell.getPistonPushReaction());}
        assertEquals(NativeCore.LIGHT,NativeCore.shell().getLightEmission());assertEquals(15,NativeAnomaly.core(143).getLightEmission());
    }
    @Test void generationProtoChunksCanStillPlaceAndReplaceShell(){
        var chunk=new ProtoChunk(new ChunkPos(0,0),UpgradeData.EMPTY,NativePlanetTerrainTest.HEIGHT,NativePlanetTerrainTest.biomes,null);var pos=new BlockPos(3,-64,5);var shell=NativeCore.shell();
        chunk.setBlockState(pos,shell,false);assertSame(shell,chunk.getBlockState(pos));assertSame(shell,chunk.setBlockState(pos,Blocks.STONE.defaultBlockState(),false));assertTrue(chunk.getBlockState(pos).is(Blocks.STONE));chunk.setBlockState(pos,shell,false);assertSame(shell,chunk.getBlockState(pos));
    }
}
