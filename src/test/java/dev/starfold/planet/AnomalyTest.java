package dev.starfold.planet;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.chart.GroundOrbit;
import dev.starfold.chart.GroundSpace;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.render.GroundExteriorMesh;
import dev.starfold.worldgen.PlanetEcologyTest;
import dev.starfold.worldgen.PlanetTerrain;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

class AnomalyTest {
    @org.junit.jupiter.api.BeforeAll
    static void boot() {
        PlanetEcologyTest.boot();
    }

    @Test
    void litSeamsRepeatAndCloseTheTopOfTheDefaultClosure() {
        int lit = 0;
        for (int row = -8; row < GroundSpace.DEFAULT_MASK.height(); row++)
            if (Anomaly.band(row)) lit++;
        assertEquals(9, lit);
        assertTrue(Anomaly.band(GroundSpace.DEFAULT_MASK.height() - 1));
        assertFalse(Anomaly.band(0));
        assertTrue(Anomaly.band(-1));
    }

    @Test
    void spireNarrowsFromInsideTheClosureFootprintToItsTip() {
        assertTrue(Anomaly.BEAM_WIDTH > 0 && Anomaly.BEAM_WIDTH < GroundSpace.DEFAULT_MASK.core());
        assertEquals(Anomaly.BEAM_WIDTH, Anomaly.beamWidth(0));
        assertEquals(0, Anomaly.beamWidth(Anomaly.BEAM_HEIGHT));
        double prior = Anomaly.BEAM_WIDTH;
        for (int h = 0; h <= Anomaly.BEAM_HEIGHT; h += Anomaly.QUAD) {
            assertTrue(Anomaly.beamWidth(h) <= prior);
            prior = Anomaly.beamWidth(h);
        }
    }

    @Test
    void orbitalViewsDrawEachFacesQuarterOfTheSameSpireSelfLit() throws Exception {
        int foot = GroundSpace.DEFAULT_MASK.height(), total = 0;
        for (var f : CubeSurfaceTopology.Face.values())
            for (int x0 = 0; x0 < 2048; x0 += 256)
                for (int z0 = 0; z0 < 2048; z0 += 256) {
                    var beams = GroundExteriorMesh.anomalyBeams(f, x0, z0, foot);
                    total += beams.size();
                    for (var t : beams) {
                        assertEquals(
                                GroundOrbit.RADIUS + foot + Anomaly.BEAM_HEIGHT,
                                t[2].length(),
                                1e-6);
                        assertEquals(0, Anomaly.distance(t[2].scale(1 / t[2].length())), 1e-3);
                        assertEquals(GroundOrbit.RADIUS + foot, t[0].length(), 1e-6);
                        assertTrue(
                                Anomaly.distance(t[0].scale(1 / t[0].length()))
                                        <= GroundSpace.DEFAULT_MASK.core() * 1.5);
                    }
                }
        assertEquals(6 * 4 * 2, total);
        assertTrue(
                Files.readString(
                                Path.of(
                                        "src/main/resources/assets/starfold/shaders/core/native_ground_exterior.fsh"))
                        .contains("if(surfaceColor.a<0.5)light=1.0;"));
        for (String file : new String[] {"GroundExterior.java", "PlanetRemoteRenderer.java"}) {
            var source = Sources.text(file.substring(0, file.length() - 5));
            assertTrue(
                    source.contains(
                            "GroundExteriorMesh.anomalyBeams(shape,Face.values()[face],x0,z0,mask.height())"),
                    file);
            assertTrue(source.contains("Anomaly.GLOW_TINT&255,0)"), file);
        }
    }

    @Test
    void fieldIsOneCircleOnTheSphereSharedByTheThreeFaces() {
        for (var f : CubeSurfaceTopology.Face.values())
            for (int[] corner : new int[][] {{0, 0}, {2048, 0}, {0, 2048}, {2048, 2048}}) {
                int su = corner[0] == 0 ? 1 : -1, sv = corner[1] == 0 ? 1 : -1;
                assertEquals(
                        0,
                        Anomaly.distance(PlanetTerrain.direction(f, corner[0], corner[1])),
                        1e-3);
                assertEquals(
                        16,
                        Anomaly.distance(
                                PlanetTerrain.direction(f, corner[0] + su * 16, corner[1])),
                        .2);
                assertEquals(
                        16,
                        Anomaly.distance(
                                PlanetTerrain.direction(f, corner[0], corner[1] + sv * 16)),
                        .2);
                assertTrue(
                        Anomaly.distance(
                                        PlanetTerrain.direction(
                                                f, corner[0] + su * 16, corner[1] + sv * 16))
                                < Anomaly.FIELD);
            }
        assertNull(
                Anomaly.surface(
                        Anomaly.distance(
                                PlanetTerrain.direction(
                                        CubeSurfaceTopology.Face.TOP, 1024, 1024))));
        assertSame(Anomaly.ring(), Anomaly.surface(16.5));
        assertSame(Anomaly.ground(), Anomaly.surface(20.5));
        assertNull(Anomaly.surface(Anomaly.FIELD));
        assertEquals(Anomaly.PLATEAU, Anomaly.elevation(-20, Anomaly.PLATEAU_INNER));
        assertEquals(-20, Anomaly.elevation(-20, Anomaly.PLATEAU_OUTER));
    }

