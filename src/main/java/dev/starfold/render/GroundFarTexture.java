package dev.starfold.render;

/** Far chart vertex contract: UV0 = sprite minimum; signed-short UV2 = unsigned-16 sprite maximum.
 * UV0.y adds twice the surface class (decoded before upstream readers); alpha keeps the patch/side tag.
 * Normal RGB remains tinted albedo. Vanilla/detail vertices keep
 * ordinary UV0 and lightmap UV2. GLSL restores full-bright UV2 before ALL upstream lighting readers.
 */
final class GroundFarTexture {
    static boolean patch(int tag) {
        return tag >= 232 && tag <= 243 || tag == 249 || tag == 250;
    }

    static int upper(float coordinate) {
        return (int) Math.floor(coordinate * 65535.0);
    }

    /** GLSL mirror, including signed-short decoding, negative-world fract and half-texel inset. */
    static double wrap(double world, double minimum, int packedMaximum, int atlasSize) {
        double maximum = (packedMaximum & 65535) / 65535.0;
        double inset = .5 / atlasSize;
        double uv = minimum + (world - Math.floor(world)) * (maximum - minimum);
        return Math.clamp(uv, minimum + inset, Math.max(minimum + inset, maximum - inset));
    }

    static int cutout(double alpha, int texturedTinted, int albedo) {
        return alpha < .1 ? albedo : texturedTinted;
    }

    static int sky(int waterDepth) { return Math.clamp(15-Math.max(0,waterDepth),0,15); }

    private GroundFarTexture() {}
}
