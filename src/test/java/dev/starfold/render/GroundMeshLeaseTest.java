package dev.starfold.render;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GroundMeshLeaseTest {
    @Test void cancellationBeforeWritingReleasesOnceAndPreventsWriting() {
        var releases = new AtomicInteger();
        var lease = new GroundMeshLease(releases::incrementAndGet);
        lease.close();
        lease.close();
        assertFalse(lease.begin());
        assertEquals(1, releases.get());
    }

    @Test void cancellationDuringWritingDefersReleaseUntilWriterLeaves() throws Exception {
        var releases = new AtomicInteger();
        var lease = new GroundMeshLease(releases::incrementAndGet);
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var task = worker.submit(() -> {
                assertTrue(lease.begin());
                entered.countDown();
                try { assertTrue(resume.await(5, TimeUnit.SECONDS)); }
                finally { lease.end(); }
                return null;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            lease.close();
            lease.close();
            assertEquals(0, releases.get());
            resume.countDown();
            task.get(5, TimeUnit.SECONDS);
            lease.close();
            assertEquals(1, releases.get());
            assertFalse(lease.begin());
        } finally {
            resume.countDown();
            worker.shutdownNow();
        }
    }

    @Test void completedWriterRetainsBuffersForUploadUntilClose() {
        var releases = new AtomicInteger();
        var lease = new GroundMeshLease(releases::incrementAndGet);
        assertTrue(lease.begin());
        lease.end();
        assertEquals(0, releases.get());
        lease.close();
        lease.close();
        assertEquals(1, releases.get());
    }
}
