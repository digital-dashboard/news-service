package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.j11a.argus.feed.FeedInserter;
import com.j11a.argus.feed.NewFeed;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.source.Source;
import com.j11a.argus.source.SourceService;
import com.j11a.argus.testsupport.LogCapture;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SourceResolveAutomaticIT extends AbstractIntegrationTest {

    @Autowired
    private SourceService sources;

    @Autowired
    private FeedInserter inserter;

    private void feedOf(Source source, String url) {
        inserter.insert(new NewFeed(source.getId(), "F", url, null, null, Topic.NEWS, null)).orElseThrow();
    }

    @Test
    void aSiteLinkKeyNoSourceHasYetCreatesThatSource() {
        Source source = sources.resolveAutomatic("https://www.example.org/", null,
                URI.create("https://feeds.feedburner.com/x"));

        assertThat(source.getKey()).isEqualTo("example.org");
        assertThat(count("SELECT count(*) FROM source")).isOne();
    }

    @Test
    void noSiteLinkUsesTheHostKey() {
        Source source = sources.resolveAutomatic(null, null, URI.create("https://feeds.bbci.co.uk/news/rss.xml"));

        assertThat(source.getKey()).isEqualTo("bbci.co.uk");
    }

    @Test
    void bbcFeedsOnTheBbciHostJoinTheExistingBbcSourceBecauseASiblingFeedSharesItsHost() {
        Source bbc = sources.findOrCreate("bbc.co.uk", "https://www.bbc.co.uk/");
        feedOf(bbc, "https://feeds.bbci.co.uk/news/rss.xml");

        Source resolved = sources.resolveAutomatic("https://www.bbc.co.uk/sport", null,
                URI.create("https://feeds.bbci.co.uk/sport/rss.xml"));

        assertThat(resolved.getId()).isEqualTo(bbc.getId());
        assertThat(count("SELECT count(*) FROM source")).isOne();
    }

    @Test
    void aSiteLinkNamingAnExistingSourceWithNoVoucherFallsBackToTheHostKeyAndWarns() {
        Source victim = sources.findOrCreate("victim.example", "https://victim.example/");
        feedOf(victim, "https://feeds.victim.example/rss");

        try (LogCapture logs = LogCapture.start()) {
            Source resolved = sources.resolveAutomatic("https://victim.example/", null,
                    URI.create("https://stray.test/feed.xml?token=SECRET"));

            assertThat(resolved.getKey()).isEqualTo("stray.test");
            assertThat(logs.at(Level.WARN, SourceService.class)).singleElement().satisfies(event -> {
                assertThat(LogCapture.keyValues(event))
                        .containsEntry("sourceKey", "stray.test")
                        .containsEntry("url", "https://stray.test/feed.xml");
                assertThat(event.getFormattedMessage()).isEqualTo(
                        "Site link of https://stray.test/feed.xml names source victim.example"
                                + " but nothing vouches for it; using stray.test");
            });
            logs.assertNothingLogged("SECRET");
        }
        assertThat(count("SELECT count(*) FROM source")).isEqualTo(2);
    }

    @Test
    void aSelfLinkOnTheSiteLinkDomainVouchesForTheExistingSource() {
        Source existing = sources.findOrCreate("shared-news.com", "https://shared-news.com/");

        Source resolved = sources.resolveAutomatic("https://shared-news.com/", "https://cdn.shared-news.com/atom.xml",
                URI.create("https://feedproxy.test/x"));

        assertThat(resolved.getId()).isEqualTo(existing.getId());
    }

    @Test
    void aFeedHostOnTheSiteLinkDomainVouchesForTheExistingSource() {
        Source existing = sources.findOrCreate("shared-news.com", "https://shared-news.com/");

        Source resolved = sources.resolveAutomatic("https://shared-news.com/", null,
                URI.create("https://feeds.shared-news.com/x"));

        assertThat(resolved.getId()).isEqualTo(existing.getId());
    }
}
