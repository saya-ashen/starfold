package dev.starfold.extras;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class StarterBooksTest {
    @Test void bothBooksGiveTheNextActionAndLandingCue() {
        assertTrue(StarterBooks.pages("zh_cn").getFirst().endsWith("走上舷梯，右键船头的控制器"));
        assertTrue(StarterBooks.pages("en_us").getFirst().endsWith("Walk up the airstair; right-click the bow controller."));
        assertTrue(StarterBooks.pages("zh_cn").get(4).contains("最近的其他星球"));
        assertTrue(StarterBooks.pages("en_us").get(4).contains("nearest other planet"));
        assertTrue(StarterBooks.pages("zh_cn").get(7).contains("下方"));
        assertTrue(StarterBooks.pages("en_us").get(7).contains("Below"));
    }

    @Test void bothBooksExplainLandingArrowsAndDockRelease() {
        assertTrue(StarterBooks.pages("zh_cn").get(7).contains("箭头"));
        assertTrue(StarterBooks.pages("en_us").get(7).contains("arrow"));
        for (var lang : new String[] {"en_us", "zh_cn"})
            assertTrue(String.join("\n", StarterBooks.pages(lang)).contains(
                    lang.equals("en_us") ? "Sneak-click: release only" : "潜行右键只放出"));
    }
}
