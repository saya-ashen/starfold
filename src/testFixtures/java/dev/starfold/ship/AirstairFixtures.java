package dev.starfold.ship;

import java.util.List;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;

/** Access to package-private stair geometry for content-module hull-clearance tests. */
public final class AirstairFixtures {
    public static List<AABB> boxes(Vector3d hinge, Vector3d out, double ground) {
        var flight = AirstairGeometry.flight(hinge, out, new Vector3d(0, -1, 0), 2, (x, z) -> ground);
        org.junit.jupiter.api.Assertions.assertNotNull(flight);
        return flight.boxes();
    }
    private AirstairFixtures() {}
}
