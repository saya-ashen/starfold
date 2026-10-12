package dev.starfold.waypoint;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import dev.starfold.flight.FlightPermitTest;
import dev.starfold.worldgen.PlanetEcologyTest;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

class ShipGuideTest {
    private static final Path ROOT = Path.of("src/main/resources/data/starfold/advancement");

    private static Map<String, JsonObject> data() throws Exception {
        var all = new TreeMap<String, JsonObject>();
        try (var paths = Files.walk(ROOT)) {
            for (var path : paths.filter(p -> p.toString().endsWith(".json")).toList())
                all.put(
                        "starfold:" + ROOT.relativize(path).toString().replace(".json", ""),
                        JsonParser.parseString(Files.readString(path)).getAsJsonObject());
        }
        return all;
    }

    private static boolean condition(JsonObject c, Set<String> mods) {
        return switch (c.get("type").getAsString()) {
            case "neoforge:mod_loaded" -> mods.contains(c.get("modid").getAsString());
            case "neoforge:not" -> !condition(c.getAsJsonObject("value"), mods);
            case "neoforge:and" -> {
                boolean yes = true;
                for (var v : c.getAsJsonArray("values"))
                    yes &= condition(v.getAsJsonObject(), mods);
                yield yes;
            }
            default -> throw new AssertionError(c);
        };
    }

    private static boolean enabled(JsonObject a, Set<String> mods) {
        if (!a.has("neoforge:conditions")) return true;
        for (var c : a.getAsJsonArray("neoforge:conditions"))
            if (!condition(c.getAsJsonObject(), mods)) return false;
        return true;
    }

    @Test
    void parentsResolveForEveryOptionalModCombination() throws Exception {
        var all = data();
        for (var mods :
                List.of(
                        Set.<String>of(),
                        Set.of("create"),
                        Set.of("simulated"),
                        Set.of("create", "simulated"))) {
            var active = new HashSet<String>();
            all.forEach(
                    (id, a) -> {
                        if (enabled(a, mods)) active.add(id);
                    });
            for (var id : active) {
                var a = all.get(id);
                if (a.has("parent"))
                    assertTrue(active.contains(a.get("parent").getAsString()), id + " " + mods);
                var seen = new HashSet<String>();
                while (a.has("parent")) {
                    assertTrue(seen.add(a.get("parent").getAsString()));
                    a = all.get(a.get("parent").getAsString());
                }
            }
        }
    }

    @Test
    void translationsFitAndEveryRecipeExistsAndParses() throws Exception {
        PlanetEcologyTest.boot();
        var ops =
                net.minecraft.resources.RegistryOps.create(
                        com.mojang.serialization.JsonOps.INSTANCE, PlanetEcologyTest.lookup);
        for (var a : data().values()) {
            if (a.has("display"))
                for (var language : List.of("en_us", "zh_cn")) {
                    var lang = FlightPermitTest.json("assets/starfold/lang/" + language + ".json");
                    for (var part : List.of("title", "description")) {
                        String key =
                                a.getAsJsonObject("display")
                                        .getAsJsonObject(part)
                                        .get("translate")
                                        .getAsString();
                        assertTrue(lang.has(key), key);
                        String text = lang.get(key).getAsString();
                        assertTrue(
                                text.codePointCount(0, text.length())
                                        <= (part.equals("title")
                                                ? (language.equals("en_us") ? 24 : 12)
                                                : (language.equals("en_us") ? 120 : 60)),
                                key + ": " + text);
                    }
                }
            if (a.has("rewards"))
                for (var r : a.getAsJsonObject("rewards").getAsJsonArray("recipes")) {
                    String id = r.getAsString();
                    assertTrue(id.startsWith("starfold:"));
                    assertNotNull(
                            net.minecraft.world.item.crafting.Recipe.CODEC
                                    .parse(
                                            ops,
                                            FlightPermitTest.json(
                                                    "data/starfold/recipe/"
                                                            + id.split(":")[1]
                                                            + ".json"))
                                    .getOrThrow());
                }
        }
    }

