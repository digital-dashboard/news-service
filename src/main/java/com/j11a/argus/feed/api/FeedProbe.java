package com.j11a.argus.feed.api;

import com.j11a.argus.feed.fetch.FetchError;
import com.j11a.argus.ingest.FeedLoader;
import com.j11a.argus.ingest.IngestTelemetry;
import com.j11a.argus.observability.LogKeys;
import com.j11a.argus.url.HttpUrls;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import java.net.URI;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;

/** The single-attempt download that checks a feed URL before a create or a URL change. Holds no connection. */
@Slf4j(topic = FeedService.AUDIT_LOGGER)
@Component
class FeedProbe {

    private static final String INVALID_DETAIL = "The URL did not return a readable feed.";

    private final FeedLoader loader;

    FeedProbe(FeedLoader loader) {
        this.loader = loader;
    }

    IngestTelemetry.CreateFetchTimer startTimer() {
        return loader.startCreateFetch();
    }

    FeedLoader.CreateLoaded.Created download(String action, URI uri, IngestTelemetry.CreateFetchTimer timer) {
        return switch (loader.loadForCreate(uri, timer)) {
            case FeedLoader.CreateLoaded.Created created -> created;
            case FeedLoader.CreateLoaded.Failed failed -> throw rejected(action, uri, failed);
        };
    }

    private ApiException rejected(String action, URI uri, FeedLoader.CreateLoaded.Failed failed) {
        String url = HttpUrls.redact(uri.toString());
        FetchError error = failed.error();
        String detail = failed.reason() + (error != null ? " " + error.type() : "");
        LoggingEventBuilder event = log.atWarn()
                .setMessage("Feed " + action + " rejected for " + url + ": " + detail)
                .addKeyValue(LogKeys.URL, url)
                .addKeyValue(LogKeys.REASON, failed.reason());
        FetchError.addFields(event, error, failed.contentType(), failed.bodyBytes()).log();
        return new ApiException(ErrorCode.FEED_INVALID, INVALID_DETAIL, Map.of("reason", failed.reason()));
    }
}
