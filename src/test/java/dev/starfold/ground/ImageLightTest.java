package dev.starfold.ground;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;

import org.junit.jupiter.api.Test;

class ImageLightTest {
    @Test
    void ownerLightNotificationsDoNotRequireATickingChunkOrVanillaBroadcast() throws Exception {
        var source = Sources.text("GroundLightBroadcastMixin");
        assertTrue(source.contains("@Mixin(ServerChunkCache.class)"));
        assertTrue(source.contains("@Inject(method = \"onLightUpdate\""));
        assertTrue(
                source.contains(
                        "level.getServer().execute(() -> ImageStream.lit(level, section.chunk()))"));
        assertFalse(source.contains("broadcastChanges"));
        assertFalse(source.contains("getTickingChunk"));
        assertTrue(source.contains("PlanetLevels.is(level)"));
    }
}