    @Test
    void everyGeneratorLevelsTheFieldLaysItsGroundAndGrowsNothingOnIt() {
        for (int version :
                new int[] {
                    PlanetTerrain.MINING_VERSION,
                    PlanetTerrain.ECOLOGY_VERSION,
                    PlanetTerrain.ROCK_VERSION
                }) {
            var t = new PlanetTerrain(PlanetTerrain.TEST_SEED, version);
            for (var f : CubeSurfaceTopology.Face.values())
                for (int u = 17; u < 34; u += 4)
                    for (int v = 17; v < 34; v += 4) {
                        var col = t.column(f, u, v);
                        int ground = col.ground();
                        assertTrue(
                                Math.abs(ground - 127 - Anomaly.PLATEAU) <= Anomaly.WARP + 1,
                                "" + ground);
                        assertTrue(ground >= PlanetTerrain.SEA_LEVEL);
                        var top = col.state(ground);
                        assertTrue(top == Anomaly.ground() || top == Anomaly.ring());
                        assertSame(top, t.surfaceState(f, u, v));
                        assertSame(Anomaly.ground(), col.state(ground - Anomaly.DEPTH));
                        assertTrue(col.state(ground + 1).isAir());
                        assertEquals(ground, t.surfaceY(f, u, v));
                    }
            var far = t.column(CubeSurfaceTopology.Face.TOP, 1024, 1024);
            assertNotSame(Anomaly.ground(), far.state(far.ground()));
        }
        var eco = new PlanetTerrain(PlanetTerrain.TEST_SEED, PlanetTerrain.ECOLOGY_VERSION);
        for (int u = 0; u < 40; u++)
            for (int v = 0; v < 40; v++)
                assertNull(eco.ecology().tree(CubeSurfaceTopology.Face.TOP, u, v));
    }

    @Test
    void groundIsWrungAroundTheVertexHarderTheNearerItLies() {
        assertEquals(0, Anomaly.twist(Anomaly.PLATEAU_OUTER));
        assertEquals(0, Anomaly.warp(Anomaly.PLATEAU_OUTER));
        assertEquals(Anomaly.TWIST, Anomaly.twist(0));
        assertEquals(Anomaly.WARP, Anomaly.warp(0));
        for (int d = 1; d <= Anomaly.PLATEAU_OUTER; d++) {
            assertTrue(Anomaly.twist(d - 1) > Anomaly.twist(d));
            assertTrue(Anomaly.warp(d - 1) > Anomaly.warp(d));
        }
        assertTrue(
                Anomaly.PLATEAU - Anomaly.warp(GroundSpace.DEFAULT_MASK.core()) > 1,
                "the field stays above the sea");
        for (var f : CubeSurfaceTopology.Face.values())
            for (int[] at : new int[][] {{30, 9}, {2000, 60}, {70, 2040}, {2030, 2001}}) {
                var unit = PlanetTerrain.direction(f, at[0], at[1]);
                double d = Anomaly.distance(unit);
                var turned = Anomaly.twisted(unit, d);
                assertEquals(1, turned.length(), 1e-12);
                assertEquals(d, Anomaly.distance(turned), 1e-6);
                assertTrue(turned.sub(unit).length() > 1e-3);
            }
        var rim = PlanetTerrain.direction(CubeSurfaceTopology.Face.TOP, 120, 0);
        assertEquals(0, Anomaly.twisted(rim, Anomaly.distance(rim)).sub(rim).length(), 1e-12);
        // Relief: rough near the closure.
        var t = new PlanetTerrain(PlanetTerrain.TEST_SEED, PlanetTerrain.MINING_VERSION);
        int low = 999, high = -999;
        for (int u = 0; u < 40; u++)
            for (int v = 0; v < 40; v++) {
                int y = t.groundY(CubeSurfaceTopology.Face.TOP, u, v);
                low = Math.min(low, y);
                high = Math.max(high, y);
            }
        assertTrue(high - low >= 5, low + ".." + high);
    }

