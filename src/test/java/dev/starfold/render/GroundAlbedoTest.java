package dev.starfold.render;

import dev.starfold.geometry.CubicColor;
import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class GroundAlbedoTest {
    @Test
    void sharedUnroundedAverageMatchesOriginalForEveryChannelTint() {
        var random = new Random(4817);
        for (int texture = 0; texture < 24; texture++) {
            int[] pixels = new int[37];
            for (int i = 0; i < pixels.length; i++) pixels[i] = random.nextInt();
            var channels = GroundAlbedo.averageChannels(pixels);
            for (int tint = 0; tint < 256; tint++) {
                int rgb = tint << 16 | (255 - tint) << 8 | (tint * 17 & 255);
                double[] sum = new double[3];
                int weight = 0;
                for (int pixel : pixels) {
                    if ((pixel >>> 24) < 26) continue;
                    weight++;
                    for (int j = 0; j < 3; j++)
                        sum[j] += CubicColor.linear((pixel >> (8 * j) & 255) / 255.0);
                }
                int expected = 0;
                for (int j = 0; j < 3; j++)
                    expected = expected << 8 | (int) Math.round(CubicColor.encoded(sum[j] / weight)
                            * (rgb >> (16 - 8 * j) & 255));
                assertEquals(expected, GroundAlbedo.tint(channels, rgb));
            }
        }
        var empty = GroundAlbedo.averageChannels(new int[] {0, 0x19010203});
        assertEquals(0xff00ff, GroundAlbedo.tint(empty, 0));
        assertEquals(0xff00ff, GroundAlbedo.tint(empty, 0xffffff));
    }
}
