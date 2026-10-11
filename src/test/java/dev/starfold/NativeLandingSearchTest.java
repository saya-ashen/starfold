package dev.starfold;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class NativeLandingSearchTest {
    @Test void ringsAreBoundedUniqueAndNearestFirst(){
        var offsets=NativeLandingSearch.offsets();
        assertEquals(offsets.size(),new java.util.HashSet<>(offsets).size());
        double previous=0;
        for(var p:offsets){assertTrue(p.distance()>=previous);assertTrue(p.distance()<=160);assertEquals(0,p.x()%8);assertEquals(0,p.z()%8);previous=p.distance();}
        assertEquals(8,offsets.getFirst().distance());
    }
    @Test void workIsIncrementalAndStopsAtNearestHit(){
        var search=new NativeLandingSearch();var count=new AtomicInteger();
        assertNull(search.step(p->{count.incrementAndGet();return false;}));
        assertEquals(NativeLandingSearch.BUDGET,count.get());assertFalse(search.complete());
        var expected=NativeLandingSearch.offsets().get(NativeLandingSearch.BUDGET);
        assertEquals(expected,search.step(p->{count.incrementAndGet();return true;}));
        assertTrue(search.complete());assertEquals(NativeLandingSearch.BUDGET+1,count.get());
        assertEquals(expected,search.step(p->{fail("finished search must not evaluate");return false;}));
    }
    @Test void exhaustiveNoHitTerminatesAndNeverExceedsBudget(){
        var search=new NativeLandingSearch();int total=0;
        while(!search.complete()){var count=new AtomicInteger();assertNull(search.step(p->{count.incrementAndGet();return false;}));assertTrue(count.get()<=NativeLandingSearch.BUDGET);total+=count.get();}
        assertEquals(NativeLandingSearch.offsets().size(),total);
    }
    @Test void wiringKeepsLoadedOnlyForecastAndBilingualCue() throws Exception {
        var root=java.nio.file.Path.of("src/main/java/dev/starfold");
        var landing=java.nio.file.Files.readString(root.resolve("NativeLanding.java"));
        assertTrue(landing.contains("var site=candidate(level,body,0,0)"));
        assertTrue(landing.contains("getChunkNow(x>>4,z>>4)"));
        assertTrue(landing.contains("NativeAirstair.canDeployAt(level,body"));
        assertTrue(landing.contains("NativeThruster.scopeTier(body)<3||!eligible(level,body)"));
        assertTrue(landing.contains("if(!finished&&scan.complete())refreshed=tick"));
        for(String lang:new String[]{"en_us","zh_cn"}){var json=NativeFlightPermitTest.json("assets/starfold/lang/"+lang+".json");for(String key:new String[]{"landing_site","landing_none","landing_search"})assertTrue(json.has("hud.starfold.instrument_"+key));}
        assertTrue(NativeStarterBooks.pages("zh_cn").get(7).contains("箭头"));assertTrue(NativeStarterBooks.pages("en_us").get(7).contains("arrow"));
    }
    @Test void aidPacketRoundTrips(){
        var buf=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY);
        try{var site=new NativeLanding.Site(NativeLanding.Kind.SLOPE,false,new NativeLanding.Aid(NativeLanding.AidStatus.FOUND,12,-35));
            var packet=new NativeHelmPackets.State(java.util.UUID.randomUUID(),net.minecraft.core.BlockPos.ZERO,net.minecraft.core.Direction.NORTH,true,true,0,false,0,0,0,site);
            NativeHelmPackets.State.CODEC.encode(buf,packet);assertEquals(packet,NativeHelmPackets.State.CODEC.decode(buf));assertEquals(0,buf.readableBytes());
        }finally{buf.release();}
    }
}
