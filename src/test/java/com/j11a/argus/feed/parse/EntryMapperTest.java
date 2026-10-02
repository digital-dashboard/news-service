package com.j11a.argus.feed.parse;

import static org.assertj.core.api.Assertions.assertThat;

import com.rometools.rome.feed.rss.Channel;
import com.rometools.rome.feed.rss.Guid;
import com.rometools.rome.feed.rss.Item;
import com.rometools.rome.feed.synd.SyndCategoryImpl;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndContentImpl;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndEntryImpl;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.feed.synd.SyndFeedImpl;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntryMapperTest {

    private static final URI BASE = URI.create("https://news.example.test/");

    private static Item itemWithGuid(String guid) {
        Item item = new Item();
        item.setGuid(new Guid());
        item.getGuid().setValue(guid);
        return item;
    }

    private static SyndContent html(String value) {
        SyndContent content = new SyndContentImpl();
        content.setType("text/html");
        content.setValue(value);
        return content;
    }

    private static ParsedEntry map(SyndEntry entry) {
        return EntryMapper.map(entry, null, BASE, null);
    }

    @Test
    void rawGuidsComeFromTheWireItemsWhenTheyLineUpWithTheEntries() {
        Channel channel = new Channel("rss_2.0");
        channel.setItems(List.of(itemWithGuid(" a "), itemWithGuid("b")));
        SyndFeed feed = new SyndFeedImpl(channel, true);

        assertThat(EntryMapper.rawGuids(feed)).containsExactly("a", "b");
    }

    @Test
    void rawGuidsFallBackToTheEntryUrisWhenTheWireItemsNoLongerLineUp() {
        Channel channel = new Channel("rss_2.0");
        channel.setItems(List.of(itemWithGuid("a"), itemWithGuid("b")));
        SyndFeed feed = new SyndFeedImpl(channel, true);
        SyndEntry only = new SyndEntryImpl();
        only.setUri("  urn:only  ");
        feed.setEntries(List.of(only));

        assertThat(EntryMapper.rawGuids(feed)).containsExactly("urn:only");
    }

    @Test
    void theExcerptSkipsAContentWithoutTextAndTakesTheNextOne() {
        SyndEntry entry = new SyndEntryImpl();
        entry.setDescription(html("<img src=\"https://news.example.test/a.png\">"));
        entry.setContents(List.of(html("<br>"), html("<p>Hello <b>world</b></p>")));

        assertThat(map(entry).excerpt()).isEqualTo("Hello world");
    }

    @Test
    void theExcerptIsEmptyWhenNeitherDescriptionNorContentHasText() {
        SyndEntry entry = new SyndEntryImpl();
        entry.setContents(List.of(html("<br>")));

        assertThat(map(entry).excerpt()).isEmpty();
    }

    @Test
    void anEntryWithNoDatesStillMapsItsCategories() {
        SyndEntry entry = new SyndEntryImpl();
        SyndCategoryImpl category = new SyndCategoryImpl();
        category.setName(" Sport ");
        entry.setCategories(List.of(category));

        ParsedEntry parsed = map(entry);

        assertThat(parsed.categories()).containsExactly("Sport");
        assertThat(parsed.publishedAt()).isNull();
        assertThat(parsed.updatedAt()).isNull();
    }
}
