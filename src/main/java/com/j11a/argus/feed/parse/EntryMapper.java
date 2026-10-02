package com.j11a.argus.feed.parse;

import com.rometools.rome.feed.module.DCModule;
import com.rometools.rome.feed.module.DCSubject;
import com.rometools.rome.feed.rss.Channel;
import com.rometools.rome.feed.rss.Item;
import com.rometools.rome.feed.synd.SyndCategory;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

final class EntryMapper {

    private EntryMapper() {
    }

    /**
     * The raw GUID of every entry, null where the feed gave none. SyndEntry.getUri() cannot tell: for RSS without a
     * guid ROME fills it with the link.
     */
    static List<String> rawGuids(SyndFeed feed) {
        List<String> guids = new ArrayList<>();
        if (feed.originalWireFeed() instanceof Channel channel && channel.getItems().size() == feed.getEntries().size()) {
            boolean rdf = channel.getFeedType().startsWith("rss_1");
            for (Item item : channel.getItems()) {
                guids.add(RawValues.trimToNull(rawGuid(item, rdf)));
            }
            return guids;
        }
        for (SyndEntry entry : feed.getEntries()) {
            guids.add(RawValues.trimToNull(entry.getUri()));
        }
        return guids;
    }

    private static @Nullable String rawGuid(Item item, boolean rdf) {
        // RSS 1.0 identifies an item by rdf:about.
        if (rdf) {
            return item.getUri();
        }
        return item.getGuid() == null ? null : item.getGuid().getValue();
    }

    static ParsedEntry map(SyndEntry entry, @Nullable String rawGuid, URI base, @Nullable String feedAuthor) {
        String author = RawValues.trimToNull(entry.getAuthor());
        return new ParsedEntry(
                rawGuid,
                RawValues.resolveHttp(base, entry.getLink()),
                entry.getTitle(),
                excerpt(entry),
                author != null ? author : feedAuthor,
                ImageSelector.select(entry, base),
                categories(entry),
                toInstant(entry.getPublishedDate()),
                toInstant(entry.getUpdatedDate()));
    }

    private static String excerpt(SyndEntry entry) {
        String fromDescription = ExcerptBuilder.fromHtml(valueOf(entry.getDescription()));
        if (!fromDescription.isEmpty()) {
            return fromDescription;
        }
        return entry.getContents().stream()
                .map(content -> ExcerptBuilder.fromHtml(valueOf(content)))
                .filter(text -> !text.isEmpty())
                .findFirst()
                .orElse("");
    }

    private static @Nullable String valueOf(@Nullable SyndContent content) {
        return content == null ? null : content.getValue();
    }

    private static List<String> categories(SyndEntry entry) {
        Stream<String> subjects = entry.getModule(DCModule.URI) instanceof DCModule dc
                ? dc.getSubjects().stream().map(DCSubject::getValue)
                : Stream.empty();
        return Stream.concat(entry.getCategories().stream().map(SyndCategory::getName), subjects)
                .map(RawValues::trimToNull)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private static @Nullable Instant toInstant(@Nullable Date date) {
        return date == null ? null : date.toInstant();
    }
}
