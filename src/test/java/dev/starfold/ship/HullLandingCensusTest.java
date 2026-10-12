package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.planet.ExperiencePackTest;

import net.minecraft.core.Direction;
import net.minecraft.nbt.*;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

/** Reproducible generated-surface census, not a claim about player-edited loaded terrain. */
class HullLandingCensusTest {
    @Test
    void sampleExperienceCraterFields() throws Exception {
        ExperiencePackTest.boot();
        var system = ExperiencePackTest.generator("experience");
        system.materialize(20261009L);
        var tag =
                NbtIo.readCompressed(
                        Path.of("src/main/resources/data/starfold/structure/starter_ship.nbt"),
                        NbtAccounter.unlimitedHeap());
        var size = tag.getList("size", 3);
        int sx = size.getInt(0), sz = size.getInt(2);
        var palette = tag.getList("palette", 10);
        var blocks = tag.getList("blocks", 10);
        record Sill(double x, double y, double z, Direction out) {}
        var sills = new ArrayList<Sill>();
        for (int i = 0; i < blocks.size(); i++) {
            var b = blocks.getCompound(i);
            var state = palette.getCompound(b.getInt("state"));
            if (!state.getString("Name").equals("starfold:airstair")) continue;
            var p = b.getList("pos", 3);
            var d = Direction.byName(state.getCompound("Properties").getString("facing"));
            sills.add(
                    new Sill(
                            p.getInt(0) + .5 - sx / 2.0,
                            p.getInt(1) + 1,
                            p.getInt(2) + .5 - sz / 2.0,
                            d));
        }
        var out =
                new StringBuilder(
                        "planet,seed,face,start,extent,stride,heading,landable,total,fraction\n");
        for (int planet : new int[] {1, 2}) {
            var terrain = system.planet(planet).terrain();
            int start = (terrain.shape().size() - 512) / 2;
            int lo = start - 40, span = 592;
            int[][] heights = new int[span][span];
            for (int x = 0; x < span; x++)
                for (int z = 0; z < span; z++)
                    heights[x][z] =
                            terrain.surfaceY(CubeSurfaceTopology.Face.TOP, lo + x, lo + z) + 1;
            HullLanding.SurfaceColumn column =
                    (x, z) -> new HullLanding.Surface(heights[x - lo][z - lo], false);
            for (int heading = 0; heading < 4; heading++) {
                final int turn = heading;
                int yes = 0, total = 0;
                for (int x = start; x < start + 512; x += 8)
                    for (int z = start; z < start + 512; z += 8) {
                        total++;
                        double cx = x + .5, cz = z + .5;
                        double w = heading % 2 == 0 ? sx : sz, d = heading % 2 == 0 ? sz : sx;
                        var survey =
                                HullLanding.survey(
                                        cx - w / 2, cz - d / 2, cx + w / 2, cz + d / 2, column);
                        if (survey.kind() != HullLanding.Kind.LAND) continue;
                        boolean works = false;
                        for (var sill : sills) {
                            var lateral = sill.out.getClockWise();
                            boolean before =
                                    sills.stream()
                                            .anyMatch(
                                                    s ->
                                                            s.out == sill.out
                                                                    && s.x
                                                                            == sill.x
                                                                                    - lateral
                                                                                            .getStepX()
                                                                    && s.z
                                                                            == sill.z
                                                                                    - lateral
                                                                                            .getStepZ());
                            if (before) continue;
                            int width =
                                    sills.stream()
                                                    .anyMatch(
                                                            s ->
                                                                    s.out == sill.out
                                                                            && s.x
                                                                                    == sill.x
                                                                                            + lateral
                                                                                                    .getStepX()
                                                                            && s.z
                                                                                    == sill.z
                                                                                            + lateral
                                                                                                    .getStepZ())
                                            ? 2
                                            : 1;
                            var v =
                                    rotate(
                                            sill.x
                                                    + .5 * sill.out.getStepX()
                                                    + (width == 2 ? .5 : 0) * lateral.getStepX(),
                                            sill.z
                                                    + .5 * sill.out.getStepZ()
                                                    + (width == 2 ? .5 : 0) * lateral.getStepZ(),
                                            turn);
                            var facing = rotate(sill.out.getStepX(), sill.out.getStepZ(), turn);
                            var at = new Vector3d(cx + v.x, survey.highest() + sill.y, cz + v.z);
                            var flight =
                                    AirstairGeometry.flight(
                                            at,
                                            facing,
                                            new Vector3d(0, -1, 0),
                                            width,
                                            (a, b) ->
                                                    column.surface(
                                                                    (int) Math.floor(a),
                                                                    (int) Math.floor(b))
                                                            .height());
                            if (flight == null) continue;
                            boolean clear = true;
                            for (var tread : flight.boxes()) {
                                for (int a = (int) Math.floor(tread.minX);
                                        a <= Math.floor(tread.maxX);
                                        a++)
                                    for (int b = (int) Math.floor(tread.minZ);
                                            b <= Math.floor(tread.maxZ);
                                            b++)
                                        if (a + 1 > tread.minX
                                                && a < tread.maxX
                                                && b + 1 > tread.minZ
                                                && b < tread.maxZ
                                                && column.surface(a, b).height() > tread.maxY + .01)
                                            clear = false;
                            }
                            if (clear) {
                                works = true;
                                break;
                            }
                        }
                        if (works) yes++;
                    }
                out.append(
                        String.format(
                                Locale.ROOT,
                                "%d,%d,TOP,%d,512,8,%d,%d,%d,%.8f%n",
                                planet,
                                terrain.seed(),
                                start,
                                heading * 90,
                                yes,
                                total,
                                yes / (double) total));
                assertTrue(total == 4096);
            }
        }
        Files.createDirectories(Path.of("build/space"));
        Files.writeString(Path.of("build/space/landaid1-census.csv"), out);
        System.out.println(out);
    }

    private static Vector3d rotate(double x, double z, int turn) {
        return switch (turn) {
            case 0 -> new Vector3d(x, 0, z);
            case 1 -> new Vector3d(-z, 0, x);
            case 2 -> new Vector3d(-x, 0, -z);
            default -> new Vector3d(z, 0, -x);
        };
    }
}
