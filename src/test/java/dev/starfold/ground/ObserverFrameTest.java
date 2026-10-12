package dev.starfold.ground;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.SourceText;
import dev.starfold.Sources;
import dev.starfold.chart.GroundChart;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.ChartOrientation;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetEcologyTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

/** Continuous client coordinates over the cube surface. Pure relabelling maths plus wiring checks;
 * the in-game behaviour is verified separately with the scripted dev client. */
class ObserverFrameTest {
    @BeforeAll
    static void boot() {
        PlanetEcologyTest.boot();
    }

    private static final int[][] EDGES = {
        {-1, 1024},
        {2048, 1024},
        {1024, -1},
        {1024, 2048},
        {-1, 7},
        {2048, 2040},
        {9, -1},
        {2039, 2048}
    };

    private static BlockPos storage(Face face, int u, int y, int v) {
        return new BlockPos(face.ordinal() * GroundCoordinates.STRIDE + u, y, v);
    }

    private static Point centre(BlockPos p) {
        return new Point(p.getX() + .5, p.getY(), p.getZ() + .5);
    }

    private static void same(Point a, Point b) {
        assertEquals(a.x(), b.x(), 1e-9);
        assertEquals(a.y(), b.y(), 1e-9);
        assertEquals(a.z(), b.z(), 1e-9);
    }

    /** The frame after walking from a root-chart halo cell onto its owner. */
    private static ObserverFrame cross(ObserverFrame frame, BlockPos halo) {
        var address = GroundTopology.address(halo);
        return frame.cross(
                GroundCoordinates.face(address.owner().getX()),
                centre(halo),
                centre(address.owner().atY(halo.getY())),
                address.turns());
    }

