package dev.starfold.chart;

import java.util.OptionalInt;

/** A selectable catalog body, never an implicitly interchangeable surface-storage slot. */
public record CelestialTarget(int bodyIndex) {
    public CelestialTarget {
        if (bodyIndex < 0) throw new IllegalArgumentException("Celestial target index");
    }

    public static CelestialTarget resolve(GroundEphemeris.Model model, int bodyIndex) {
        var target = new CelestialTarget(bodyIndex);
        target.body(model);
        return target;
    }

    public GroundEphemeris.Body body(GroundEphemeris.Model model) {
        var body = model.catalogBody(bodyIndex);
        return body;
    }

    public GroundEphemeris.Frame frame(GroundEphemeris.Model model, double tick) {
        body(model);
        return model.frame(bodyIndex, tick);
    }

    /** Only this explicit projection may feed landing, terrain, shape and surface identity APIs. */
    public OptionalInt surfaceSlot(GroundEphemeris.Model model) {
        var body = body(model);
        return body.appearance() == GroundEphemeris.Appearance.SURFACE
                ? OptionalInt.of(body.slot())
                : OptionalInt.empty();
    }

    public int requireSurfaceSlot(GroundEphemeris.Model model) {
        return surfaceSlot(model)
                .orElseThrow(() -> new IllegalArgumentException("Target has no landable surface"));
    }
}
