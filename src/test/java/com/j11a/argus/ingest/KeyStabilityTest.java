package com.j11a.argus.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class KeyStabilityTest {

    @Test
    void bbcStyleGuidAndLink() {
        String guid = "https://www.bbc.co.uk/news/world-12345678?at_medium=custom7#0";
        String link = "https://www.bbc.co.uk/news/world-12345678?at_medium=custom7";
        String title = "BBC News Headline";
        String excerpt = "This is a summary of the BBC story.";
        List<String> categories = List.of("World", "News");

        assertThat(EntryKeys.guidKey(guid, link)).isEqualTo("https://bbc.co.uk/news/world-12345678");
        assertThat(EntryKeys.linkKey(link)).isEqualTo("https://bbc.co.uk/news/world-12345678");
        assertThat(ContentHash.of(title, excerpt, categories))
                .isEqualTo("95108e161711c54bcb87aa804355e3ecae79a4017b91e5f6e2aec0317e5b6b46");
    }

    @Test
    void wordPressPParamGuidAndLink() {
        String guid = "";
        String link = "http://www.example.com/?p=123&utm_source=rss";
        String title = "WordPress Blog Post";
        String excerpt = null;
        List<String> categories = List.of("Tech", "Updates");

        assertThat(EntryKeys.guidKey(guid, link)).isEqualTo("https://example.com?p=123");
        assertThat(EntryKeys.linkKey(link)).isEqualTo("https://example.com?p=123");
        assertThat(ContentHash.of(title, excerpt, categories))
                .isEqualTo("a66abb4a8d48f2a20e3a5cf27e4dd6ae23e1b736f107f7b4f9e6fd1fc19bead7");
    }

    @Test
    void ampUrlGuidAndLink() {
        String guid = "https://amp.example.org/article/2026/amp/?amp=1&ref=rss";
        String link = "https://m.example.org/article/2026/amp/";
        String title = "AMP Article Headline";
        String excerpt = "AMP content excerpt";
        List<String> categories = List.of("Mobile", "Web");

        assertThat(EntryKeys.guidKey(guid, link)).isEqualTo("https://example.org/article/2026");
        assertThat(EntryKeys.linkKey(link)).isEqualTo("https://example.org/article/2026");
        assertThat(ContentHash.of(title, excerpt, categories))
                .isEqualTo("4f103ba0f2b8240fc39072060074585493e2ba62f10834d86215de1c6d311d82");
    }

    @Test
    void nonUrlGuidAndLink() {
        String guid = "urn:uuid:f81d4fae-7dec-11d0-a765-00a0c91e6bf6";
        String link = "https://www.example.net/item/99";
        String title = "UUID Item";
        String excerpt = "Item excerpt";
        List<String> categories = List.of("General");

        assertThat(EntryKeys.guidKey(guid, link)).isEqualTo("urn:uuid:f81d4fae-7dec-11d0-a765-00a0c91e6bf6");
        assertThat(EntryKeys.linkKey(link)).isEqualTo("https://example.net/item/99");
        assertThat(ContentHash.of(title, excerpt, categories))
                .isEqualTo("91cfdf911b6e6e56fc58e4776709b145172a4c531d409bdca31edba034b56bd1");
    }

    @Test
    void longUrlThatGetsCapped() {
        String longUrl = "https://www.example.com/" + "a".repeat(600);
        String title = "Long URL Article";
        String excerpt = "Long URL excerpt";
        List<String> categories = List.of("Archived");

        assertThat(EntryKeys.guidKey(longUrl, longUrl))
                .isEqualTo("sha256:a9c378e196d3a6a5a8c4184f213a1fd5c1803407597774e7c049468d353e84b9");
        assertThat(EntryKeys.linkKey(longUrl))
                .isEqualTo("sha256:a9c378e196d3a6a5a8c4184f213a1fd5c1803407597774e7c049468d353e84b9");
        assertThat(ContentHash.of(title, excerpt, categories))
                .isEqualTo("f8458b6e37b4f1e59c00ee1d42541434916b94c9824feb0ec90a07c68bb7cb89");
    }
}
