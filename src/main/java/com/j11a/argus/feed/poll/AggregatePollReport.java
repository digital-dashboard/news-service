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
        int updated,
        int linked,
        int unchanged,
        int skipped,
        List<IngestReport> reports) {

    public static AggregatePollReport of(
            String pollId, PollTrigger trigger, Duration duration, List<IngestReport> reports) {
        int entriesSeen = 0;
        int inserted = 0;
        int updated = 0;
        int linked = 0;
        int unchanged = 0;
        int skipped = 0;
        for (IngestReport r : reports) {
            entriesSeen += r.entriesSeen();
            inserted += r.inserted();
            updated += r.updated();
            linked += r.linked();
            unchanged += r.unchanged();
            skipped += r.skipped();
        }

        return new AggregatePollReport(
                pollId,
                trigger,
                duration.toMillis(),
                reports.size(),
                count(reports, IngestReport.Outcome.COMPLETED),
                count(reports, IngestReport.Outcome.NOT_MODIFIED),
                count(reports, IngestReport.Outcome.FAILED),
                entriesSeen,
                inserted,
                updated,
                linked,
                unchanged,
                skipped,
                List.copyOf(reports));
    }

    private static int count(List<IngestReport> reports, IngestReport.Outcome outcome) {
        return (int) reports.stream().filter(report -> report.outcome() == outcome).count();
    }
}
