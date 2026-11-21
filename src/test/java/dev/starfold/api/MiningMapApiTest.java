package dev.starfold.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class MiningMapApiTest {
    private static MiningMapApi.Survey site(List<MiningMapApi.Ore> ores, int examined) {
        return new MiningMapApi.Survey(
                "starfold:native_planet",
                0,
                0,
                0,
                16,
                16,
                16,
                130,
                16,
                1,
                512,
                examined,
                "Survey_1",
                ores);
    }

    @Test
    void deepHomeOreSnapshotsAreBoundedAndImmutable() {
        var ore = new MiningMapApi.Ore("minecraft:redstone_ore", 3, 310, 512);
        var rows = new ArrayList<>(List.of(ore));
        var survey = site(rows, 12800);
        rows.clear();
        assertEquals(List.of(ore), survey.ores());
        assertThrows(UnsupportedOperationException.class, () -> survey.ores().clear());
        assertThrows(
                IllegalArgumentException.class,
                () -> new MiningMapApi.Ore("minecraft:iron_ore", 1, 1, 513));
        assertThrows(IllegalArgumentException.class, () -> site(List.of(ore), 12801));
    }

    @Test
    void inconsistentOrDuplicateDiscoveriesAreRejected() {
        var ore = new MiningMapApi.Ore("minecraft:iron_ore", 5, 1, 8);
        assertThrows(IllegalArgumentException.class, () -> site(List.of(ore), 4));
        assertThrows(IllegalArgumentException.class, () -> site(List.of(ore, ore), 100));
        assertThrows(IllegalArgumentException.class, () -> new MiningMapApi.Ore("bad", 1, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MiningMapApi.Ore("minecraft:iron_ore", 1, 8, 1));
    }

    @Test
    void clientSnapshotStoreIsBoundedAndCanBeCleared() {
        var survey = site(List.of(), 12800);
        try {
            var source = new ArrayList<>(List.of(survey));
            MiningMapApi.acceptClientSurveys(source);
            source.clear();
            assertEquals(List.of(survey), MiningMapApi.surveys());
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            MiningMapApi.acceptClientSurveys(
                                    java.util.Collections.nCopies(33, survey)));
        } finally {
            MiningMapApi.clearClientSurveys();
        }
        assertTrue(MiningMapApi.surveys().isEmpty());
    }
}
