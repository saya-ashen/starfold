package dev.starfold;

import org.junit.jupiter.api.*;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import com.mojang.serialization.JsonOps;
import static org.junit.jupiter.api.Assertions.*;

class NativePlanetIdentityTest {
    @BeforeAll static void boot(){NativePlanetEcologyTest.boot();}
    @Test void typedEntriesPersistIdsWithoutRequiringTheirRegistry() {
        var json=com.google.gson.JsonParser.parseString("{\"planet_type\":\"starfold:rocky\",\"terrain_seed\":42}");
        var entry=NativePlanetType.PLANET.parse(NativePlanetTypeTest.ops(),json).getOrThrow();
        var saved=NativePlanetType.PLANET.encodeStart(NativePlanetTypeTest.ops(),entry).getOrThrow();
        var bare=net.minecraft.resources.RegistryOps.create(JsonOps.INSTANCE,NativePlanetEcologyTest.lookup);
        var copy=NativePlanetType.PLANET.parse(bare,saved).getOrThrow();
        assertEquals(Optional.of(ResourceLocation.parse("starfold:rocky")),copy.get().typeId());
        assertEquals(saved,NativePlanetType.PLANET.encodeStart(bare,copy).getOrThrow());
    }
    @Test void optionalNamesAndIdsSurviveDefinitionBeforeAndAfterMaterialization() throws Exception {
        var definition=NativeAuthoredSystemTest.read(NativeAuthoredSystemTest.definition());
        var bare=net.minecraft.resources.RegistryOps.create(JsonOps.INSTANCE,NativePlanetEcologyTest.lookup);
        var saved=NativeSystemDefinition.CODEC.encodeStart(NativeAuthoredSystemTest.ops(),definition).getOrThrow();
        var copy=NativeSystemDefinition.CODEC.parse(bare,saved).getOrThrow();
        var a=definition.materialize(42);var b=copy.materialize(42);
        for(int i=0;i<a.planets().size();i++)assertEquals(NativePlanetIdentity.of(a.planets().get(i)),NativePlanetIdentity.of(b.planets().get(i)));
        assertEquals(Optional.of("Aster"),b.planets().getFirst().displayName());assertTrue(b.planets().get(3).displayName().isEmpty());
        var generator=b.planets().getFirst();assertEquals(NativePlanetIdentity.of(generator),NativePlanetIdentity.of(NativePlanetChunkGenerator.CODEC.codec().parse(bare,NativePlanetChunkGenerator.CODEC.codec().encodeStart(bare,generator).getOrThrow()).getOrThrow()));
    }
    @Test void savedUniverseAndLayoutKeepTypeOrigins() throws Exception {
        var ops=NativePlanetTypeTest.ops();var source=com.google.gson.JsonParser.parseString("{\"stars\":{\"min\":1,\"max\":1},\"planets\":{\"min\":2,\"max\":2},\"star_gap\":100000,\"types\":[{\"type\":\"starfold:rocky\"}]}");
        var layout=NativeUniverseLayout.CODEC.parse(ops,source).getOrThrow();var bare=net.minecraft.resources.RegistryOps.create(JsonOps.INSTANCE,NativePlanetEcologyTest.lookup);
        var copy=NativeUniverseLayout.CODEC.parse(bare,NativeUniverseLayout.CODEC.encodeStart(ops,layout).getOrThrow()).getOrThrow();
        var universe=NativeUniverse.generate(42,copy,NativePlanetSystem.model());
        var loaded=NativeUniverse.load(universe.save(new net.minecraft.nbt.CompoundTag(),NativePlanetEcologyTest.lookup),NativePlanetEcologyTest.lookup);
        for(var planet:loaded.planets())assertEquals(Optional.of(ResourceLocation.parse("starfold:rocky")),planet.typeId());
    }
    @Test void oldInlineDefaultPairReportsFamilyNotGuessedBiome() throws Exception {
        var system=NativeSystemChunkGenerator.CODEC.codec().parse(NativePlanetTypeTest.ops(),NativePlanetTypeTest.preset("native_universe")).getOrThrow();
        for(int i=0;i<2;i++){var id=NativePlanetIdentity.of(system.planet(i));assertTrue(id.type().isEmpty());assertEquals(i==0?"temperate":"rocky",id.label(k->k));}
    }
    @Test void twelveIdentitiesRoundTripIndependentlyOfNearEight() {
        var identities=new ArrayList<NativePlanetIdentity>();for(int i=0;i<12;i++)identities.add(new NativePlanetIdentity(Optional.of(ResourceLocation.parse("example:type_"+i)),"rocky",i==11?Optional.of("planet.example.last"):Optional.empty()));
        int[] sizes=new int[12],depths=new int[12];Arrays.fill(sizes,1024);Arrays.fill(depths,192);
        var packet=new NativeGroundPackets.Shapes(sizes,depths,List.of(),identities);
        var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY);
        try{NativeGroundPackets.Shapes.CODEC.encode(buffer,packet);var copy=NativeGroundPackets.Shapes.CODEC.decode(buffer);assertEquals(identities,copy.identities());assertEquals(12,NativePlanetSystem.identities().size());assertEquals(identities.getLast(),NativePlanetSystem.identity(11));assertEquals(0,buffer.readableBytes());}finally{buffer.release();NativePlanetShape.reset();}
        assertTrue(NativePlanetSystem.identities().isEmpty());
        assertThrows(IllegalArgumentException.class,()->new NativeGroundPackets.Shapes(sizes,depths,List.of(),identities.subList(0,8)));
    }
    @Test void formatterUsesNameThenLocalizedTypeThenPathAndKeepsRank() {
        var id=new NativePlanetIdentity(Optional.of(ResourceLocation.parse("starfold_pack:asteroid")),"rocky",Optional.empty());
        assertEquals("asteroid",id.label(k->k));assertEquals("小行星",id.label(k->k.equals("planet_type.starfold_pack.asteroid")?"小行星":k));
        assertEquals("H · 小行星 · 9/12",NativePlanetIdentity.caption(7,"小行星",9,12,240,String::length));
        assertEquals("My home",new NativePlanetIdentity(id.type(),"rocky",Optional.of("My home")).label(k->k));
        assertEquals("家",new NativePlanetIdentity(id.type(),"rocky",Optional.of("home.key")).label(k->k.equals("home.key")?"家":k));
    }
    @Test void everyInstalledTypeHasBothTranslations() throws Exception {
        var root=java.nio.file.Path.of("src/main/resources");for(String language:List.of("en_us","zh_cn")){var lang=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(root.resolve("assets/starfold/lang/"+language+".json"))).getAsJsonObject();try(var files=java.nio.file.Files.walk(root.resolve("data"))){for(var path:files.filter(p->p.toString().contains("/planet_type/")&&p.toString().endsWith(".json")).toList()){var rel=root.resolve("data").relativize(path);String key="planet_type."+rel.getName(0)+"."+path.getFileName().toString().replace(".json","");assertTrue(lang.has(key),key);}}}
    }
}
