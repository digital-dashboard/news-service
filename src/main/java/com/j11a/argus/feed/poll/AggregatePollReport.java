package com.j11a.argus.feed.poll;

import com.j11a.argus.ingest.IngestReport;
import java.time.Duration;
import java.util.List;

public record AggregatePollReport(
        String pollId,
        PollTrigger trigger,
        long durationMs,
        int feedsPolled,
        int succeeded,
        int notModified,
        int failed,
        int entriesSeen,
        int inserted,
        int unchanged,
        int skipped,
        List<IngestReport> reports) {

    public static AggregatePollReport of(
            String pollId, PollTrigger trigger, Duration duration, List<IngestReport> reports) {
        int succeeded = 0;
        int notModified = 0;
        int failed = 0;
        int entriesSeen = 0;
        int inserted = 0;
        int unchanged = 0;
        int skipped = 0;

        for (IngestReport r : reports) {
            switch (r.outcome()) {
                case COMPLETED -> succeeded++;
                case NOT_MODIFIED -> notModified++;
                case FAILED -> failed++;
            }
            entriesSeen += r.entriesSeen();
            inserted += r.inserted();
            unchanged += r.unchanged();
            skipped += r.skipped();
        }

        return new AggregatePollReport(
                pollId,
                trigger,
                duration.toMillis(),
                reports.size(),
                succeeded,
                notModified,
                failed,
                entriesSeen,
                inserted,
                unchanged,
                skipped,
                List.copyOf(reports));
    }
}
