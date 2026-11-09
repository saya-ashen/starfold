package dev.starfold.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.GroundOrbit;
import dev.starfold.chart.GroundSolar;
import dev.starfold.chart.GroundSpace;
import dev.starfold.chart.PlanetShape;
import dev.starfold.flight.GroundFlightClient;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.geometry.PlanetSpace;
import dev.starfold.network.StarfoldNetwork;
import dev.starfold.planet.Anomaly;
import dev.starfold.DevelopmentHooks;
import dev.starfold.sky.Space;
import dev.starfold.space.RenderFrame;
import dev.starfold.space.SpaceClient;
import dev.starfold.space.SpaceView;
import dev.starfold.worldgen.PlanetHeightCache;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.PlanetTint;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.*;

/** Owns one remote planet's building, resident and fallback GPU cohorts. */
final class RemotePlanetMeshes {
    // Bounds follow a buffer through staging, resident and fallback slots.
    static final Map<VertexBuffer, float[]> spheres = new WeakHashMap<>();
        PlanetRemoteView view = new PlanetRemoteView();
        GroundSpace.Config mask, fallbackMask;
        PlanetRemoteView.Snapshot building, resident, fallback;
        Job job;
        int cursor, requestedStep = 64, buildingStep, residentStep;
        long heard;
        PlanetRemoteView.Snapshot inputSource;
        PlanetHeightCache inputHeights;
        GroundCoverSnapshot inputCover;
        dev.starfold.worldgen.surface.SurfaceFunction trees;
        final GroundMipCache pyramids = new GroundMipCache();
        final BitSet pyramidDirty = new BitSet();
        final GroundCoverSnapshot[] inputs = new GroundCoverSnapshot[384];
        final GroundSpace.Config[] inputMasks = new GroundSpace.Config[384];
        final VertexBuffer[] meshes = new VertexBuffer[384],
                staging = new VertexBuffer[384],
                fallbackMeshes = new VertexBuffer[384];

        void session(long session) {
            if (view.session() != 0 && view.session() != session) {
                if (job != null) job.close();
                job = null;
                for (int i = 0; i < 384; i++) {
                    if (staging[i] != null && staging[i] != meshes[i]) staging[i].close();
                    staging[i] = null;
                    if (resident != null) {
                        if (fallbackMeshes[i] != null) fallbackMeshes[i].close();
                        fallbackMeshes[i] = meshes[i];
                        meshes[i] = null;
                    }
                }
                if (resident != null) {
                    fallback = resident;
                    fallbackMask = mask;
                }
                building = resident = null;
                buildingStep = residentStep = 0;
                Arrays.fill(inputs, null);
                inputSource = null;
                inputHeights = null;
                inputCover = null;
                cursor = 0;
                mask = null;
                view = new PlanetRemoteView();
            }
        }

        void close() {
            pyramids.clear();
            pyramidDirty.clear();
            if (job != null) job.close();
            job = null;
            for (int i = 0; i < 384; i++) {
                if (meshes[i] != null) meshes[i].close();
                if (staging[i] != null && staging[i] != meshes[i]) staging[i].close();
                if (fallbackMeshes[i] != null) fallbackMeshes[i].close();
                meshes[i] = staging[i] = fallbackMeshes[i] = null;
            }
            building = resident = fallback = null;
            buildingStep = residentStep = 0;
            Arrays.fill(inputs, null);
            inputSource = null;
            inputHeights = null;
            inputCover = null;
            fallbackMask = null;
            cursor = 0;
        }

        void work(long deadline) {
            if (mask == null) return;
            if (view.terrain() == null || view.terrain().readySurface() == null) return;
            pyramidDirty.or(pyramids.poll());
            if (building == null) pyramidDirty.stream().forEach(p -> inputs[p] = null);
            if (building == null
                    && (!pyramidDirty.isEmpty()
                            || resident == null
                            || residentStep != requestedStep
                            || resident.generation() != view.generation())) {
                pyramidDirty.clear();
                building = view.snapshot();
                buildingStep = requestedStep;
                if (building != null) prepareInputs();
                cursor = 0;
            }
            if (building == null) return;
            while (cursor < 384 && System.nanoTime() < deadline) {
                if (!GroundViewPolicy.exists(building.terrain().shape(), cursor)) {
                    cursor++;
                    continue;
                }
                if (job == null) {
                    var next =
                            inputCover.farPatch(
                                    GroundViewPolicy.face(cursor),
                                    GroundViewPolicy.x(cursor),
                                    GroundViewPolicy.z(cursor));
                    if (meshes[cursor] != null
                            && residentStep == buildingStep
                            && mask.equals(inputMasks[cursor])
                            && next.sameInputs(inputs[cursor])) {
                        staging[cursor] = meshes[cursor];
                        cursor++;
                        continue;
                    }
                    inputs[cursor] = next;
                    inputMasks[cursor] = mask;
                    job = new Job(building, cursor, buildingStep, mask, inputCover, pyramids);
                }
                job.step(deadline);
                if (job.tile < 256) break;
                staging[cursor] = job.finish();
                job.close();
                job = null;
                cursor++;
            }
            if (cursor == 384) {
                for (int i = 0; i < 384; i++) {
                    if (meshes[i] != null && meshes[i] != staging[i]) meshes[i].close();
                    meshes[i] = staging[i];
                    staging[i] = null;
                }
                resident = building;
                residentStep = buildingStep;
                building = null;
            }
        }

