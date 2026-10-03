package com.j11a.argus.feed.poll;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.ingest.IngestReport;
import com.j11a.argus.ingest.PersistCounts;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AggregatePollReportTest {

    @Test
    void aggregatesCountsCorrectlyAcrossAllOutcomes() {
        IngestReport completed = IngestReport.completed(1L, 10,
                new PersistCounts(4, Map.of("content_changed", 1), 1, 2, Map.of("missing_key", 2), Map.of()));
        IngestReport notModified = IngestReport.notModified(2L);
        IngestReport failed = IngestReport.failed(3L, "timeout");

        AggregatePollReport report = AggregatePollReport.of(
                "poll-1",
                PollTrigger.SCHEDULED,
                Duration.ofMillis(1234),
                List.of(completed, notModified, failed));

        assertThat(report.pollId()).isEqualTo("poll-1");
        assertThat(report.trigger()).isEqualTo(PollTrigger.SCHEDULED);
        assertThat(report.durationMs()).isEqualTo(1234);
        assertThat(report.feedsPolled()).isEqualTo(3);
        assertThat(report.succeeded()).isEqualTo(1);
        assertThat(report.notModified()).isEqualTo(1);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.entriesSeen()).isEqualTo(10);
        assertThat(report.inserted()).isEqualTo(4);
        assertThat(report.updated()).isEqualTo(1);
        assertThat(report.linked()).isEqualTo(1);
        assertThat(report.unchanged()).isEqualTo(2);
        assertThat(report.skipped()).isEqualTo(2);
        assertThat(report.reports()).containsExactly(completed, notModified, failed);
    }
}
