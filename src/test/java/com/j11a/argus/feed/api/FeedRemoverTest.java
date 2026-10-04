package com.j11a.argus.feed.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.j11a.argus.feed.health.FeedHealthGauges;
import com.j11a.argus.source.SourceLock;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

class FeedRemoverTest {

    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final SourceLock sourceLock = mock(SourceLock.class);
    private final FeedHealthGauges healthGauges = mock(FeedHealthGauges.class);
    private final FeedRemover remover = new FeedRemover(jdbc, sourceLock, healthGauges,
            mock(PlatformTransactionManager.class));

    @SuppressWarnings("unchecked")
    private JdbcClient.StatementSpec statementUpdating(int rows, Optional<Long> first, Optional<Long>... later) {
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
        when(spec.update()).thenReturn(rows);
        JdbcClient.MappedQuerySpec<Long> query = mock(JdbcClient.MappedQuerySpec.class);
        when(spec.query(Long.class)).thenReturn(query);
        when(query.optional()).thenReturn(first, later);
        when(jdbc.sql(anyString())).thenReturn(spec);
        return spec;
    }

    @Test
    void locksTheSourceThenRemovesOwnedArticlesAndTheFeedAndRefreshesGaugesAfterCommit() {
        JdbcClient.StatementSpec statement = statementUpdating(1, Optional.of(4L), Optional.of(4L));

        remover.delete(9L);

        InOrder order = inOrder(sourceLock, jdbc);
        order.verify(sourceLock).acquire(4L);
        order.verify(jdbc).sql(contains("DELETE FROM article a USING article_feed mine"));
        order.verify(jdbc).sql("DELETE FROM feed WHERE id = :id");
        verify(statement).param("feedId", 9L);
        verify(healthGauges).refreshAfterCommit();
    }

    @Test
    void anUnknownFeedIsNotFoundWithoutLockingOrRefreshingGauges() {
        statementUpdating(0, Optional.empty());

        assertThatThrownBy(() -> remover.delete(404L))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FEED_NOT_FOUND));
        verifyNoInteractions(healthGauges, sourceLock);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFeedMovedBeforeTheLockWasTakenIsDeletedUnderTheLockOfItsNewSource() {
        statementUpdating(1, Optional.of(4L), Optional.of(5L), Optional.of(5L), Optional.of(5L));

        remover.delete(9L);

        InOrder order = inOrder(sourceLock, jdbc);
        order.verify(sourceLock).acquire(4L);
        order.verify(sourceLock).acquire(5L);
        order.verify(jdbc).sql("DELETE FROM feed WHERE id = :id");
        verify(healthGauges).refreshAfterCommit();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFeedThatMovesTwiceIsAConflictAndNothingIsDeleted() {
        statementUpdating(1, Optional.of(4L), Optional.of(5L), Optional.of(5L), Optional.of(6L));

        assertThatThrownBy(() -> remover.delete(9L))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
        verify(jdbc, never()).sql("DELETE FROM feed WHERE id = :id");
        verifyNoInteractions(healthGauges);
    }
}
