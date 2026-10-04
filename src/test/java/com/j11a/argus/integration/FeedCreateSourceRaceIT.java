package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.api.CreateFeedRequest;
import com.j11a.argus.feed.api.FeedResponse;
import com.j11a.argus.source.SourceLock;
import com.j11a.argus.source.SourceMerger;
import com.j11a.argus.testsupport.MergeData;
import com.j11a.argus.testsupport.RssBody;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A create that waits for a source lock while a merge deletes that source must not fail on the foreign key. */
class FeedCreateSourceRaceIT extends AbstractIntegrationTest {

    private static final String SITE = "https://race.example.test/";

    @Autowired
    private SourceMerger merger;

    @Autowired
    private SourceLock sourceLock;

    @Autowired
    private PlatformTransactionManager txManager;

    private MergeData data;
    private long raced;
    private long mergedInto;

    @BeforeEach
    void seed() {
        data = new MergeData(jdbcClient);
        raced = data.source("race.example.test", SITE);
        mergedInto = data.source("other.example.test", null);
    }

    private String serve(String path) {
        stub.serve(path, 200, RssBody.CONTENT_TYPE, RssBody.rss(SITE, null, path.replace("/", "-")));
        return stub.baseUrl() + path;
    }

    /** Starts the create, lets it queue for the raced source's lock, and merges that source away under it. */
    private CompletableFuture<FeedResponse> createWhileTheSourceIsMergedAway(CreateFeedRequest request)
            throws Exception {
        CompletableFuture<FeedResponse> creating;
        try (HeldLock merging = HeldLock.on(new TransactionTemplate(txManager), sourceLock, raced,
                () -> merger.merge(raced, mergedInto))) {
            creating = CompletableFuture.supplyAsync(() -> feedService.create(request));
            awaitWaitingForSourceLock(raced);
        }
        return creating;
    }

    @Test
    void anExplicitSourceMergedAwayWhileTheCreateWaitsIs404NotAForeignKeyFailure() throws Exception {
        String url = serve("/race/explicit.xml");

        CompletableFuture<FeedResponse> creating = createWhileTheSourceIsMergedAway(
                new CreateFeedRequest(url, null, Topic.NEWS, raced));

        assertThatThrownBy(() -> creating.get(LOCK_WAIT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.SOURCE_NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("Source " + raced + " does not exist.");
                });
        assertThat(count("SELECT count(*) FROM feed WHERE url = '" + url + "'")).isZero();
    }

    @Test
    void anAutomaticallyResolvedSourceMergedAwayWhileTheCreateWaitsAttachesTheFeedToALiveSource() throws Exception {
        data.feed(raced, serve("/race/first.xml"));
        String url = serve("/race/automatic.xml");

        CompletableFuture<FeedResponse> creating = createWhileTheSourceIsMergedAway(
                new CreateFeedRequest(url, null, Topic.NEWS, null));

        FeedResponse created = creating.get(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(count("SELECT count(*) FROM source WHERE id = " + raced)).isZero();
        assertThat(created.source().id()).isNotEqualTo(raced);
        assertThat(count("SELECT count(*) FROM feed f JOIN source s ON s.id = f.source_id WHERE f.id = "
                + created.id())).isOne();
    }
}
