package dev.starfold;

import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

/** A system is a list of planets of any length; the two it began with are the first two of it. */
class NativePlanetCountTest {
    final NativeSingleLevelStorageTest fixture=new NativeSingleLevelStorageTest();
    @BeforeAll static void boot(){NativePlanetEcologyTest.boot();}
    NativePlanetChunkGenerator third(){
        var settings=new net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings(Optional.of(net.minecraft.core.HolderSet.direct()),NativePlanetEcologyTest.palette.getHolderOrThrow(Biomes.DESERT),List.of());
        settings.getLayersInfo().add(new net.minecraft.world.level.levelgen.flat.FlatLayerInfo(1,Blocks.BEDROCK));settings.updateLayers();
        return new NativePlanetChunkGenerator(settings,20261003L,5,Optional.empty(),Optional.of(new NativePlanetGeneration("rocky","rocky_craters",1,1.2,.04,.09,512)));
    }
    NativeSystemChunkGenerator three(){var two=fixture.generator();return new NativeSystemChunkGenerator(NativePlanetStorage.LAYOUT,List.of(two.planet(0),two.planet(1),third()));}
    @Test void aSystemOfThreeGeneratesEachPlanetInItsOwnStorageAndNothingBeyondASmallerFace(){
        var g=three();assertEquals(3,g.count());assertThrows(IllegalArgumentException.class,()->g.planet(3));
        NativePlanetShape.system(new int[]{2048,2048,512},new int[]{191,191,191});
        try{
            // The third planet is the first of the second row: under the first, a row stride along z.
            int row=NativePlanetStorage.ROW_STRIDE/16,rowBlocks=NativePlanetStorage.ROW_STRIDE;assertEquals(0,NativePlanetStorage.offset(2));assertEquals(rowBlocks,NativePlanetStorage.offsetZ(2));
            for(int face=0;face<6;face++){
                var chunk=fixture.chunk(g,face*256+8,row+8);int solid=0;for(int y=-64;y<200;y++)if(!chunk.getBlockState(new BlockPos(chunk.getPos().getMinBlockX()+3,y,rowBlocks+131)).isAir())solid++;
                assertTrue(solid>100,"face "+face+" of the third planet has ground");
                assertSame(Blocks.BEDROCK,chunk.getBlockState(new BlockPos(chunk.getPos().getMinBlockX(),-64,rowBlocks+128)).getBlock());
                assertTrue(chunk.getNoiseBiome(1,40,33).is(Biomes.DESERT),"the third planet's own biome");
                assertTrue(fixture.chunk(g,face*256+40,row+8).getBlockState(new BlockPos((face*256+40)*16,-64,rowBlocks+128)).isAir(),"nothing beyond a face of 512");
                assertTrue(fixture.chunk(g,face*256+8,row+40).getBlockState(new BlockPos((face*256+8)*16,-64,rowBlocks+640+8)).isAir(),"nor beyond it along z");
            }
            assertTrue(fixture.chunk(g,NativePlanetStorage.PLANET_STRIDE/16+8,row+8).getBlockState(new BlockPos(NativePlanetStorage.PLANET_STRIDE+128,-64,rowBlocks+128)).isAir(),"no fourth planet");
            assertTrue(fixture.chunk(g,2*NativePlanetStorage.PLANET_STRIDE/16+8,8).getBlockState(new BlockPos(2*NativePlanetStorage.PLANET_STRIDE+128,-64,128)).isAir(),"nothing where a row of three would have put it");
            // The same ground as the planet alone makes of the same place.
            var alone=g.planet(2);for(int x=3;x<500;x+=97)for(int z=5;z<500;z+=89)assertEquals(alone.getBaseHeight(x,z,net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,NativePlanetEcologyTest.HEIGHT,null),g.getBaseHeight(x,rowBlocks+z,net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,NativePlanetEcologyTest.HEIGHT,null));
        }finally{NativePlanetShape.reset();}
    }
    @Test void planetsStandInASquareThatGrowsAShellAtATimeAndEachKeepsItsPlace(){
        assertEquals(0,NativePlanetStorage.column(0));assertEquals(0,NativePlanetStorage.row(0));assertEquals(1,NativePlanetStorage.column(1));assertEquals(0,NativePlanetStorage.row(1));
        assertEquals(0,NativePlanetStorage.column(2));assertEquals(1,NativePlanetStorage.row(2));assertEquals(1,NativePlanetStorage.column(3));assertEquals(1,NativePlanetStorage.row(3));assertEquals(2,NativePlanetStorage.column(4));assertEquals(0,NativePlanetStorage.row(4));
        var seen=new java.util.HashSet<Long>();for(int planet=0;planet<5000;planet++){int column=NativePlanetStorage.column(planet),row=NativePlanetStorage.row(planet);assertTrue(seen.add(column*100000L+row));assertEquals(planet,NativePlanetStorage.index(column,row));
            int shell=(int)Math.floor(Math.sqrt(planet));assertEquals(shell,Math.max(column,row),"a square of n holds the first n*n");}
        int last=NativePlanetStorage.MAX_PLANETS-1;assertEquals(NativePlanetStorage.SIDE-1,NativePlanetStorage.column(last));assertEquals(NativePlanetStorage.SIDE-1,NativePlanetStorage.row(last));assertEquals(-1,NativePlanetStorage.index(NativePlanetStorage.SIDE,0));assertEquals(-1,NativePlanetStorage.index(-1,0));
        assertThrows(IllegalArgumentException.class,()->NativePlanetStorage.column(NativePlanetStorage.MAX_PLANETS));
        // The furthest storage lies inside the world border, and no planet's charts reach its neighbours'.
        assertTrue((long)NativePlanetStorage.SIDE*NativePlanetStorage.PLANET_STRIDE<29_999_984L);assertTrue(NativePlanetStorage.PLANET_STRIDE>=2048+6*4096);assertTrue(NativePlanetStorage.ROW_STRIDE>=2048+4096);
    }
    @Test void storageAddressesEveryPlanetOfTheRunningSystemAndNoMore(){
        assertEquals(2,NativePlanetShape.count());assertThrows(IllegalArgumentException.class,()->NativePlanetStorage.offset(2));assertThrows(IllegalArgumentException.class,()->NativePlanetStorage.planet(10,NativePlanetStorage.ROW_STRIDE+10));assertEquals(-1,NativePlanetStorage.find(10,NativePlanetStorage.ROW_STRIDE+10));
        int[] sizes=new int[]{2048,1024,512,1024,768},depths=new int[]{191,326,191,191,191};NativePlanetShape.system(sizes,depths);
        try{
            assertEquals(5,NativePlanetShape.count());assertEquals(512,NativePlanetShape.of(2).size());assertEquals(326,NativePlanetShape.depth(1));assertThrows(IllegalArgumentException.class,()->NativePlanetShape.of(5));
            for(int planet=0;planet<5;planet++){int ox=NativePlanetStorage.offset(planet),oz=NativePlanetStorage.offsetZ(planet);assertEquals(NativePlanetStorage.column(planet)*NativePlanetStorage.PLANET_STRIDE,ox);assertEquals(NativePlanetStorage.row(planet)*NativePlanetStorage.ROW_STRIDE,oz);
                assertEquals(planet,NativePlanetStorage.planet(ox+10.5,oz+10.5));assertEquals(planet,NativePlanetStorage.planet(ox-100.0,oz+10.5),"the halo before its first face");assertEquals(planet,NativePlanetStorage.planet(ox+10.5,oz-100.0),"and the halo above it");
                long key=NativePlanetStorage.key(ox/16+256+3,oz/16+5);assertEquals(planet*(long)NativePlanetStorage.CHUNKS_PER_PLANET+dev.starfold.storage.NativeChunkKeys.local(256+3,5),key);assertEquals(new net.minecraft.world.level.ChunkPos(ox/16+256+3,oz/16+5),NativePlanetStorage.chunk(key));
                var query=new BlockPos(ox+100,64,oz+100);var address=NativePlanetStorage.address(query);assertEquals(planet,address.owner().planet());assertEquals(query.getX(),address.owner().block().getX());assertEquals(query.getZ(),address.owner().block().getZ());
                // A step off any edge of a face is another face of the same planet, never a neighbour's.
                assertEquals(planet,NativePlanetStorage.address(new BlockPos(ox-1,64,oz+100)).owner().planet());assertEquals(planet,NativePlanetStorage.address(new BlockPos(ox+100,64,oz-1)).owner().planet());assertEquals(planet,NativePlanetStorage.address(new BlockPos(ox+100,64,oz+sizes[planet])).owner().planet());
                assertEquals(new BlockPos(100,64,100),NativePlanetStorage.local(planet,query));var pose=NativePlanetStorage.body(planet,new dev.starfold.BentStripGeometry.Point(ox+100.5,140,oz+100.5));assertEquals(new dev.starfold.BentStripGeometry.Point(ox+100.5,140,oz+100.5),NativePlanetStorage.feet(planet,pose));
                var access=NativePlanetAccess.SHARED;assertTrue(access.inChart(query));assertEquals(planet,access.storagePlanet(query));assertEquals(sizes[planet],access.shape(query).size());assertEquals(0,access.face(query.getX()));assertEquals(3,access.face(ox+3*4096+50));
                assertEquals(new dev.starfold.BentStripGeometry.Point(ox+4096,128,oz),access.origin(planet,1));assertEquals(new dev.starfold.BentStripGeometry.Point(50.5,12,60.5),access.chart(new dev.starfold.BentStripGeometry.Point(ox+4096+50.5,140,oz+60.5)));}
            assertEquals(-1,NativePlanetStorage.key(256+32,NativePlanetStorage.ROW_STRIDE/16+5),"beyond a face of 512");assertEquals(-1,NativePlanetStorage.key(256+3,NativePlanetStorage.ROW_STRIDE/16+32+5),"along z too");assertThrows(IllegalArgumentException.class,()->NativePlanetStorage.chunk(5L*NativePlanetStorage.CHUNKS_PER_PLANET));
            // Two planets of one column, or of one row, are not the same planet.
            var access=NativePlanetAccess.SHARED;assertFalse(access.samePlanet(10,10,10,NativePlanetStorage.ROW_STRIDE+10));assertFalse(access.samePlanet(10,10,NativePlanetStorage.PLANET_STRIDE+10,10));assertTrue(access.samePlanet(10,10,4096+300,-50));
            assertNull(access.image(10.5,10.5,new dev.starfold.BentStripGeometry.Point(4096+10.5,140,NativePlanetStorage.ROW_STRIDE+10.5)),"a place of the planet below is not drawn in this one's chart");
            assertEquals(-1,NativePlanetStorage.find(10,5000),"between two rows");assertEquals(-1,NativePlanetStorage.find(27000,10),"between two columns");assertFalse(access.inChart(new BlockPos(10,128,5000)));
        }finally{NativePlanetShape.reset();}
        assertEquals(2,NativePlanetShape.count());
        assertThrows(IllegalArgumentException.class,()->NativePlanetShape.system(new int[0],new int[0]));assertThrows(IllegalArgumentException.class,()->NativePlanetShape.system(new int[]{2048},new int[]{191,191}));assertThrows(IllegalArgumentException.class,()->NativePlanetShape.system(new int[]{2048,500},new int[]{191,191}));
    }
    @Test void aRowOfTwoIsStillReadAndAThirdPlanetNeedsTheSquare()throws Exception{
        var two=fixture.generator();assertEquals(2,new NativeSystemChunkGenerator(NativePlanetStorage.ROW_LAYOUT,two.planet(0),two.planet(1)).count());
        assertThrows(IllegalArgumentException.class,()->new NativeSystemChunkGenerator(NativePlanetStorage.ROW_LAYOUT,List.of(two.planet(0),two.planet(1),third())));
        var ops=net.minecraft.resources.RegistryOps.create(JsonOps.INSTANCE,NativePlanetEcologyTest.lookup);var row=NativeSystemChunkGenerator.CODEC.codec().encodeStart(ops,new NativeSystemChunkGenerator(NativePlanetStorage.ROW_LAYOUT,two.planet(0),two.planet(1))).getOrThrow().getAsJsonObject();assertEquals("six_face_x32768_v1",row.get("storage_layout").getAsString());
        assertEquals("six_face_square_v2",NativeSystemChunkGenerator.CODEC.codec().encodeStart(ops,three()).getOrThrow().getAsJsonObject().get("storage_layout").getAsString());
        for(var preset:List.of("native_trio","native_stars","native_universe"))assertTrue(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/data/starfold/worldgen/world_preset/"+preset+".json")).contains("\"storage_layout\":\"six_face_square_v2\""),preset);
        for(var preset:List.of("native_system","native_exploration"))assertTrue(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/data/starfold/worldgen/world_preset/"+preset+".json")).contains("\"storage_layout\":\"six_face_x32768_v1\""),preset);
    }
    @Test void aSystemIsSavedAsItsNamedPairOrAsAListAndNeverBoth(){
        var ops=net.minecraft.resources.RegistryOps.create(JsonOps.INSTANCE,NativePlanetEcologyTest.lookup);var codec=NativeSystemChunkGenerator.CODEC.codec();
        var pair=codec.encodeStart(ops,fixture.generator()).getOrThrow().getAsJsonObject();assertTrue(pair.has("a")&&pair.has("b"));assertFalse(pair.has("planets"));
        var list=codec.encodeStart(ops,three()).getOrThrow().getAsJsonObject();assertFalse(list.has("a")||list.has("b"));assertEquals(3,list.getAsJsonArray("planets").size());
        var back=codec.parse(ops,list).getOrThrow();assertEquals(3,back.count());assertEquals(512,back.planet(2).terrain().shape().size());assertEquals(20261003L,back.planet(2).terrain().seed());assertEquals(list,codec.encodeStart(ops,back).getOrThrow());
        var both=list.deepCopy();both.add("a",pair.get("a"));assertThrows(IllegalArgumentException.class,()->codec.parse(ops,both));
        var neither=list.deepCopy();neither.remove("planets");assertThrows(IllegalArgumentException.class,()->codec.parse(ops,neither));
        var one=list.deepCopy();one.getAsJsonArray("planets").remove(2);one.getAsJsonArray("planets").remove(1);assertThrows(IllegalArgumentException.class,()->codec.parse(ops,one));
        // A list may hold any of the planet policies in any order; the named pair stays the temperate and the rocky one.
        assertEquals(2,new NativeSystemChunkGenerator(NativePlanetStorage.LAYOUT,List.of(third(),third())).count());
    }
    @Test void theClientIsToldEveryPlanetsShapeAndDepth(){
        var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY);
        try{
            NativeGroundPackets.Shapes.CODEC.encode(buffer,new NativeGroundPackets.Shapes(new int[]{2048,1024,512},new int[]{652,326,191}));
            var read=NativeGroundPackets.Shapes.CODEC.decode(buffer);assertArrayEquals(new int[]{2048,1024,512},read.sizes());assertArrayEquals(new int[]{652,326,191},read.depths());
            assertEquals(3,NativePlanetShape.count(),"takes effect as it is decoded");assertEquals(326,NativePlanetShape.depth(1));
            assertThrows(IllegalArgumentException.class,()->new NativeGroundPackets.Shapes(new int[]{2048,100},new int[]{191,191}));assertThrows(IllegalArgumentException.class,()->new NativeGroundPackets.Shapes(new int[]{2048},new int[0]));
        }finally{NativePlanetShape.reset();}
    }
    @Test void theModelCarriesEveryPlanetOfASystemAndTheFirstTwoAreWhereTheyWere(){
        var two=NativePlanetSystem.model();var three=NativePlanetSystem.model(3);assertEquals(2,two.count());assertEquals(3,three.count());assertEquals(two,NativePlanetSystem.model(2));
        for(long tick:new long[]{0,777,48000,1234567})for(int id=0;id<2;id++){assertEquals(two.at(id,tick).center(),three.at(id,tick).center());assertEquals(two.at(id,tick).velocity(),three.at(id,tick).velocity());}
        assertThrows(IllegalArgumentException.class,()->two.at(2,0));assertThrows(IllegalArgumentException.class,()->three.at(3,0));assertThrows(IllegalArgumentException.class,()->NativePlanetSystem.model(1));
        // The third planet circles the same sun a ring further out, and never comes near the first two.
        double ring=two.radius()+NativePlanetSystem.RING;
        for(long tick=0;tick<400000;tick+=3371){var c=three.at(2,tick);assertEquals(ring,c.center().sub(three.center()).length(),1e-6);assertEquals(2*Math.PI*ring/three.more().get(0).period(),c.velocity().length(),1e-9);
            for(int id=0;id<2;id++)assertTrue(c.center().sub(three.at(id,tick).center()).length()>NativePlanetSystem.RING-1);}
        var step=three.at(2,1000.5).center().sub(three.at(2,999.5).center());assertEquals(0,step.sub(three.at(2,1000).velocity()).length(),1e-6,"velocity is the motion of the centre");
        assertTrue(three.extent()>=ring+8192);assertEquals(GroundTransit.SPACE_RADIUS,two.extent());assertTrue(three.drift()>=two.drift());assertEquals(two.turn(),three.turn());
        assertSame(three.more().get(0).spin(),three.spin(2));
    }
    @Test void aLargerModelIsSavedSentAndSpunWhole(){
        var three=NativePlanetSystem.model(4);var tag=GroundEphemerisSave.modelTag(three);assertEquals(2,tag.getList("more",10).size());assertEquals(three,GroundEphemerisSave.model(tag));
        assertFalse(GroundEphemerisSave.modelTag(NativePlanetSystem.model()).contains("more"),"a pair is saved as it always was");
        var spun=GroundEphemeris.spinning(three,false,500);assertEquals(4,spun.count());assertFalse(spun.spin(3).enabled());assertEquals(three.at(3,500).center(),spun.at(3,500).center());
        assertThrows(IllegalArgumentException.class,()->new GroundEphemeris.Model(three.center(),three.axis(),three.radial(),three.radius(),three.period(),three.phase(),three.epoch(),three.a(),three.b(),three.sun(),false,three.more()));
    }
    @Test void aVesselBelongsToTheNearestOfAllPlanetsAndNamesRunOn(){
        var m=NativePlanetSystem.model(3);long tick=1000;var a=m.at(0,tick).center();var c=m.at(2,tick).center();
        assertEquals(0,NativeGroundDynamics.select(m,a.add(new dev.starfold.BentStripGeometry.Point(0,3000,0)),0,tick));
        assertEquals(2,NativeGroundDynamics.select(m,c.add(new dev.starfold.BentStripGeometry.Point(0,3000,0)),0,tick));assertEquals(2,NativeGroundDynamics.select(m,c.add(new dev.starfold.BentStripGeometry.Point(0,3000,0)),1,tick));
        var middle=a.add(c).scale(.5);assertEquals(0,NativeGroundDynamics.select(m,middle,0,tick),"the one held is kept at the midpoint");assertEquals(2,NativeGroundDynamics.select(m,middle,2,tick));
        var two=NativePlanetSystem.model();for(int id=0;id<2;id++)assertEquals(id,NativeGroundDynamics.select(two,two.at(id,tick).center().add(new dev.starfold.BentStripGeometry.Point(0,2000,0)),1-id,tick));
        assertEquals("A",NativePlanetSystem.name(0));assertEquals("C",NativePlanetSystem.name(2));assertEquals("Z",NativePlanetSystem.name(25));assertEquals("P26",NativePlanetSystem.name(26));
    }
    @Test void anyPlanetOfTheRunningSystemCanBeSteeredForAndNoOther(){
        assertThrows(IllegalArgumentException.class,()->new GroundGuidance.Options(2,false,false));
        NativePlanetShape.system(new int[]{2048,1024,512},new int[]{191,191,191});
        try{
            var m=NativePlanetSystem.model(3);var options=new GroundGuidance.Options(2,true,false);assertEquals(2,options.target());assertThrows(IllegalArgumentException.class,()->new GroundGuidance.Options(3,false,false));
            var pose=new NativeGroundTopology.Pose(CubeSurfaceTopology.Face.values()[0],new dev.starfold.BentStripGeometry.Point(256,400,256));
            var state=NativeGroundDynamics.guidance(NativeGroundDynamics.local(100,2,pose,GroundEphemeris.ZERO,new org.joml.Quaterniond(),true,NativeGroundDynamics.OFF),options);
            assertEquals(2,state.planet());assertEquals(state,NativeGroundNavigationSave.decode(NativeGroundNavigationSave.encode(state)));
            var marker=new NativeWaypoint("c_base",2,new NativeGroundTopology.Pose(CubeSurfaceTopology.Face.values()[0],new dev.starfold.BentStripGeometry.Point(100,130,100)));assertEquals(marker,NativeWaypointCodec.decode(NativeWaypointCodec.encode(marker)));
        }finally{NativePlanetShape.reset();}
    }
    @Test void theTrioPresetIsAListOfThreeWithASmallThirdPlanet()throws Exception{
        var json=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/data/starfold/worldgen/world_preset/native_trio.json"))).getAsJsonObject().getAsJsonObject("dimensions").getAsJsonObject("starfold:native_planet");
        assertEquals("starfold:native_deep",json.get("type").getAsString());var g=NativeSystemChunkGenerator.CODEC.codec().parse(net.minecraft.resources.RegistryOps.create(JsonOps.INSTANCE,NativePlanetEcologyTest.lookup),json.get("generator")).getOrThrow();
        assertEquals(3,g.count());assertEquals(2048,g.planet(0).terrain().shape().size());assertEquals(1024,g.planet(1).terrain().shape().size());assertEquals(512,g.planet(2).terrain().shape().size());assertEquals(-528,g.getMinY());
        assertEquals(191,g.planet(2).generation().depth());assertTrue(g.planet(2).generation().coreShell());assertNotEquals(g.planet(1).terrain().seed(),g.planet(2).terrain().seed());
        assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of("src/main/resources/data/minecraft/tags/worldgen/world_preset/extended.json")));
    }
    @Test void aUniverseOfSeveralSunsKeepsEachPlanetAboutItsOwnAndLightsEachPlaceByTheNearest(){
        var one=NativePlanetSystem.model(3);assertEquals(one,NativePlanetSystem.model(new int[]{3}));assertTrue(one.stars().isEmpty());assertEquals(1,one.everyStar().size());
        var m=NativePlanetSystem.model(new int[]{2,2});assertEquals(4,m.count());assertEquals(1,m.stars().size());assertEquals(2,m.everyStar().size());
        var two=NativePlanetSystem.model();for(long tick:new long[]{0,999,123456})for(int id=0;id<2;id++)assertEquals(two.at(id,tick).center(),m.at(id,tick).center());
        var far=m.stars().get(0);assertEquals(NativePlanetSystem.STAR_GAP,far.position().sub(m.sun().position()).length(),1e-9);
        for(long tick=0;tick<300000;tick+=4111){assertEquals(m.radius(),m.at(2,tick).center().sub(far.position()).length(),1e-6);assertEquals(m.radius()+NativePlanetSystem.RING,m.at(3,tick).center().sub(far.position()).length(),1e-6);
            for(int id=0;id<2;id++)assertSame(m.everyStar().get(0).getClass(),m.star(m.at(id,tick).center()).getClass());
            for(int id=0;id<2;id++)assertEquals(m.sun().position(),m.star(m.at(id,tick).center()).position());for(int id=2;id<4;id++)assertEquals(far,m.star(m.at(id,tick).center()));}
        assertTrue(m.extent()>=NativePlanetSystem.STAR_GAP+8192);
        assertEquals(m,GroundEphemerisSave.model(GroundEphemerisSave.modelTag(m)));assertFalse(GroundEphemerisSave.modelTag(one).contains("stars"));assertEquals(m.stars(),GroundEphemeris.spinning(m,false,10).stars());
        assertThrows(IllegalArgumentException.class,()->NativePlanetSystem.model(new int[]{1,3}));assertThrows(IllegalArgumentException.class,()->NativePlanetSystem.model(new int[]{2,0}));assertThrows(IllegalArgumentException.class,()->NativePlanetSystem.model(new int[0]));
    }
    @Test void aSystemSavesHowItsPlanetsAreSharedOutAmongItsSuns()throws Exception{
        var ops=NativePlanetTypeTest.ops();var codec=NativeSystemChunkGenerator.CODEC.codec();
        var json=NativePlanetTypeTest.preset("native_stars");
        var g=codec.parse(ops,json).getOrThrow();assertEquals(8,g.count());assertArrayEquals(new int[]{2,2,2,2},g.groups());assertEquals(768,g.planet(2).terrain().shape().size());assertEquals(8,NativePlanetSystem.model(g.groups()).count());
        var saved=codec.encodeStart(ops,g).getOrThrow().getAsJsonObject();assertEquals(4,saved.getAsJsonArray("stars").size());assertArrayEquals(new int[]{2,2,2,2},codec.parse(ops,saved).getOrThrow().groups());
        // More planets about one sun than the gap to the next has room for are refused: their circles would cross.
        assertThrows(IllegalArgumentException.class,()->NativePlanetSystem.model(new int[]{2,6}));assertThrows(IllegalArgumentException.class,()->NativePlanetSystem.model(new int[]{2,3,3}));assertEquals(5,NativePlanetSystem.model(new int[]{3,2}).count());
        var plain=net.minecraft.resources.RegistryOps.create(JsonOps.INSTANCE,NativePlanetEcologyTest.lookup);assertFalse(codec.encodeStart(plain,three()).getOrThrow().getAsJsonObject().has("stars"),"one sun is not written");assertArrayEquals(new int[]{3},three().groups());assertArrayEquals(new int[]{2},fixture.generator().groups());
        var wrong=saved.deepCopy();wrong.getAsJsonArray("stars").set(1,new com.google.gson.JsonPrimitive(3));assertThrows(IllegalArgumentException.class,()->codec.parse(ops,wrong));
        var pair=codec.encodeStart(plain,fixture.generator()).getOrThrow().getAsJsonObject();pair.add("stars",saved.get("stars"));assertThrows(IllegalArgumentException.class,()->codec.parse(plain,pair));
        assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of("src/main/resources/data/minecraft/tags/worldgen/world_preset/extended.json")));
    }
    @Test void theSystemIsToldBeforeTheFirstFrameThatNamesOneOfItsPlanets()throws Exception{
        var frames=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/starfold/NativeFrames.java"));int told=frames.indexOf("NativeGroundServer.shapes(player)"),sent=frames.indexOf("new NativeGroundFlightPackets.Frame(frame)");assertTrue(told>0&&told<sent,"shapes go out before the frame");
        // What the client would otherwise refuse: a frame on a third planet while it still counts two.
        assertThrows(IllegalArgumentException.class,()->NativeFrame.identity(2,0));NativePlanetShape.system(new int[]{2048,1024,512},new int[]{191,191,191});try{assertEquals(2,NativeFrame.identity(2,0).planet());}finally{NativePlanetShape.reset();}
    }
    @Test void eachPlanetHasASurfaceIndexOfItsOwn()throws Exception{
        assertTrue(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/starfold/NativeGroundSurfaceIndex.java")).contains("if(Files.size(path)<4096)continue;"),"a region file still being made is passed over");
        assertEquals(NativeGroundSurfaceIndex.FILE,NativeGroundSurfaceIndex.file(0));assertEquals(NativeGroundSurfaceIndex.FILE+"_planet_b",NativeGroundSurfaceIndex.file(1));
        var names=new java.util.HashSet<String>();for(int id=0;id<40;id++)assertTrue(names.add(NativeGroundSurfaceIndex.file(id)),"planet "+id);
    }
}
