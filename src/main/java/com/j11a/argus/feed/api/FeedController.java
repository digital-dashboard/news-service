package com.j11a.argus.feed.api;

import com.j11a.argus.feed.poll.AggregatePollReport;
import com.j11a.argus.feed.poll.FeedPoller;
import com.j11a.argus.feed.poll.PollTrigger;
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.ingest.IngestReport;
import com.j11a.argus.web.ApiPaths;
import com.j11a.argus.web.error.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/feeds")
public class FeedController {

    static final int MAX_PAGE_SIZE = 100;
    private static final String PAGE_TOO_DEEP = "page is too large for this size";

    private final FeedService feeds;
    private final FeedIngestService ingest;
    private final FeedPoller poller;

    public FeedController(FeedService feeds, FeedIngestService ingest, FeedPoller poller) {
        this.feeds = feeds;
        this.ingest = ingest;
        this.poller = poller;
    }

    @PostMapping
    public ResponseEntity<FeedResponse> create(@Valid @RequestBody CreateFeedRequest request) {
        FeedResponse created = feeds.create(request);
        return ResponseEntity.created(URI.create(ApiPaths.BASE + "/feeds/" + created.id())).body(created);
    }

    @GetMapping
    public PagedModel<FeedResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        if ((long) page * size > Integer.MAX_VALUE) {
            throw ApiException.validationFailed("page", PAGE_TOO_DEEP);
        }
        return new PagedModel<>(feeds.list(page, size));
    }

    @GetMapping("/{id}")
    public FeedResponse get(@PathVariable long id) {
        return feeds.get(id);
    }

    @PostMapping("/{id}/refresh")
    public IngestReport refresh(@PathVariable long id) {
        return ingest.refresh(id);
    }

    @PostMapping("/refresh")
    public AggregatePollReport refreshAll() {
        return poller.poll(PollTrigger.MANUAL);
    }

    @PatchMapping("/{id}")
    public FeedResponse patch(@PathVariable long id, @Valid @RequestBody PatchFeedRequest request) {
        return feeds.patch(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        feeds.delete(id);
    }
}
