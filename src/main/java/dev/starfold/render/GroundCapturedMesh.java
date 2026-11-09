package dev.starfold.render;

import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.lod.*;

import java.util.List;
import java.util.function.Consumer;

/** Same bounded complete-tile mesh for chart, orbital and remote views. */
final class GroundCapturedMesh implements AutoCloseable {
    private final VoxelMesher.Job mesh;
    private int emitted;

    GroundCapturedMesh(
            VoxelColumns image,
            Face face,
            int x,
            int z,
            int step,
            int remaining,
            PlanetShape shape,
            java.util.function.LongFunction<GroundSurface> surfaces) {
        this(image, face, x, z, step, remaining, shape, surfaces, null);
    }

    /** {@code generated} answers halo columns of neighbours that were never captured. */
    GroundCapturedMesh(
            VoxelColumns image,
            Face face,
            int x,
            int z,
            int step,
            int remaining,
            PlanetShape shape,
            java.util.function.LongFunction<GroundSurface> surfaces,
            VoxelSource generated) {
        this(
                new CapturedVoxelSource(
                        image,
                        face,
                        x,
                        z,
                        step,
                        (u, v) -> {
                            var c =
                                    shape.topology()
                                            .canonical(
                                                    new dev.starfold.geometry.CubeSurfaceTopology
                                                            .Cell(face, u, 0, v))
                                            .cell();
                            var neighbour =
                                    surfaces.apply(
                                            GroundOwnership.key(
                                                    shape, c.face().ordinal(), c.u(), c.v()));
                            if (neighbour == null && generated != null)
                                return generated.column(c.face(), c.u(), c.v());
                            if (neighbour == null || neighbour.voxels() == null) return null;
                            return new VoxelSource.Column() {
                                public net.minecraft.world.level.block.state.BlockState state(
                                        int y) {
                                    return neighbour.voxels().state(c.u() & 15, y, c.v() & 15);
                                }

                                public int ground() {
                                    return neighbour.voxels().floor() - 1;
                                }
                            };
                        }),
                face,
                x,
                z,
                step,
                remaining);
    }

    /** Whole 16×16 tile with real neighbour halo: equal neighbours cull their shared faces. */
    GroundCapturedMesh(VoxelSource source, Face face, int x, int z, int step, int remaining) {
        mesh =
                new VoxelMesher.Job(
                        source,
                        face,
                        x,
                        z,
                        step,
                        Math.max(0, Math.min(VoxelLodPolicy.RESIDENT_TILE_QUADS, remaining)),
                        true,
                        true);
    }

    void step(long deadline, Consumer<VoxelMesher.Quad> output) {
        mesh.step(
                deadline,
                VoxelLodPolicy.COLUMNS_PER_STEP,
                VoxelLodPolicy.CELLS_PER_STEP,
                () -> false);
        if (!mesh.done() || mesh.overflow()) return;
        int budget = VoxelLodPolicy.QUADS_PER_STEP;
        while (emitted < mesh.quadCount() && budget-- > 0 && System.nanoTime() < deadline) {
            var quad = mesh.quad(emitted++);
            output.accept(
                    new VoxelMesher.Quad(
                            quad.material(),
                            quad.axis(),
                            quad.points().stream()
                                    .map(p -> new Point(p.x(), p.y() - 128, p.z()))
                                    .toList(),
                            true));
        }
    }

    static List<Point> project(PlanetShape shape, Face face, List<Point> points) {
        return points.stream()
                .map(p -> GroundExteriorMesh.position(shape, face, p.x(), p.z(), p.y()))
                .toList();
    }

    boolean done() {
        return mesh.done() && (mesh.overflow() || emitted == mesh.quadCount());
    }

    boolean fallback() {
        return mesh.overflow();
    }

    int quadCount() {
        return mesh.quadCount();
    }

    public void close() {
        mesh.close();
    }
}
