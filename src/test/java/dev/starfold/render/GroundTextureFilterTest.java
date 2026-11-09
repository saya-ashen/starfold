package dev.starfold.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GroundTextureFilterTest {
    @Test
    void reloadRescansReusedAtlasAndKeepsDiagnosticMode() {
        GroundTextureFilter.invalidateResources();
        int mode = GroundTextureFilter.mode;
        assertEquals(3, GroundTextureFilter.lastMip(42, 16, i -> i <= 3 ? 8 : 0));
        assertEquals(3, GroundTextureFilter.lastMip(42, 16, i -> { fail("cached atlas was rescanned"); return 0; }));
        GroundTextureFilter.invalidateResources();
        assertEquals(mode, GroundTextureFilter.mode);
        assertEquals(0, GroundTextureFilter.lastMip(42, 16, i -> 0));
        assertEquals(0, GroundTextureFilter.lastMip(42, 16, i -> { fail("zero mip is a valid cached result"); return 0; }));
        assertEquals(2, GroundTextureFilter.lastMip(43, 2, i -> 8));
        GroundTextureFilter.invalidateResources();
    }
}
