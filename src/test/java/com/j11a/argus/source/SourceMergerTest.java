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
import org.mockito.stubbing.OngoingStubbing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

class SourceMergerTest {

    private static final String STOP = "stop after the locks";
    private static final long BEYOND_INT = 5_000_000_000L;

    private final SourceLock lock = mock(SourceLock.class);
    private final JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    private final SourceMerger merger = new SourceMerger(jdbc, mock(JdbcTemplate.class), lock, Clock.systemUTC(),
            new MergeTelemetry(ObservationRegistry.NOOP, new SimpleMeterRegistry()));

    @SuppressWarnings("unchecked")
    private OngoingStubbing<Optional<SourceMerger.SourceRow>> sourceRows() {
        return when(jdbc.sql(anyString()).param(anyString(), any()).query(any(RowMapper.class)).optional());
    }

    @Test
    void mergeLocksTheLowerIdFirstWhicheverDirectionItRuns() {
        Optional<SourceMerger.SourceRow> row = Optional.of(new SourceMerger.SourceRow(1, "k", null));
        sourceRows().thenReturn(row, row).thenThrow(new IllegalStateException(STOP))
                .thenReturn(row, row).thenThrow(new IllegalStateException(STOP));

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
    void mergeRefusesASourceThatDoesNotExistBeforeLockingItSoAnIdBeyondTheLockKeyIsA404() {
        sourceRows().thenReturn(Optional.empty());

        assertThatThrownBy(() -> merger.merge(9, BEYOND_INT))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.SOURCE_NOT_FOUND);
                    assertThat(e.getMessage()).contains("Source 9 ");
                });
        verifyNoInteractions(lock);
    }

    @Test
    void moveFeedLocksTheLowerIdFirstWhicheverDirectionItRuns() {
        IllegalStateException stop = new IllegalStateException(STOP);
        Optional<SourceMerger.SourceRow> row = Optional.of(new SourceMerger.SourceRow(1, "k", null));
        sourceRows().thenReturn(row);
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
