package com.j11a.argus.feed.parse;

import com.j11a.argus.feed.parse.FeedParseException.Reason;
import com.rometools.modules.atom.modules.AtomLinkModule;
import com.rometools.rome.feed.atom.Link;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.feed.synd.SyndLink;
import com.rometools.rome.feed.synd.SyndPerson;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

public final class FeedParser {

    private static final String SELF_REL = "self";
    private static final String ALTERNATE_REL = "alternate";

    public ParsedFeed parse(byte[] body, URI feedUrl, @Nullable String contentType) throws FeedParseException {
        if (isBlank(body)) {
            throw new FeedParseException(Reason.EMPTY);
        }
        SyndFeed feed = FeedReader.read(body, contentType);
        String siteLink = RawValues.resolveHttp(feedUrl, siteLinkOf(feed));
        URI base = siteLink == null ? feedUrl : URI.create(siteLink);
        String feedAuthor = feed.getAuthors().stream()
                .map(SyndPerson::getName)
                .map(RawValues::trimToNull)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        List<String> rawGuids = EntryMapper.rawGuids(feed);
        List<ParsedEntry> entries = new ArrayList<>();
        for (int i = 0; i < feed.getEntries().size(); i++) {
            entries.add(EntryMapper.map(feed.getEntries().get(i), rawGuids.get(i), base, feedAuthor));
        }
        return new ParsedFeed(
                feed.getTitle(),
                siteLink,
                selfLink(feed, base),
                RawValues.trimToNull(feed.getLanguage()),
                entries);
    }

    // SyndFeed.getLink() can return the rel=self link of an Atom feed.
    private static @Nullable String siteLinkOf(SyndFeed feed) {
        return feed.getLinks().stream()
                .filter(link -> ALTERNATE_REL.equalsIgnoreCase(link.getRel()))
                .map(SyndLink::getHref)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(feed.getLink());
    }

    private static @Nullable String selfLink(SyndFeed feed, URI base) {
        Stream<String> syndLinks = feed.getLinks().stream()
                .filter(link -> SELF_REL.equalsIgnoreCase(link.getRel()))
                .map(SyndLink::getHref);
        Stream<String> atomModuleLinks = feed.getModule(AtomLinkModule.URI) instanceof AtomLinkModule module
                ? module.getLinks().stream().filter(link -> SELF_REL.equalsIgnoreCase(link.getRel())).map(Link::getHref)
                : Stream.empty();
        return Stream.concat(syndLinks, atomModuleLinks)
                .map(href -> RawValues.resolveLink(base, href))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static boolean isBlank(byte[] body) {
        for (byte b : body) {
            if (b > ' ') {
                return false;
            }
        }
        return true;
    }
}
