package com.j11a.argus.integration;

import com.j11a.argus.source.SourceLock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.support.TransactionTemplate;

/** Holds a source lock in a transaction on another thread until closed, then commits. */
final class HeldLock implements AutoCloseable {

    private static final long SECONDS = 20;

    private final CountDownLatch release = new CountDownLatch(1);
    private final CompletableFuture<Void> holder;

    private HeldLock(TransactionTemplate tx, SourceLock lock, long sourceId, CountDownLatch acquired) {
        holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            lock.acquire(sourceId);
            acquired.countDown();
            await(release);
        }));
    }

    static HeldLock on(TransactionTemplate tx, SourceLock lock, long sourceId) throws InterruptedException {
        CountDownLatch acquired = new CountDownLatch(1);
        HeldLock held = new HeldLock(tx, lock, sourceId, acquired);
        if (!acquired.await(SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the lock holder never acquired source " + sourceId);
        }
        return held;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() throws Exception {
        release.countDown();
        holder.get(SECONDS, TimeUnit.SECONDS);
    }
}
