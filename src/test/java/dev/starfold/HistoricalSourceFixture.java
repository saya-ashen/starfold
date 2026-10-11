package dev.starfold;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Explicit structural regression baselines; never regenerated from current sources. */
final class HistoricalSourceFixture {
    private final String resource;
    private final JsonObject sources;
    private final JsonObject port;

    HistoricalSourceFixture(String version) throws IOException {
        resource = "/historical-source/" + version + ".json";
        try (var in = HistoricalSourceFixture.class.getResourceAsStream(resource)) {
            assertNotNull(in, "Missing historical fixture: " + resource);
            sources = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        try(var in=HistoricalSourceFixture.class.getResourceAsStream("/historical-source/neoforge-port-overlay.json")){
            assertNotNull(in);port=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("versions").getAsJsonObject(version);
        }
    }

    byte[] bytes(String path) {
        var source = sources.get(path);
        assertNotNull(source, resource + ": missing frozen entry " + path);
        String text=source.getAsString();
        if(port!=null&&port.has(path))for(var edit:port.getAsJsonArray(path)){
            var pair=edit.getAsJsonObject();String old=pair.get("old").getAsString();int start=text.indexOf(old);
            org.junit.jupiter.api.Assertions.assertTrue(start>=0&&text.indexOf(old,start+1)<0,resource+": unique pinned port hunk "+path);
            text=text.substring(0,start)+pair.get("new").getAsString()+text.substring(start+old.length());
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
