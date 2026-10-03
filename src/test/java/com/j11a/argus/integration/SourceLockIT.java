package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.j11a.argus.source.SourceLock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class SourceLockIT extends AbstractIntegrationTest {

    @Autowired
    private SourceLock sourceLock;

    @Autowired
    private PlatformTransactionManager txManager;

    @Test
    void acquireOutsideTransactionThrowsIllegalTransactionStateException() {
        assertThatThrownBy(() -> sourceLock.acquire(1L))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void uncontendedAcquireReturnsZero() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Duration wait = tx.execute(status -> sourceLock.acquire(42L));
        assertThat(wait).isEqualTo(Duration.ZERO);
    }

    @Test
    void contendedAcquireWaitsUntilHolderReleases() throws Exception {
        TransactionTemplate tx1 = new TransactionTemplate(txManager);
        TransactionTemplate tx2 = new TransactionTemplate(txManager);

        CountDownLatch holderAcquired = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        long holdMillis = 200;

        Thread holder = new Thread(() -> {
            tx1.execute(status -> {
                sourceLock.acquire(99L);
                holderAcquired.countDown();
                try {
                    releaseHolder.await();
                    Thread.sleep(holdMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
        });

        holder.start();
        holderAcquired.await();

        AtomicReference<Duration> waitRef = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            Duration wait = tx2.execute(status -> sourceLock.acquire(99L));
            waitRef.set(wait);
        });

        waiter.start();
        Thread.sleep(50);
        releaseHolder.countDown();

        holder.join();
        waiter.join();

        Duration wait = waitRef.get();
        assertThat(wait).isNotNull();
        assertThat(wait.toMillis()).isGreaterThanOrEqualTo(holdMillis - 50);
    }
}
