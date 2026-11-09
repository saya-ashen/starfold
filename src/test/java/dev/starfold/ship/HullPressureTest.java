package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import dev.starfold.worldgen.PlanetBiomeFixtures;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

class HullPressureTest {
    @BeforeAll
    static void boot() throws Exception {
        PlanetBiomeFixtures.boot();
    }

    private static HullPressureDefinition parse(String json) {
        return HullPressureDefinition.parse(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void parsesDirectAndTaggedPressureAndRejectsMalformedInput() {
        var direct = parse("{\"blocks\":\"minecraft:iron_block\",\"pressure\":0.85}");
        assertEquals(ResourceLocation.parse("minecraft:iron_block"), direct.blocks());
        assertFalse(direct.tag());
        assertEquals(.85, direct.pressure());
        assertTrue(parse("{\"blocks\":\"#c:storage_blocks/iron\",\"pressure\":1.1}").tag());
        for (var json : List.of("{}", "{\"blocks\":1,\"pressure\":1}",
                "{\"blocks\":\"#\",\"pressure\":1}", "{\"blocks\":\"BAD:ID\",\"pressure\":1}",
                "{\"blocks\":\"minecraft:stone\"}", "{\"blocks\":\"minecraft:stone\",\"pressure\":\"1\"}",
                "{\"blocks\":\"minecraft:stone\",\"pressure\":null}",
                "{\"blocks\":\"minecraft:stone\",\"pressure\":0}",
                "{\"blocks\":\"minecraft:stone\",\"pressure\":-1}",
                "{\"blocks\":\"minecraft:stone\",\"pressure\":1e999}"))
            assertThrows(JsonParseException.class, () -> parse(json), json);
    }

    @Test
    void resolvesTagsAndBlocksCachesStatesAndReloadReplacesTheCache() {
        var tag = TagKey.create(Registries.BLOCK, ResourceLocation.parse("test:pressure_hulls"));
        var holder = Blocks.IRON_BLOCK.builtInRegistryHolder();
        var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        var previous = new java.util.HashMap<net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block>,
                List<net.minecraft.core.Holder<net.minecraft.world.level.block.Block>>>();
        registry.getTags().forEach(pair -> previous.put(pair.getFirst(), pair.getSecond().stream().toList()));
        var direct = parse("{\"blocks\":\"minecraft:iron_block\",\"pressure\":0.75}");
        var tagged = parse("{\"blocks\":\"#test:pressure_hulls\",\"pressure\":0.85}");
        try {
            var tags = new java.util.HashMap<>(previous);
            tags.put(tag, List.of(holder));
            registry.bindTags(tags);
            for (var rules : List.of(List.of(direct, tagged), List.of(tagged, direct))) {
                var table = new HullPressure.Table(rules);
                assertEquals(.85, table.get(Blocks.IRON_BLOCK.defaultBlockState()));
                assertEquals(.5, table.get(Blocks.STONE.defaultBlockState()));
            }
            var listener = new HullPressure();
            listener.apply(List.of(tagged), null, null);
            assertFalse(HullPressure.damaged(Blocks.IRON_BLOCK.defaultBlockState(), Math.nextDown(.85)));
            assertTrue(HullPressure.damaged(Blocks.IRON_BLOCK.defaultBlockState(), .85));
            assertFalse(HullPressure.damaged(Blocks.STONE.defaultBlockState(), .49));
            assertTrue(HullPressure.damaged(Blocks.STONE.defaultBlockState(), .5));
            registry.bindTags(previous);
            assertFalse(HullPressure.damaged(Blocks.IRON_BLOCK.defaultBlockState(), .8), "already cached state");
            listener.apply(List.of(tagged), null, null);
            assertTrue(HullPressure.damaged(Blocks.IRON_BLOCK.defaultBlockState(), .8), "reload resolves current tags");
            listener.apply(List.of(parse("{\"blocks\":\"minecraft:iron_block\",\"pressure\":0.25}")), null, null);
            assertEquals(0, HullPressure.damageInterval(.24));
            assertEquals(100, HullPressure.damageInterval(.25));
            assertTrue(HullPressure.damaged(Blocks.IRON_BLOCK.defaultBlockState(), .25));
        } finally {
            registry.bindTags(previous);
            new HullPressure().apply(List.of(), null, null);
        }
        assertEquals(0, HullPressure.damageInterval(.49));
        assertEquals(100, HullPressure.damageInterval(.5));
        assertEquals(44, HullPressure.damageInterval(.85));
    }
}
