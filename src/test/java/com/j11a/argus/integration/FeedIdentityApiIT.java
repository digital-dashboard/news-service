package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.api.CreateFeedRequest;
import com.j11a.argus.feed.api.FeedResponse;
import com.j11a.argus.source.Source;
import com.j11a.argus.source.SourceResolver;
import com.j11a.argus.source.SourceService;
import com.j11a.argus.testsupport.AdminKeys;
import com.j11a.argus.testsupport.MergeData;
import com.j11a.argus.testsupport.RssBody;
import java.net.URI;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class FeedIdentityApiIT extends AbstractIntegrationTest {

    private static final String EXISTING_PATH = "/identity/existing.xml";
    private static final String SITE = "https://site-a.example.test/";
    private static final String STORED_ON_HOSTNAME = "http://feeds.example.test/identity/existing.xml";

    @Autowired
    private SourceService sources;

    private long createExisting() {
        stub.serve(EXISTING_PATH, 200, RssBody.CONTENT_TYPE, RssBody.rss(SITE, null, "a1"));
        return feedService.create(new CreateFeedRequest(stub.baseUrl() + EXISTING_PATH, null, Topic.NEWS, null)).id();
    }

    /** A stored feed on a hostname, so the www. variant is a valid URL; it is never fetched. */
    private long insertExistingOnHostname() {
        MergeData data = new MergeData(jdbcClient);
        return data.feed(data.source("feeds.example.test", null), STORED_ON_HOSTNAME);
    }

    private ResultActions postFeed(String url, Long sourceId) throws Exception {
        String body = "{\"url\":\"" + url + "\",\"topic\":\"NEWS\"" + (sourceId == null ? "" : ",\"sourceId\":" + sourceId) + "}";
        return mockMvc.perform(post("/news/v2/feeds").header(AdminKeys.HEADER, AdminKeys.VALID)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions postFeed(String url) throws Exception {
        return postFeed(url, null);
    }

    private double conflicts(String kind) {
        return meters().counter("argus.feed.identity.conflict", "kind", kind);
    }

    static Stream<Arguments> enteredVariants() {
        return Stream.of(
                Arguments.of("scheme", (UnaryOperator<String>) url -> url.replace("http://", "https://")),
                Arguments.of("www", (UnaryOperator<String>) url -> url.replace("http://", "http://www.")),
                Arguments.of("trailing slash", (UnaryOperator<String>) url -> url + "/"),
                Arguments.of("scheme case", (UnaryOperator<String>) url -> url.replace("http://feeds.", "HTTP://FEEDS.")));
    }

    @ParameterizedTest(name = "{0} variant of a stored url")
    @MethodSource("enteredVariants")
    void aVariantOfAStoredUrlIs409KindEnteredWithNothingStoredAndNothingDownloaded(String name,
            UnaryOperator<String> variant) throws Exception {
        long existing = insertExistingOnHostname();
        int requestsBefore = stub.requests().size();
        double before = conflicts("entered");

        postFeed(variant.apply(STORED_ON_HOSTNAME))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FEED_URL_CONFLICT"))
                .andExpect(jsonPath("$.existingFeedId").value(existing))
                .andExpect(jsonPath("$.kind").value("entered"));

        assertThat(count("SELECT count(*) FROM feed")).isOne();
        assertThat(stub.requests()).hasSize(requestsBefore);
        assertThat(conflicts("entered")).isEqualTo(before + 1);
    }

    @ParameterizedTest(name = "a {0} redirect onto a stored feed")
    @ValueSource(ints = {301, 302})
    void aUrlThatRedirectsToAStoredFeedIs409KindRedirectWithNothingStored(int status) throws Exception {
        long existing = createExisting();
        String oldPath = "/identity/moved-" + status + ".xml";
        stub.redirect(oldPath, status, stub.baseUrl() + EXISTING_PATH);
        double before = conflicts("redirect");

        postFeed(stub.baseUrl() + oldPath)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existingFeedId").value(existing))
                .andExpect(jsonPath("$.kind").value("redirect"));

        assertThat(count("SELECT count(*) FROM feed")).isOne();
        assertThat(count("SELECT count(*) FROM source")).isOne();
        assertThat(conflicts("redirect")).isEqualTo(before + 1);
    }

    @Test
    void aFeedWhoseSelfLinkIsAStoredFeedIs409KindSelfLinkAndItsNewSourceIsRolledBack() throws Exception {
        long existing = createExisting();
        String path = "/identity/mirror.xml";
        stub.serve(path, 200, RssBody.CONTENT_TYPE,
                RssBody.rss("https://site-b.example.test/", stub.baseUrl() + EXISTING_PATH, "b1"));
        double before = conflicts("self_link");

        postFeed(stub.baseUrl() + path)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existingFeedId").value(existing))
                .andExpect(jsonPath("$.kind").value("self_link"));

        assertThat(count("SELECT count(*) FROM feed")).isOne();
        assertThat(count("SELECT count(*) FROM source")).isOne();
        assertThat(conflicts("self_link")).isEqualTo(before + 1);
    }

    @Test
    void aNewFeedStoresItsCleanedSelfLink() throws Exception {
        String path = "/identity/with-self.xml";
        stub.serve(path, 200, RssBody.CONTENT_TYPE,
                RssBody.rss(SITE, "HTTPS://Feeds.Example.test/self.xml?x=1#frag", "s1"));

        postFeed(stub.baseUrl() + path).andExpect(status().isCreated());

        String selfUrl = jdbcClient.sql("SELECT self_url FROM feed").query(String.class).single();
        assertThat(selfUrl).isEqualTo("https://feeds.example.test/self.xml?x=1");
    }

    @Test
    void anExplicitSourceIdBypassesAutomaticResolutionEntirely() throws Exception {
        Source explicit = sources.findOrCreate("explicit.example", null);
        String path = "/identity/explicit.xml";
        stub.serve(path, 200, RssBody.CONTENT_TYPE, RssBody.rss("https://elsewhere.example.test/", null, "e1"));

        postFeed(stub.baseUrl() + path, explicit.getId()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.source.id").value(explicit.getId()));

        assertThat(count("SELECT count(*) FROM source")).isOne();
    }

    @Test
    void aStraySiteLinkNamingAnotherOutletsSourceLandsOnTheFeedHostKey() throws Exception {
        MergeData data = new MergeData(jdbcClient);
        long outlet = data.source("outlet.co.uk", "https://www.outlet.co.uk/");
        data.feed(outlet, "https://feeds.outlet.co.uk/rss");
        String path = "/identity/stray.xml";
        stub.serve(path, 200, RssBody.CONTENT_TYPE, RssBody.rss("https://www.outlet.co.uk/", null, "x1"));

        FeedResponse created = feedService.create(
                new CreateFeedRequest(stub.baseUrl() + path, null, Topic.NEWS, null));

        String hostKey = SourceResolver.hostKey(URI.create(stub.baseUrl() + path));
        assertThat(created.source().name()).isEqualTo(hostKey).isNotEqualTo("outlet.co.uk");
        assertThat(count("SELECT count(*) FROM feed WHERE source_id = " + outlet)).isOne();
    }
}
