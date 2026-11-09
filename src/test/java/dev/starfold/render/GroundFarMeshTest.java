package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrainTest;
import dev.starfold.worldgen.lod.*;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import java.util.*;

class GroundFarMeshTest {
    static final PlanetShape SHAPE=PlanetShape.STANDARD;
    @BeforeAll static void boot() { PlanetTerrainTest.registry(); }
    static double min(VoxelMesher.Quad q) { return q.points().stream().mapToDouble(Point::y).min().orElseThrow(); }
    static double max(VoxelMesher.Quad q) { return q.points().stream().mapToDouble(Point::y).max().orElseThrow(); }
    static GroundFarMesh.Surface tree(int step) {
        return GroundFarMesh.Surface.voxel(new VoxelColumn(List.of(
                new VoxelColumn.Run(-64,128,Blocks.GRASS_BLOCK.defaultBlockState()),
                new VoxelColumn.Run(144,152,Blocks.OAK_LEAVES.defaultBlockState()))));
    }
    @Test void remotePlanetsUseScreenSizedMipsInsteadOfTheResidentLayersL16Floor() throws Exception {
        int step = GroundFarMesh.remoteLevel(1900, 64, 70, 720);
        assertEquals(4, step);
        assertTrue(VoxelLodPolicy.pixels(step, 1900, 70, 720) <= 1.25);
        assertEquals(16, GroundFarMesh.level(1900, 64, 70, 720));
        assertEquals(4, GroundFarMesh.remoteLevel(0, 4, 70, 720));
        assertEquals(64, GroundFarMesh.remoteLevel(100000, 4, 70, 720));
        var remote = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/starfold/render/PlanetRemoteRenderer.java"));
        assertTrue(remote.contains("body.requestedStep = GroundFarMesh.remoteLevel("));
        var meshes = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/starfold/render/RemotePlanetMeshes.java"));
        assertTrue(meshes.contains("GroundAlbedo.position(face, q.points())"));
        assertTrue(meshes.contains("net.minecraft.core.Direction.getNearest("));
    }

    @Test void distanceIsNearestSphericalPatchNotCentreOrHorizontalRange() {
        for(var face:Face.values()) {
            int patch=face.ordinal()*64+3+3*8;
            var camera=SHAPE.direction(face,770,800).scale(SHAPE.radius()+2100);
            assertEquals(2100,GroundFarMesh.distance(SHAPE,camera,patch,0),1e-7);
            assertEquals(2000,GroundFarMesh.distance(SHAPE,camera,patch,100),1e-7);
        }
    }
    @Test void everyLevelKeepsAirGapsAndBoxFacesInsteadOfHeightfieldRamps() {
        for(int step:new int[]{1,2,4,8,16,32,64}) {
            var mesh=GroundFarMesh.build(SHAPE,Face.TOP,256,256,Math.max(16,step),step,(f,x,z,s)->tree(s));
            assertTrue(mesh.stream().anyMatch(q->q.material().is(Blocks.OAK_LEAVES)));
            assertTrue(mesh.stream().filter(q->q.material().is(Blocks.OAK_LEAVES)).flatMap(q->q.points().stream()).allMatch(p->p.y()>=16 && p.y()<=24));
            for(var q:mesh) {
                assertTrue(q.points().stream().allMatch(p->p.x()==q.points().getFirst().x())
                        ||q.points().stream().allMatch(p->p.y()==q.points().getFirst().y())
                        ||q.points().stream().allMatch(p->p.z()==q.points().getFirst().z()));
                assertTrue(q.rgb()==-1 || step>=4 && (q.rgb()==-2 || q.rgb()==-3));
            }
        }
    }
    @Test void sameLevelNeighbourIntervalsCullOnlyBuriedSides() {
        var grass=Blocks.GRASS_BLOCK.defaultBlockState();
        var mesh=VoxelMesher.boxes(0,0,32,32,16,(x,z)->new VoxelColumn(List.of(new VoxelColumn.Run(-64,128,grass))));
        assertEquals(4,mesh.stream().filter(q->q.axis()==0 || q.axis()==1 || q.axis()==4 || q.axis()==5).count());
        assertEquals(6, mesh.size(), "a solid plateau greedily merges to one box");
        assertTrue(mesh.stream().filter(q->q.axis()==0).allMatch(q->q.points().getFirst().x()==0));
    }
    @Test void everyPerimeterHasFullVerticalClosureForMixedLevelNeighboursOnAllFaces() {
        for(var face:Face.values()) for(int step:new int[]{1,2,4,8,16,32,64}) {
            int width=Math.max(16,step);
            var mesh=GroundFarMesh.build(SHAPE,face,0,64,width,step,(f,x,z,s)->tree(s));
            assertTrue(mesh.stream().anyMatch(q->GroundFarMesh.side(q)==0 && min(q)==-192 && max(q)==0));
            assertTrue(mesh.stream().anyMatch(q->GroundFarMesh.side(q)==0 && min(q)==16 && max(q)==24));
        }
    }
    @Test void arbitrarySixteenAlignedPlanetSizesClipBoxes() {
        var quads=GroundFarMesh.build(new PlanetShape(272),9,64,(f,x,z,s)->tree(s));
        for(var q:quads) for(var p:q.points()) {
            assertTrue(p.x()>=256 && p.x()<=272); assertTrue(p.z()>=256 && p.z()<=272);
        }
    }
    @Test void smallPlantsAreDoubleSidedCrossesAndNeverCullTheGroundUnderThem() {
        var stone = Blocks.STONE.defaultBlockState();
        var bush = Blocks.DEAD_BUSH.defaultBlockState();
        var column = new VoxelColumn(List.of(new VoxelColumn.Run(0, 1, stone), new VoxelColumn.Run(1, 2, bush)));
        var mesh = VoxelMesher.boxes(0, 0, 1, 1, 1, (x, z) -> column);
        var specks = mesh.stream().filter(q -> q.material() == bush).toList();
        assertEquals(4, specks.size());
        assertTrue(specks.stream().allMatch(q->!q.volume()));
        assertTrue(specks.stream().flatMap(q->q.points().stream()).allMatch(p -> p.y()>=1 && p.y()<=2 && p.x()>.14 && p.x()<.86));
        assertTrue(mesh.stream().anyMatch(q -> q.material() == stone && q.axis() == 3));
        assertEquals(stone, VoxelColumn.reduce(List.of(column), 2).state(0));
    }
    @Test void albedoCalibrationPreservesTextureColourAndMaterialVariation() {
        assertEquals(0x487530,GroundAlbedo.calibrate(0x7fb238,0x7fb238,0x487530));
        assertNotEquals(0x487530,GroundAlbedo.calibrate(0xaabb88,0x7fb238,0x487530));
    }
}
