package dev.starfold.worldgen.surface;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.universe.PlanetPackets;
import dev.starfold.worlds.fixtures.ExperienceFixtures;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceLodIntegrationTest {
    @BeforeAll static void boot() throws Exception { ExperienceFixtures.boot(); SurfaceTestTags.bind(); }
    @Test void profileTransportsResolvedTemplatesAndClientMatchesServerIncludingSeams() {
        for(String type:List.of("lush","home","mushroom","ice","basalt")) {
            var generator=SurfaceGenerationTest.generator(type);
            var profile=PlanetPackets.Profile.of(17,generator);
            var raw=Unpooled.buffer();
            PlanetPackets.Profile decoded;
            try {
                var buf=new RegistryFriendlyByteBuf(raw,RegistryAccess.EMPTY);
                PlanetPackets.Profile.CODEC.encode(buf,profile);
                assertTrue(raw.readableBytes()<1_000_000);
                decoded=PlanetPackets.Profile.CODEC.decode(buf);
                assertEquals(profile,decoded);
                assertFalse(buf.isReadable());
            } finally {raw.release();}
            assertTrue(decoded.surfaceCompatible());
            var server=new SurfaceFunction(generator.terrain());
            var client=new SurfaceFunction(decoded.terrain());
            assertEquals(SurfaceKey.of(server.terrain()),SurfaceKey.of(client.terrain()));
            int size=generator.terrain().shape().size();
            var random=new Random(42);
            for(int i=0;i<1200;i++) {
                var face=Face.values()[i%6];
                int u=i<100 ? i%2*(size-1) : random.nextInt(size),v=random.nextInt(size);
                assertEquals(server.visible(face,u,v),client.visible(face,u,v),type+" "+face+" "+u+","+v);
            }
        }
    }
    @Test void progressivePublicationIsCompleteAndMatchesBlockingBuild() throws Exception {
        var f=new SurfaceFunction(SurfaceGenerationTest.generator("asteroid").terrain());
        var cache=new SurfaceCache(SurfaceKey.of(f.terrain()));
        var published=new HashSet<Integer>();
        cache.fill(f,4,Face.NORTH,128,128,p->{synchronized(published){assertTrue(published.add(p));}});
        int size=f.terrain().shape().size(),width=(size+63)/64;
        assertEquals(6*width*width,published.size());
        var serial=SurfaceCache.build(f,1,Face.TOP,0,0);
        for(var face:Face.values()) for(int level=4;level<=64;level*=2)
            for(int v=0;v<size;v+=level) for(int u=0;u<size;u+=level)
                assertEquals(serial.cell(face,u,v,level),cache.cell(face,u,v,level));
    }
    @Test void lushWholePlanetEightWorkerReceipt() throws Exception {
        var f=new SurfaceFunction(SurfaceGenerationTest.generator("lush").terrain());
        f.point(Face.NORTH,512,512); // Include resolved objects, not the old bare prototype.
        for(int i=0;i<10_000;i++) f.visible(Face.NORTH,256+i%128,256+i/128);
        long start=System.nanoTime();
        for(int i=0;i<20_000;i++) f.visible(Face.NORTH,256+i%256,256+i/256);
        double visible=(System.nanoTime()-start)/20_000.;
        start=System.nanoTime();
        var cache=SurfaceCache.build(f,8,Face.NORTH,512,512);
        double seconds=(System.nanoTime()-start)/1e9;
        System.out.printf(Locale.ROOT,"SURFACE_LOD lush size=%d visible_ns=%.1f build_8_seconds=%.3f retained_bytes=%d%n",
                f.terrain().shape().size(),visible,seconds,cache.retainedBytes());
        assertEquals(1024,f.terrain().shape().size());
        assertTrue(cache.cell(Face.NORTH,512,512,64).materials().size()>0);
    }
}