        private void prepareInputs() {
            var terrain = building.terrain();
            var model=GroundClient.ephemeris();
            if(model!=null && building.planet()<model.count()) {
                Point eye;
                double time;
                if(SpaceClient.frame()!=null) {
                    eye=SpaceClient.universeEye(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
                    time=SpaceClient.time();
                } else {
                    var pose=GroundClient.shape().canonical(Face.VALUES[GroundClient.face()],GroundClient.eye()).pose();
                    eye=GroundClient.home().world(GroundClient.shape().position(pose)); time=GroundClient.time();
                }
                pyramids.focus(model.at(building.planet(),time).local(eye));
            }
            if (inputSource == null
                    || trees != terrain.readySurface()
                    || inputSource.terrain().seed() != terrain.seed()
                    || inputSource.terrain().version() != terrain.version()
                    || !inputSource.terrain().generation().equals(terrain.generation())
                    || !inputSource.biome().equals(building.biome())) {
                inputHeights = new PlanetHeightCache(terrain);
                trees = terrain.readySurface();
            }
            inputSource = building;
            inputCover =
                    GroundCoverSnapshot.far(inputHeights, building.surfaces(), building.changed());
        }

    private static final class Job implements AutoCloseable {
        final GroundExteriorVertices out = new GroundExteriorVertices();
        final PlanetRemoteView.Snapshot snapshot;
        final GroundSpace.Config mask;
        final int patch, face, x0, z0;
        int tile, fed;
        final java.util.concurrent.Future<List<dev.starfold.worldgen.lod.VoxelMesher.Quad>> future;
        List<dev.starfold.worldgen.lod.VoxelMesher.Quad> quads;
        final PlanetShape shape;
        final net.minecraft.world.level.BlockAndTintGetter tint;

        Job(
                PlanetRemoteView.Snapshot snapshot,
                int patch,
                int step,
                GroundSpace.Config mask,
                GroundCoverSnapshot cover,
                GroundMipCache pyramids) {
            this.mask = Objects.requireNonNull(mask);
            this.snapshot = snapshot;
            this.patch = patch;
            var level = Minecraft.getInstance().level;
            tint =
                    PlanetTint.view(
                            level,
                            level.registryAccess(),
                            snapshot.planet(),
                            snapshot.terrain(),
                            snapshot.biome());
            shape = snapshot.terrain().shape();
            face = GroundViewPolicy.face(patch);
            x0 = GroundViewPolicy.x(patch);
            z0 = GroundViewPolicy.z(patch);
            future =
                    GroundMeshScheduler.shared.farMesh(
                            shape, patch, step, pyramids.field(shape, snapshot.terrain(), cover, patch));
        }

        void step(long deadline) {
            if (!future.isDone()) return;
            if (quads == null) quads = GroundMeshScheduler.completed(future);
            while (fed < quads.size() && System.nanoTime() < deadline) {
                var q = quads.get(fed++);
                var p = GroundCapturedMesh.project(shape, Face.VALUES[face], q.points());
                var pos = GroundAlbedo.position(face, q.points());
                var normal = q.points().get(1).sub(q.points().getFirst())
                        .cross(q.points().get(2).sub(q.points().getFirst()));
                int rgb = GroundAlbedo.color(
                        q.material(),
                        PlanetTint.position(snapshot.planet(), face,
                                pos.getX() - face * 4096, pos.getY(), pos.getZ()),
                        tint,
                        net.minecraft.core.Direction.getNearest(
                                (float) normal.x(), (float) normal.y(), (float) normal.z()));
                if (q.rgb() >= 0) rgb = GroundAlbedo.calibrate(q.rgb(),
                        dev.starfold.worldgen.surface.SurfaceColor.color(q.material()), rgb);
                int surfaceClass=dev.starfold.api.SurfaceClass.of(q.material(),net.minecraft.world.level.EmptyBlockGetter.INSTANCE,net.minecraft.core.BlockPos.ZERO);
                out.quad(p, rgb, GroundExteriorVertices.surfaceTag(surfaceClass,false), false);
            }
            if (fed == quads.size()) tile = 256;
        }

        void glow(Point[] t) {
            out.glow(t, Anomaly.GLOW_TINT);
        }

        VertexBuffer finish() {
            if (mask.core() > 0)
                for (var t :
                        GroundExteriorMesh.anomalyBeams(
                                shape, Face.VALUES[face], x0, z0, mask.height())) glow(t);
            var gpu = new VertexBuffer(VertexBuffer.Usage.STATIC);
            gpu.bind();
            try {
                var sphere = out.sphere();
                gpu.upload(out.build());
                if (sphere != null) spheres.put(gpu, sphere);
                return gpu;
            } catch (RuntimeException e) {
                gpu.close();
                throw e;
            } finally {
                VertexBuffer.unbind();
            }
        }

        public void close() {
            future.cancel(false);
            out.close();
        }
    }

}