    @Test
    void advancementCodecAndItemReferences() throws Exception {
        PlanetEcologyTest.boot();
        var ops =
                net.minecraft.resources.RegistryOps.create(
                        com.mojang.serialization.JsonOps.INSTANCE, PlanetEcologyTest.lookup);
        boolean mods = Files.isDirectory(Path.of("run/mods"));
        for (var a : data().values()) {
            // Unit runtime has no optional Create/Simulated. Where the optional mods are in
            // run/mods (a development
            // checkout; the directory is not versioned), check those exact ids against their
            // shipped item models,
            // then substitute only the absent registry entries to exercise the entire advancement
            // codec.
            String json = a.toString();
            var matcher =
                    java.util.regex.Pattern.compile("\"((?:create|simulated):[a-z_]+)\"")
                            .matcher(json);
            while (matcher.find()) {
                String id = matcher.group(1);
                if (mods) assertTrue(optionalItem(id), id);
                json = json.replace('"' + id + '"', "\"minecraft:iron_ingot\"");
            }
            assertNotNull(
                    net.minecraft.advancements.Advancement.CODEC
                            .parse(ops, JsonParser.parseString(json))
                            .getOrThrow());
        }
    }

    private static boolean optionalItem(String id) throws Exception {
        String path = "assets/" + id.split(":")[0] + "/models/item/" + id.split(":")[1] + ".json";
        try (var files = Files.list(Path.of("run/mods"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".jar")).toList())
                try (var zip = new java.util.zip.ZipFile(file.toFile())) {
                    if (zip.getEntry(path) != null) return true;
                    for (var entry : Collections.list(zip.entries()))
                        if (entry.getName().endsWith(".jar"))
                            try (var nested =
                                    new java.util.zip.ZipInputStream(zip.getInputStream(entry))) {
                                java.util.zip.ZipEntry e;
                                while ((e = nested.getNextEntry()) != null)
                                    if (e.getName().equals(path)) return true;
                            }
                }
        }
        return false;
    }

    @Test
    void guideTeachesBalancedBeginnerHullAndFirstTripNavigation() throws Exception {
        var en = FlightPermitTest.json("assets/starfold/lang/en_us.json");
        var zh = FlightPermitTest.json("assets/starfold/lang/zh_cn.json");
        String prefix = "advancement.starfold.ship.";
        for (var rule :
                Map.of(
                                "thruster",
                                List.of("pairs", "mass centre"),
                                "gyro",
                                List.of("at least two", "lever on"),
                                "helm",
                                List.of("overshoot", "floor layer", "six cobble layers"),
                                "airstair",
                                List.of("door and ramp approach clear"),
                                "scope",
                                List.of("navigation scope", "first planet trip"))
                        .entrySet())
            for (var phrase : rule.getValue())
                assertTrue(
                        en.get(prefix + rule.getKey() + ".description")
                                .getAsString()
                                .contains(phrase),
                        rule.getKey() + ": " + phrase);
        for (var rule :
                Map.of(
                                "thruster",
                                "推进器成对、对称地装在船体两侧",
                                "gyro",
                                "至少两个陀螺仪",
                                "helm",
                                "船太轻会冲过头：多铺一层地板",
                                "airstair",
                                "舱门和舷梯前不要放东西",
                                "scope",
                                "首次跨星旅行前就装导航观察镜")
                        .entrySet())
            assertTrue(
                    zh.get(prefix + rule.getKey() + ".description")
                            .getAsString()
                            .contains(rule.getValue()),
                    rule.getKey());
    }

    @Test
    void flightNeedsGroundedPilotedLiftAndQuietLanding() {
        assertFalse(ShipGuide.lifted(false, true, 3));
        assertFalse(ShipGuide.lifted(true, false, 3));
        assertFalse(ShipGuide.lifted(true, true, Double.NaN));
        assertFalse(ShipGuide.lifted(true, true, 2));
        assertTrue(ShipGuide.lifted(true, true, 2.01));
        int n = 0;
        for (int i = 0; i < 20; i++) n = ShipGuide.settled(n, .15, .1);
        assertEquals(20, n);
        assertEquals(0, ShipGuide.settled(n, 5, 0));
        assertEquals(0, ShipGuide.settled(n, .15, 1));
    }
}
