package dev.starfold;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Incremental 8-block ring lattice, ordered by Euclidean distance, within 160 blocks.
 * Nearest means nearest sampled loaded candidate, not a continuous terrain optimum. */
final class NativeLandingSearch {
    static final int BUDGET=4;
    record Offset(int x,int z){double distance(){return Math.hypot(x,z);}}
    private static final List<Offset> OFFSETS=makeOffsets();
    private int cursor;private Offset hit;
    private static List<Offset> makeOffsets(){
        var points=new ArrayList<Offset>();
        for(int ring=8;ring<=160;ring+=8)for(int x=-ring;x<=ring;x+=8)for(int z=-ring;z<=ring;z+=8)
            if(Math.max(Math.abs(x),Math.abs(z))==ring&&Math.hypot(x,z)<=160)points.add(new Offset(x,z));
        points.sort(Comparator.comparingDouble(Offset::distance).thenComparingInt(Offset::x).thenComparingInt(Offset::z));
        return List.copyOf(points);
    }
    static List<Offset> offsets(){return OFFSETS;}
    Offset step(Predicate<Offset> landable){
        for(int work=0;work<BUDGET&&!complete();work++){var p=OFFSETS.get(cursor++);if(landable.test(p))hit=p;}
        return hit;
    }
    boolean complete(){return hit!=null||cursor==OFFSETS.size();}
}
