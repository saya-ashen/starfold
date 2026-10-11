package dev.starfold;

import java.util.*;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;
import java.util.function.LongToDoubleFunction;

/** Deduplicated image-light work, nearest first until age gives FIFO precedence. No world access. */
final class NativeRelightQueue {
    static final int MAX_NEAR_TICKS=40;
    private record Entry(long image,long due,long first,long order){}
    private final Map<Long,Entry> entries=new HashMap<>();
    private long sequence;
    void mark(long image,long due,long now){
        entries.compute(image,(key,old)->old==null?new Entry(image,due,now,sequence++):new Entry(image,Math.min(due,old.due),old.first,old.order));
    }
    void remove(long image){entries.remove(image);}
    void retain(Set<Long> images){entries.keySet().retainAll(images);}
    int size(){return entries.size();}
    long oldestAge(long now){long age=0;for(var e:entries.values())age=Math.max(age,now-e.first);return age;}
    /** A chunk is atomic: the first eligible attempt guarantees progress; thereafter never start past deadline.
     * Failed (temporarily unloaded) owners stay queued, are attempted once per drain, and rotate to
     * the FIFO tail so a missing owner cannot monopolize the guaranteed attempt.
     * After 40 ticks, FIFO supersedes distance. With N older eligible entries and >=1 success/tick,
     * a continuously loaded entry waits at most 40+N ticks, regardless of new arrivals or re-dirtying. */
    int drain(long now,LongToDoubleFunction distance,LongSupplier clock,long deadline,int limit,LongPredicate work){
        var ready=new ArrayList<Entry>();for(var e:entries.values())if(e.due<=now)ready.add(e);
        ready.sort((a,b)->{
            boolean oldA=now-a.first>=MAX_NEAR_TICKS,oldB=now-b.first>=MAX_NEAR_TICKS;
            if(oldA!=oldB)return oldA?-1:1;
            int cmp=oldA?Long.compare(a.order,b.order):Double.compare(distance.applyAsDouble(a.image),distance.applyAsDouble(b.image));
            return cmp!=0?cmp:Long.compare(a.order,b.order);
        });
        int attempts=0;
        for(var e:ready){
            if(attempts>=limit||attempts>0&&clock.getAsLong()>=deadline)break;
            attempts++;if(work.test(e.image))entries.remove(e.image,e);
            else entries.replace(e.image,e,new Entry(e.image,e.due,e.first,sequence++));
        }
        return attempts;
    }
}
