package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.api.CreateFeedRequest;
import com.j11a.argus.feed.poll.AggregatePollReport;
import com.j11a.argus.feed.poll.FeedPoller;
import com.j11a.argus.feed.poll.PollInterruptedException;
import com.j11a.argus.feed.poll.PollTrigger;
import com.j11a.argus.observability.MetricNames;
import com.j11a.argus.testsupport.ThreadDumps;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * A poll over real stub feeds is interrupted the way the scheduler's shutdown interrupts it. The extra property gives
 * this class a Spring context of its own, so the shared one is left alone.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "argus.poll.concurrency=2")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PollShutdownIT extends AbstractIntegrationTest {

    private static final Duration BOUND = Duration.ofSeconds(12);
    private static final String RSS = "application/rss+xml";
    private static final String NEW_PREFIX = "new-";

    @Autowired
    private FeedPoller poller;

    @Autowired
    private MeterRegistry registry;

    private static byte[] feedXml(int index, String... slugs) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>F")
                .append(index).append("</title><link>https://shutdown-").append(index)
                .append(".example.test</link><description>d</description>");
        for (String slug : slugs) {
            xml.append("<item><title>").append(slug).append("</title><link>https://shutdown-").append(index)
                    .append(".example.test/").append(slug).append("</link><guid>").append(index).append(slug)
                    .append("</guid></item>");
        }
        return xml.append("</channel></rss>").toString().getBytes(StandardCharsets.UTF_8);
    }

    private long createFeed(int index) {
        String path = "/shutdown/" + index + ".xml";
        stub.serve(path, 200, RSS, feedXml(index, "old-" + index));
        return feedService.create(new CreateFeedRequest(stub.baseUrl() + path, null, Topic.NEWS)).id();
    }

    private long interruptedPolls() {
        Timer timer = registry.find(MetricNames.POLL).tag(MetricNames.Tags.OUTCOME, "interrupted").timer();
        return timer == null ? 0 : timer.count();
    }

    @Test
    void interruptingAPollKeepsFinishedFeedsAndDoesNotChargeTheHeldOnes(CapturedOutput output) throws Exception {
        long[] ids = new long[5];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = createFeed(i + 1);
        }
        // Feeds 1 and 2 have a new item. Feeds 3 and 4 hang until released. Feed 5 waits for a free slot.
        stub.serve("/shutdown/1.xml", 200, RSS, feedXml(1, "old-1", NEW_PREFIX + 1));
        stub.serve("/shutdown/2.xml", 200, RSS, feedXml(2, "old-2", NEW_PREFIX + 2));
        CountDownLatch held = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        stub.serveWithLatch("/shutdown/3.xml", held, release, "bbc-like-rss2.xml");
        stub.serveWithLatch("/shutdown/4.xml", held, release, "bbc-like-rss2.xml");
        long interruptedBefore = interruptedPolls();

        AtomicReference<Thread> orchestrator = new AtomicReference<>();
        CompletableFuture<AggregatePollReport> poll = CompletableFuture.supplyAsync(() -> {
            orchestrator.set(Thread.currentThread());
            return poller.poll(PollTrigger.SCHEDULED);
        });
        try {
            assertThat(held.await(BOUND.toSeconds(), TimeUnit.SECONDS)).isTrue();

            orchestrator.get().interrupt();

            assertThatThrownBy(() -> poll.get(BOUND.toSeconds(), TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(PollInterruptedException.class);
        } finally {
            release.countDown();
        }

        assertThat(jdbcClient.sql("SELECT title FROM article WHERE title LIKE :p ORDER BY title")
                .param("p", NEW_PREFIX + "%").query(String.class).list())
                .containsExactly(NEW_PREFIX + 1, NEW_PREFIX + 2);
        assertThat(jdbcClient.sql("SELECT consecutive_failures FROM feed WHERE id IN (:ids)")
                .param("ids", List.of(ids[2], ids[3])).query(Integer.class).list()).containsOnly(0);
        assertThat(interruptedPolls()).isEqualTo(interruptedBefore + 1);
        assertThat(output.getOut()).doesNotContain("\"level\":\"ERROR\"");
        await().atMost(BOUND).untilAsserted(() ->
                assertThat(ThreadDumps.namesStartingWith("argus-poll-")).isEmpty());
    }
}