    @Test
    void everyFrameIsAChunkAlignedBijectionOnBlocksPointsAndHeadings() {
        for (int turns = 0; turns < 4; turns++) {
            var frame = new ObserverFrame(0, 3, turns, -4096, 20480);
            var seen = new HashSet<BlockPos>();
            for (int x = -17; x <= 17; x++)
                for (int z = -17; z <= 17; z++) {
                    var block = new BlockPos(5000 + x, 70, 900 + z);
                    var image = frame.toClient(block);
                    assertTrue(seen.add(image));
                    assertEquals(block, frame.toStorage(image));
                    // The block's centre lands in its image cell, and a chunk maps onto exactly one
                    // chunk.
                    var centre = frame.toClient(centre(block));
                    assertEquals(image, BlockPos.containing(centre.x(), centre.y(), centre.z()));
                    var origin =
                            frame.toClient(
                                    new BlockPos(block.getX() & ~15, 70, block.getZ() & ~15));
                    var far =
                            frame.toClient(new BlockPos(block.getX() | 15, 70, block.getZ() | 15));
                    assertEquals(image.getX() >> 4, origin.getX() >> 4);
                    assertEquals(image.getX() >> 4, far.getX() >> 4);
                    assertEquals(image.getZ() >> 4, origin.getZ() >> 4);
                    assertEquals(image.getZ() >> 4, far.getZ() >> 4);
                }
            var p = new Point(5000.25, 70.5, 900.75);
            same(p, frame.toStorage(frame.toClient(p)));
            for (var direction : Direction.Plane.HORIZONTAL) {
                var v =
                        frame.vectorToClient(
                                new Point(direction.getStepX(), 0, direction.getStepZ()));
                var image = Direction.fromYRot(frame.yawToClient(direction.toYRot()));
                assertEquals(image.getStepX(), v.x(), 1e-9);
                assertEquals(image.getStepZ(), v.z(), 1e-9);
                assertEquals(direction, frame.directionToStorage(image));
                // Block states turn with the same rotation as vectors and headings.
                assertEquals(
                        image,
                        frame.stateToClient(
                                        Blocks.OAK_STAIRS
                                                .defaultBlockState()
                                                .setValue(StairBlock.FACING, direction))
                                .getValue(StairBlock.FACING));
                assertEquals(
                        direction.toYRot(),
                        Math.floorMod(
                                (int) frame.yawToStorage(frame.yawToClient(direction.toYRot())),
                                360),
                        1e-6);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new ObserverFrame(0, 0, 0, 8, 0));
        assertThrows(IllegalArgumentException.class, () -> new ObserverFrame(0, 6, 0, 0, 0));
        assertTrue(ObserverFrame.identity(1, 2).isIdentity());
        same(new Point(3, 4, 5), ObserverFrame.identity(1, 2).toClient(new Point(3, 4, 5)));
    }

    @Test
    void crossingAnyOfTheTwentyFourSeamsMovesNothingOnTheClient() {
        for (var face : Face.values())
            for (int[] edge : EDGES)
                for (int turns = 0; turns < 4; turns++) {
                    var before = new ObserverFrame(0, face.ordinal(), turns, turns * 16 - 32, 48);
                    var halo = storage(face, edge[0], 140, edge[1]);
                    var address = GroundTopology.address(halo);
                    assertNotEquals(face.ordinal(), GroundCoordinates.face(address.owner().getX()));
                    var after = cross(before, halo);
                    assertEquals(GroundCoordinates.face(address.owner().getX()), after.root());
                    assertEquals(Math.floorMod(turns - address.turns(), 4), after.turns());
                    // The body: where it stood in the old chart's halo is where it stands in the
                    // new root chart.
                    same(
                            before.toClient(centre(halo)),
                            after.toClient(centre(address.owner().atY(140))));
                    // Everything around it: both sides of the seam keep their client cells, states
                    // and headings.
                    for (int a = -40; a <= 40; a += 8)
                        for (int b = -40; b <= 40; b += 8) {
                            var query =
                                    halo.offset(
                                            edge[0] < 0 || edge[0] >= 2048 ? a / 8 : a,
                                            0,
                                            edge[1] < 0 || edge[1] >= 2048 ? b / 8 : b);
                            var cell = GroundTopology.cell(query);
                            // Skip the corner wedge: there the two charts legitimately disagree
                            // (270 degrees around a cube vertex).
                            if ((cell.u() < 0 || cell.u() >= 2048)
                                    && (cell.v() < 0 || cell.v() >= 2048)) continue;
                            var owner = GroundTopology.address(query);
                            var seen = before.toClient(query);
                            int ownerFace = GroundCoordinates.face(owner.owner().getX());
                            // A third face near a vertex is drawn from whichever chart is the root;
                            // only the two faces of this seam must stay put.
                            if (ownerFace != face.ordinal() && ownerFace != after.root()) continue;
                            var inNewChart =
                                    owner.owner().getX() / GroundCoordinates.STRIDE == after.root()
                                            ? owner.owner().atY(140)
                                            : imageIn(after.root(), owner.owner().atY(140));
                            if (inNewChart == null) continue;
                            assertEquals(
                                    seen,
                                    after.toClient(inNewChart),
                                    face
                                            + " "
                                            + Arrays.toString(edge)
                                            + " turns "
                                            + turns
                                            + " at "
                                            + query);
                            var stairs =
                                    Blocks.OAK_STAIRS
                                            .defaultBlockState()
                                            .setValue(StairBlock.FACING, Direction.EAST);
                            var oldView =
                                    ChartOrientation.state(stairs, before.turns() - owner.turns());
                            var again = GroundTopology.address(inNewChart);
                            assertEquals(
                                    oldView,
                                    ChartOrientation.state(stairs, after.turns() - again.turns()));
                        }
                    assertEquals(
                            before.yawToClient(37f) % 360,
                            Math.floorMod(
                                    (int) after.yawToClient(37f + address.turns() * 90f), 360),
                            1e-4);
                }
    }

    /** A canonical owner block's position in another face's chart (its halo), by searching that chart's edge images. */
    private static BlockPos imageIn(int root, BlockPos owner) {
        var tile =
                new GroundChart(Face.values()[root])
                        .tile(Face.values()[GroundCoordinates.face(owner.getX())]);
        if (tile == null) return null;
        var p =
                GroundCoordinates.origin(root)
                        .add(
                                tile.point(
                                        GroundCoordinates.chart(
                                                new Point(
                                                        owner.getX() + .5,
                                                        owner.getY(),
                                                        owner.getZ() + .5))));
        var image = BlockPos.containing(p.x(), owner.getY(), p.z());
        var back = GroundTopology.address(image).owner();
        return back.getX() == owner.getX() && back.getZ() == owner.getZ() ? image : null;
    }

    @Test
    void walkingAroundACubeCornerAccumulatesItsQuarterTurnAndReturnsOnTheNextFace() {
        // TOP -> EAST -> SOUTH -> TOP -> EAST: four right-angle legs around a 270-degree vertex.
        var frame = ObserverFrame.identity(0, 0);
        Point client = new Point(1990.5, 150, 1990.5);
        int[][] legs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        var faces = new ArrayList<Integer>();
        for (int[] leg : legs)
            for (int step = 0; step < 116; step++) {
                client = client.add(new Point(leg[0], 0, leg[1]));
                var at = frame.toStorage(client);
                var block = BlockPos.containing(at.x(), at.y(), at.z());
                var cell = GroundTopology.cell(block);
                if (cell.u() < 0 || cell.u() >= 2048 || cell.v() < 0 || cell.v() >= 2048) {
                    frame = cross(frame, block);
                    faces.add(frame.root());
                }
                var now = frame.toStorage(client);
                assertEquals(frame.root(), GroundCoordinates.face(now.x()));
                assertTrue(GroundCoordinates.owned(now));
            }
        assertEquals(List.of(1, 4, 0, 1), faces);
        assertEquals(3, frame.turns());
        same(new Point(1990.5, 150, 1990.5), client);
    }

    @Test
    void serverAndClientAreWiredThroughTheFrame() throws Exception {
        SourceText seams = Sources.text("GroundSeams"),
                stream = Sources.text("ImageStream"),
                map = Sources.text("PacketMap"),
                client = Sources.text("GroundClient"),
                level = Sources.text("GroundTopologyLevelMixin"),
                frustum = Sources.text("GroundFlightFrustumMixin"),
                sender = Sources.text("GroundChunkSenderMixin");
        // A seam re-roots the frame and moves the body silently: no teleport packet, so the client
        // never reloads.
        assertTrue(seams.contains("ObserverFrames.cross(player,"));
        assertTrue(seams.contains("player.connection.resetPosition()"));
        assertFalse(seams.contains("player.teleportTo("));
        // The observer's chunks are images in client coordinates, never raw storage chunks.
        assertTrue(
                sender.contains(
                        "if(ImageStream.active(player)){pendingChunks.clear();ci.cancel();}"));
        assertTrue(stream.contains("frame.toStorage(new BlockPos(x,GroundCoordinates.FLOOR,z))"));
        assertTrue(stream.contains("ChunkTrackingView.isWithinDistance"));
        assertTrue(stream.contains("getChunkNow("));
        assertFalse(stream.contains("level.getChunk("));
        assertFalse(stream.contains("level.setBlock"));
        assertTrue(
                map.contains(
                        "packet instanceof ClientboundSectionBlocksUpdatePacket||packet instanceof ClientboundLightUpdatePacket||packet instanceof ClientboundLevelChunkWithLightPacket"));
        assertTrue(map.contains("ServerboundMovePlayerPacket.PosRot(s.x(),s.y(),s.z(),yaw,pitch"));
        // The client world is read where it is; only the corner mask is virtual, and halo sections
        // are drawn.
        assertTrue(
                level.contains(
                        "if(level.isClientSide)return GroundSeams.clientMasked(level,pos)?"));
        assertFalse(frustum.contains("ownedSection"));
        assertTrue(client.contains("frame().vectorToClient(new Point(1,0,0))"));
        assertTrue(client.contains("rotateY((float)(-f.turns()*Math.PI/2))"));
    }
}