    @Test
    void enteringTheZoneWarnsOnceAndKeepsThePlayerNauseous() throws Exception {
        var zone = Sources.text("AnomalyZone");
        assertTrue(zone.contains("MobEffects.CONFUSION"));
        assertTrue(zone.contains("if(inside.add(player.getUUID()))"));
        assertTrue(zone.contains("distance(level,access,player)<Anomaly.ZONE"));
        assertTrue(Sources.text("GroundServer").contains("AnomalyZone.tick(level);"));
        for (String lang : new String[] {"zh_cn", "en_us"}) {
            var text =
                    Files.readString(
                            Path.of("src/main/resources/assets/starfold/lang/" + lang + ".json"));
            for (String key :
                    new String[] {
                        "anomaly.starfold.enter",
                        "anomaly.starfold.enter.detail",
                        "anomaly.starfold.leave"
                    }) assertTrue(text.contains("\"" + key + "\""), lang + " " + key);
        }
    }

    @Test
    void materialsAreTheModsOwnBlocksWithTheirOwnTextures() {
        for (var block :
                new net.minecraft.world.level.block.Block[] {
                    Anomaly.SHELL_BLOCK,
                    Anomaly.GLOW_BLOCK,
                    Anomaly.GROUND_BLOCK,
                    Anomaly.RING_BLOCK
                }) {
            var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
            assertEquals("starfold", id.getNamespace());
            for (String file :
                    new String[] {
                        "blockstates/" + id.getPath() + ".json",
                        "models/block/" + id.getPath() + ".json",
                        "models/item/" + id.getPath() + ".json",
                        "textures/block/" + id.getPath() + ".png"
                    })
                assertTrue(
                        Files.exists(Path.of("src/main/resources/assets/starfold/" + file)), file);
        }
        assertSame(Anomaly.SHELL_BLOCK, Anomaly.shell().getBlock());
        assertSame(Anomaly.GLOW_BLOCK, Anomaly.glow().getBlock());
        assertSame(Anomaly.GROUND_BLOCK, Anomaly.ground().getBlock());
        assertSame(Anomaly.RING_BLOCK, Anomaly.ring().getBlock());
        assertEquals(15, Anomaly.glow().getLightEmission());
        assertTrue(Anomaly.ring().getLightEmission() > 0);
    }

    @Test
    void closureIsSolidOnBothSidesOfEveryEdgeAtAVertexAndNowhereElse() throws Exception {
        for (int in : new int[] {0, 15, -1, -16, 2047, 2032, 2048, 2063})
            assertTrue(Anomaly.closed(in, 16), "" + in);
        for (int out : new int[] {16, -17, 2031, 2064, 1024})
            assertFalse(Anomaly.closed(out, 16), "" + out);
        assertFalse(Anomaly.closed(0, 0));
        var client = Sources.text("GroundClient");
        assertTrue(
                client.contains(
                        "Anomaly.closed(PlanetShape.of(f.planet()),storage.getX()-(int)o.x(),config.core())&&Anomaly.closed(PlanetShape.of(f.planet()),storage.getZ()-(int)o.z(),config.core())"));
        assertTrue(client.contains("access().origin(f.planet(),f.root())"));
        assertTrue(
                Sources.text("GroundRenderer")
                        .contains(
                                "drawResidual(RenderFrame c){if(GroundFlightClient.controlled()||mask!=null&&mask.core()>0)return;"));
    }

    @Test
    void displayPathsTakeTheLookFromOneReplaceableDefinition() throws Exception {
        var renderer = Sources.text("GroundRenderer");
        var mask =
                renderer.substring(
                        renderer.indexOf("private static Mesh buildMask("),
                        renderer.indexOf(
                                "private static void render(RenderFrame c,int face,Mesh mesh,RenderType layer){"));
        assertTrue(mask.contains("Anomaly.shell()"));
        assertTrue(mask.contains("next-row<Anomaly.QUAD"));
        assertTrue(
                renderer.contains(
                        "masks[face]=buildMask(face,false);masks[face+6]=buildMask(face,true);"));
        assertTrue(
                renderer.contains(
                        "if(!orbitalChart&&face==GroundClient.face())render(c,face,masks[face+6],layer);"));
        assertTrue(
                renderer.contains("mesh!=masks[face]&&mesh!=masks[face+6]&&mesh!=stitches[face]"));
        assertTrue(mask.contains("above+=Anomaly.QUAD"));
        assertTrue(Anomaly.QUAD <= 4);
        assertTrue(mask.contains("Anomaly.glow()"));
        assertFalse(mask.contains("Blocks."));
        for (String file : new String[] {"GroundExterior.java", "PlanetRemoteRenderer.java"})
            assertTrue(
                    Sources.text(file.substring(0, file.length() - 5))
                            .contains("if(masked(x,z))state=Anomaly.shell();"),
                    file);
    }
}
