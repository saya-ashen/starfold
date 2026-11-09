package dev.starfold.worldgen.plan;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.universe.PlanetPackets;
import dev.starfold.universe.PlanetPlanSync;
import dev.starfold.worldgen.surface.SurfaceFunction;
import java.nio.file.*;
import java.util.*;

/** Registry-only, all legal sizes. Explicit offline task, not repeated by check. */
public final class PlanetPlanEvaluation {
    private static volatile double sink;
    public static void run(String[] args) throws Exception {
        PlanetPlanFixtures.boot();
        try(var out=Files.newBufferedWriter(Path.of(args.length==0 ? "docs/planet-plan-measurements.csv" : args[0]))) {
            out.write("size,sites,planner_ms,selection_ms,assembly_ms,recording_ms,conflict_ms,indexing_ms,compression_ms,archive_bytes,plan_gzip_bytes,profile_bytes,profile_max_packet_bytes,plan_parts,baseline_point_ns,planned_point_ns,extra_point_ns,plan_sha256\n");
            int[] sizes=args.length<=1 ? java.util.stream.IntStream.iterate(416,n->n<=2048,n->n+16).toArray()
                    : Arrays.stream(args).skip(1).mapToInt(Integer::parseInt).toArray();
            for(int size:sizes) {
                var planet=PlanetPlanFixtures.planet(size,"plains");
                long start=System.nanoTime();
                var planner=new TraditionalPlanetPlanner(planet,PlanetPlanFixtures.context);
                var archive=planner.generate();
                var timings=planner.timings();
                double ms=(System.nanoTime()-start)/1e6;
                if(archive.plan().sites().isEmpty())throw new AssertionError("Empty benchmark plan "+size);
                var file=Path.of("build/planet-plan-fixture/measure-"+size+".dat");
                PlanetPlanStore.write(file,planet,archive);
                planet.terrain().initializePlan(archive::plan);
                var transfer=PlanetPlanSync.prepare(PlanetPackets.Profile.of(17,planet),PlanetPlanFixtures.context.registries());
                int packet=transfer.fullProfileBytes();
                if(transfer.maxPacketBytes()>1_000_000)throw new AssertionError("Profile packet limit");
                var assembler=new PlanetPlanSync.Assembler();var received=new ArrayList<PlanetPackets.Profile>();
                assembler.await(transfer.profile(),"benchmark",received::add);
                var reversed=new ArrayList<>(transfer.parts());Collections.reverse(reversed);for(var part:reversed)assembler.accept(part);
                if(received.size()!=1 || !received.getFirst().plan().orElseThrow().equals(archive.plan()))throw new AssertionError("Profile round trip");
                int compressed=archive.plan().compressed().length;
                var baseline=new SurfaceFunction(PlanetPlanFixtures.planet(size,"plains").terrain());
                var planned=new SurfaceFunction(planet.terrain());
                var site=archive.plan().sites().getFirst();
                var random=new Random(42);int[] u=new int[1024],v=new int[1024];Face[] face=new Face[1024];
                for(int i=0;i<u.length;i++) {
                    face[i]=i%2==0 ? site.face() : Face.VALUES[i%6];
                    u[i]=i%2==0 ? Math.clamp(site.u()+random.nextInt(33)-16,0,size-1) : random.nextInt(size);
                    v[i]=i%2==0 ? Math.clamp(site.v()+random.nextInt(33)-16,0,size-1) : random.nextInt(size);
                }
                for(int i=0;i<3;i++){measure(baseline,face,u,v);measure(planned,face,u,v);}
                double base=Double.MAX_VALUE,with=Double.MAX_VALUE;
                for(int i=0;i<3;i++){base=Math.min(base,measure(baseline,face,u,v));with=Math.min(with,measure(planned,face,u,v));}
                String row=String.format(Locale.ROOT,"%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%d,%.1f,%.1f,%.1f,%s%n",size,archive.plan().sites().size(),ms,
                        timings.selection()/1e6,timings.assembly()/1e6,timings.recording()/1e6,timings.conflict()/1e6,timings.indexing()/1e6,timings.compression()/1e6,Files.size(file),compressed,packet,transfer.maxPacketBytes(),transfer.parts().size(),base,with,with-base,archive.plan().identity());
                out.write(row);out.flush();System.out.print("PLAN_EVAL "+row);
            }
        } finally {PlanetPlanFixtures.close();}
    }
    private static double measure(SurfaceFunction function,Face[] face,int[] u,int[] v) {
        long start=System.nanoTime();double sum=0;
        for(int i=0;i<u.length;i++){var c=function.point(face[i],u[i],v[i]);sum+=c.height()+c.canopyTop();}
        sink=sum;return (System.nanoTime()-start)/(double)u.length;
    }
}
