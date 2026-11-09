package dev.starfold.worldgen.plan;

import static org.junit.jupiter.api.Assertions.*;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.*;
import dev.starfold.worldgen.plan.terrain.TerrainPlan;
import dev.starfold.worldgen.surface.SurfaceFunction;
import dev.starfold.worldgen.lod.VoxelColumn;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

class SurfaceHeightLimitTest {
    @BeforeAll static void boot() { PlanetTerrainTest.registry(); }

    static PlanetTerrain archivedTerrain() throws Exception {
        try(var in=new DataInputStream(new GZIPInputStream(SurfaceHeightLimitTest.class.getResourceAsStream(
                "/planet-terrain/pelagia-20261005.bin.gz")))) {
            var fixed=TerrainPlan.read(in,1536);
            var terrain=new PlanetTerrain(20261005L,PlanetTerrain.VERSION,new PlanetGeneration("recipe",
                    PlanetGeneration.SURFACE_VERSION,fixed.recipe(),1536,489,"core_shell",true,false,false,
                    PlanetEnvironment.defaults(true,1536)));
            terrain.initializeTerrain(fixed);
            return terrain;
        }
    }

    @Test void archivedPelagiaLakeColumnMatchesTheActualGenerationCeiling() throws Exception {
        var terrain=archivedTerrain();var function=new SurfaceFunction(terrain);
        var face=Face.WEST;int x=360,z=449;
        assertEquals(TerrainPlan.Water.LAKE,terrain.terrainPlan().water(face,x,z));
        assertEquals(531.9938768488355,terrain.terrainPlan().waterLevel(face,x,z),1e-9);
        int ground=terrain.groundY(face,x,z);assertEquals(318,ground);
        var blocks=function.blocks(face,x,z);
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)) { for(var block:blocks) out.writeUTF(block.toString()); }
        var hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        // Captured before D: the cap repair must preserve every generated block in the failing column.
        assertEquals("7bbaae80e419b0bda5a33e1c1d1ed8bd61abc25e4c79487bccff6863489ebcc2",hash);
        assertEquals(Blocks.WATER.defaultBlockState(),blocks[319-terrain.floor()]);
        assertEquals(319,terrain.surfaceTop(face,x,z,ground));
        assertEquals(319,terrain.surface(face,x,z,ground).top());
        assertEquals(319,function.groundTop(face,x,z));
        assertEquals(319,function.point(face,x,z).height());
        var column=VoxelColumn.visible(function,face,x,z);
        assertSame(blocks[319-terrain.floor()],column.state(319));
        assertTrue(column.state(320).isAir());
    }

    @Test void clippedColdWaterDoesNotInventIceAboveTheRealCeiling() throws Exception {
        var original=archivedTerrain();var base=original.terrainPlan();
        var json=base.recipe().json();json.getAsJsonObject("water").addProperty("freeze",true);
        var recipe=new dev.starfold.worldgen.plan.terrain.TerrainRecipe(json);
        for(int level:new int[]{319,320,321,532}) {
            var fields=new float[6][base.nodeCount()];
            for(int i=0;i<base.nodeCount();i++)for(int f=0;f<6;f++)fields[f][i]=base.field(f,i);
            Arrays.fill(fields[TerrainPlan.WATER_LEVEL],level);Arrays.fill(fields[TerrainPlan.TEMPERATURE],-20);
            var water=new byte[base.nodeCount()];Arrays.fill(water,(byte)TerrainPlan.Water.LAKE.ordinal());
            var fixed=new TerrainPlan(base.seed(),1536,recipe,fields,new short[base.nodeCount()],water,
                    base.landforms(),List.of());
            var terrain=new PlanetTerrain(base.seed(),PlanetTerrain.VERSION,original.generation().withRecipe(recipe));
            terrain.initializeTerrain(fixed);var function=new SurfaceFunction(terrain);
            var blocks=function.blocks(Face.WEST,360,449);
            var surface=terrain.surface(Face.WEST,360,449,318);
            assertEquals(Math.min(319,Math.max(318,level-1)),surface.top());
            assertSame(blocks[surface.top()-terrain.floor()],surface.state(),"water level "+level);
            assertEquals(level==320,surface.state().is(Blocks.ICE));
        }
    }

    @Test void heightCorrectionIsConfinedToCapsAboveTheGeneratedRange() throws Exception {
        var terrain=archivedTerrain();var fixed=terrain.terrainPlan();
        int changed=0;
        for(var face:Face.VALUES)for(int z=4;z<1536;z+=16)for(int x=4;x<1536;x+=16) {
            if(terrain.closure(face,x,z)||fixed.localContent(face,x,z))continue;
            int ground=terrain.groundY(face,x,z);
            var liquid=terrain.liquid(face,x,z);
            int legacy=liquid==null?ground:Math.max(ground,liquid.level()-1);
            if(legacy==ground && (liquid==null||ground>=liquid.level()) && terrain.temperature(face,x,z)<0
                    && terrain.generation().environment().atmosphere().density()>0)legacy=ground+1;
            assertEquals(Math.min(319,legacy),terrain.surfaceTop(face,x,z,ground));
            if(legacy>319)changed++;
        }
        assertTrue(changed>0,"the archive includes overflowing lake caps");
        System.out.println("Pelagia overflow cap samples (one per 16x16 cell): "+changed);
    }
}
