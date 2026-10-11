package dev.starfold;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeRelightQueueTest {
    @Test void nearestFirstAndStableTies(){
        var q=new NativeRelightQueue();q.mark(9,0,0);q.mark(3,0,0);q.mark(1,0,0);
        var seen=new ArrayList<Long>();q.drain(0,k->k,()->0,100,24,k->{seen.add(k);return true;});
        assertEquals(List.of(1L,3L,9L),seen);assertEquals(0,q.size());
    }
    @Test void neverStartsAnotherEntryAfterDeadlineButMakesOneAtomicUnitOfProgress(){
        var q=new NativeRelightQueue();for(int i=0;i<10;i++)q.mark(i,0,0);
        var clock=new AtomicLong();var seen=new ArrayList<Long>();
        assertEquals(2,q.drain(0,k->k,clock::get,6,24,k->{seen.add(k);clock.addAndGet(3);return true;}));
        assertEquals(List.of(0L,1L),seen);assertEquals(8,q.size());
        assertEquals(1,q.drain(1,k->k,clock::get,6,24,k->true));
    }
    @Test void countCapAndNotYetDue(){
        var q=new NativeRelightQueue();q.mark(0,4,0);q.mark(1,0,0);q.mark(2,0,0);
        assertEquals(1,q.drain(0,k->k,()->0,100,1,k->true));assertEquals(2,q.size());
        assertEquals(1,q.drain(1,k->k,()->0,100,24,k->true));assertEquals(1,q.size());
        assertEquals(0,q.drain(3,k->k,()->0,100,24,k->true));assertEquals(1,q.drain(4,k->k,()->0,100,24,k->true));
    }
    @Test void duplicateNotificationsPreserveAgeAndAdvanceButNeverDelayDueTime(){
        var q=new NativeRelightQueue();q.mark(10,4,0);q.mark(10,8,4);q.mark(10,2,1);
        assertEquals(1,q.size());assertEquals(0,q.drain(1,k->k,()->0,100,24,k->true));
        assertEquals(1,q.drain(2,k->k,()->0,100,24,k->true));assertEquals(0,q.size());
    }
    @Test void oldFarEntryCannotStarveUnderContinuousNearUpdates(){
        var q=new NativeRelightQueue();q.mark(999,0,0);var seen=new ArrayList<Long>();
        for(int tick=0;tick<=NativeRelightQueue.MAX_NEAR_TICKS;tick++){
            q.mark(1,tick,tick);q.mark(999,tick,tick);
            q.drain(tick,k->k,()->0,100,1,k->{seen.add(k);return true;});
        }
        assertEquals(999L,seen.getLast());
    }
    @Test void overdueFifoBoundsWaitEvenWhenServedEntriesAreRequeued(){
        var q=new NativeRelightQueue();for(int i=0;i<100;i++)q.mark(i,0,0);
        var served=new HashSet<Long>();for(int tick=0;tick<NativeRelightQueue.MAX_NEAR_TICKS+100;tick++){
            int now=tick;q.drain(tick,k->-k,()->0,100,1,k->{served.add(k);return true;});
            for(long k:served)q.mark(k,now,now);
        }
        assertEquals(100,served.size());
    }
    @Test void missingOldOwnerCannotMonopolizeOneAttemptBudget(){
        var q=new NativeRelightQueue();q.mark(0,0,0);q.mark(100,0,0);
        assertEquals(1,q.drain(100,k->k,()->0,100,1,k->false));
        var seen=new ArrayList<Long>();q.drain(101,k->k,()->0,100,1,k->{seen.add(k);return true;});
        assertEquals(List.of(100L),seen);assertEquals(1,q.size());
    }
    @Test void unloadedOwnerRetriesWithoutBlockingOthersAndForgottenImagesAreRemoved(){
        var q=new NativeRelightQueue();q.mark(0,0,0);q.mark(1,0,0);var seen=new ArrayList<Long>();
        assertEquals(2,q.drain(100,k->k,()->0,100,24,k->{seen.add(k);return k!=0;}));
        assertEquals(List.of(0L,1L),seen);assertEquals(1,q.size());
        q.retain(Set.of(5L));assertEquals(0,q.size());
    }
}
