package com.j11a.argus.feed.api;

import com.j11a.argus.feed.Feed;
import com.j11a.argus.feed.FeedRepository;
import com.j11a.argus.feed.poll.PollProperties;
import com.j11a.argus.security.AdminAccess;
import com.j11a.argus.web.error.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedService {

    /** The feed API's audit lines share one logger, whichever of its collaborators writes them. */
    static final String AUDIT_LOGGER = "com.j11a.argus.feed.api.FeedService";
    /** feed.name is varchar(255). */
    static final int MAX_NAME_LENGTH = 255;
    /** feed.language is varchar(16). */
    static final int MAX_LANGUAGE_LENGTH = 16;

    private final FeedRepository feeds;
    private final FeedCreator creator;
    private final FirstIngest firstIngest;
    private final FeedUpdater updater;
    private final FeedRemover remover;
    private final PollProperties properties;

    FeedService(FeedRepository feeds, FeedCreator creator, FirstIngest firstIngest, FeedUpdater updater,
            FeedRemover remover, PollProperties properties) {
        this.feeds = feeds;
        this.creator = creator;
        this.firstIngest = firstIngest;
        this.updater = updater;
        this.remover = remover;
        this.properties = properties;
    }

    /** Not transactional, see FeedCreator: the download must not hold a connection. */
    public FeedResponse create(CreateFeedRequest request) {
        FeedCreator.Created created = creator.create(request);
        firstIngest.run(created.feed(), created.download());
        return toResponse(created.feed());
    }

    public FeedResponse get(long id) {
        return toResponse(requireFeed(id));
    }

    @Transactional(readOnly = true)
    public Page<FeedResponse> list(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("id").ascending());
        return feeds.findAll(pageRequest).map(this::toResponse);
    }

    /** See FeedUpdater for the order of the steps and what a failure part-way leaves behind. */
    public FeedResponse patch(long id, PatchFeedRequest request) {
        updater.patch(id, request);
        return toResponse(requireFeed(id));
    }

    /** Removes the feed and the articles only it linked to, in one transaction. The source row is kept. */
    public void delete(long id) {
        remover.delete(id);
    }

    private Feed requireFeed(long id) {
        return feeds.findWithSourceById(id).orElseThrow(() -> ApiException.feedNotFound(id));
    }

    private FeedResponse toResponse(Feed feed) {
        return FeedResponse.of(feed, AdminAccess.isAdmin(), properties.failingThreshold());
    }

    static String feedText(long id, String what) {
        return "Feed " + id + " " + what;
    }
}
