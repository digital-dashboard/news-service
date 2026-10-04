package com.j11a.argus.integration;

import static com.j11a.argus.integration.AbstractIntegrationTest.LOCK_WAIT_SECONDS;

import com.j11a.argus.source.SourceLock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Holds a source lock in a transaction on another thread until closed, then runs the optional hook inside that
 * transaction and commits.
 */
final class HeldLock implements AutoCloseable {

    private final CountDownLatch release = new CountDownLatch(1);
    private final CompletableFuture<Void> holder;

    private HeldLock(TransactionTemplate tx, SourceLock lock, long sourceId, CountDownLatch acquired,
            Runnable beforeCommit) {
        holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            lock.acquire(sourceId);
            acquired.countDown();
            await(release);
            beforeCommit.run();
        }));
    }

    static HeldLock on(TransactionTemplate tx, SourceLock lock, long sourceId) throws InterruptedException {
        return on(tx, lock, sourceId, () -> { });
    }

    static HeldLock on(TransactionTemplate tx, SourceLock lock, long sourceId, Runnable beforeCommit)
            throws InterruptedException {
        CountDownLatch acquired = new CountDownLatch(1);
        HeldLock held = new HeldLock(tx, lock, sourceId, acquired, beforeCommit);
        if (!acquired.await(LOCK_WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the lock holder never acquired source " + sourceId);
        }
        return held;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() throws Exception {
        release.countDown();
        holder.get(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
    }
}
