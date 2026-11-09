package dev.starfold.atlas;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MapLabelsTest {
    @Test void priorityLabelsReserveTheirRectangleAndPositionCannotOverwriteNames() {
        var labels = new MapLabels();
        assertTrue(labels.place(new MapLabels.Rect(20, 20, 40, 11))); // current body
        assertFalse(labels.place(new MapLabels.Rect(25, 25, 40, 11))); // selected body
        assertTrue(labels.place(new MapLabels.Rect(20, 32, 40, 11))); // planet
        assertFalse(labels.place(new MapLabels.Rect(30, 37, 40, 11))); // moon / position
        assertTrue(labels.place(new MapLabels.Rect(60, 20, 40, 11))); // touching is not overlap
    }
    @Test void packGasNameAndAtlasSystemCaptionsHaveBothTranslations() throws Exception {
        for (String system : new String[]{"experience", "experience_named_preview"}) {
            var json = Files.readString(Path.of("worlds/src/main/resources/data/starfold_worlds/starfold/system/"+system+".json"));
            assertTrue(json.contains("\"name\": \"planet.starfold_worlds.amber\""));
            assertFalse(json.contains("\"name\": \"Amber\""));
        }
        for (String lang : new String[]{"en_us", "zh_cn"}) {
            var pack = Files.readString(Path.of("worlds/src/main/resources/assets/starfold_worlds/lang/"+lang+".json"));
            assertTrue(pack.contains("\"planet.starfold_worlds.amber\": \""+(lang.equals("zh_cn") ? "琥珀" : "Amber")+"\""));
            var atlas = Files.readString(Path.of("atlas/src/main/resources/assets/starfold_atlas/lang/"+lang+".json"));
            assertTrue(atlas.contains("\"atlas.starfold_atlas.system_name\": \""+(lang.equals("zh_cn") ? "恒星系 %s" : "System %s")+"\""));
            assertTrue(atlas.contains("\"atlas.starfold_atlas.home_system\""));
        }
    }
}
