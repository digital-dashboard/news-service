package com.j11a.argus.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

class SourceMergerTest {

    private static final String STOP = "stop after the locks";

    private final SourceLock lock = mock(SourceLock.class);
    private final JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    private final SourceMerger merger = new SourceMerger(jdbc, mock(JdbcTemplate.class), lock, Clock.systemUTC(),
            new MergeTelemetry(ObservationRegistry.NOOP, new SimpleMeterRegistry()));

    private void failEverythingAfterTheLocks() {
        when(jdbc.sql(anyString())).thenThrow(new IllegalStateException(STOP));
    }

    @Test
    void mergeLocksTheLowerIdFirstWhicheverDirectionItRuns() {
        failEverythingAfterTheLocks();

        assertThatThrownBy(() -> merger.merge(9, 4)).hasMessage(STOP);
        assertThatThrownBy(() -> merger.merge(4, 9)).hasMessage(STOP);

        InOrder order = inOrder(lock);
        order.verify(lock).acquire(4);
        order.verify(lock).acquire(9);
        order.verify(lock).acquire(4);
        order.verify(lock).acquire(9);
        order.verifyNoMoreInteractions();
    }

    @Test
    void mergeIntoItselfIsRejectedBeforeAnyLockOrQuery() {
        assertThatThrownBy(() -> merger.merge(5, 5))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.SOURCE_MERGE_INVALID));

        verifyNoInteractions(lock, jdbc);
    }

    @Test
    void moveFeedLocksTheLowerIdFirstWhicheverDirectionItRuns() {
        IllegalStateException stop = new IllegalStateException(STOP);
        when(jdbc.sql(anyString()).param(anyString(), any()).query(Long.class).optional())
                .thenReturn(Optional.of(9L)).thenThrow(stop)
                .thenReturn(Optional.of(9L)).thenThrow(stop);

        assertThatThrownBy(() -> merger.moveFeed(1, 4)).hasMessage(STOP);
        assertThatThrownBy(() -> merger.moveFeed(1, 12)).hasMessage(STOP);

        InOrder order = inOrder(lock);
        order.verify(lock).acquire(4);
        order.verify(lock, times(2)).acquire(9);
        order.verify(lock).acquire(12);
        order.verifyNoMoreInteractions();
    }
}
