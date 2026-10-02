package com.j11a.argus.feed;

import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.ingest.IngestReport;
import com.j11a.argus.web.ApiPaths;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/feeds")
public class FeedController {

    private final FeedService feeds;
    private final FeedIngestService ingest;

    public FeedController(FeedService feeds, FeedIngestService ingest) {
        this.feeds = feeds;
        this.ingest = ingest;
    }

    @PostMapping
    public ResponseEntity<FeedResponse> create(@Valid @RequestBody CreateFeedRequest request) {
        FeedResponse created = feeds.create(request);
        return ResponseEntity.created(URI.create(ApiPaths.BASE + "/feeds/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public FeedResponse get(@PathVariable long id) {
        return feeds.get(id);
    }

    @PostMapping("/{id}/refresh")
    public IngestReport refresh(@PathVariable long id) {
        return ingest.refresh(id);
    }
}
