package com.j11a.argus.testsupport;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Builds the smallest RSS feed with a chosen site link, self link and items, for identity and redirect tests. */
public final class RssBody {

    public static final String CONTENT_TYPE = "application/rss+xml; charset=utf-8";

    private static final Pattern SELF_LINK_TAG = Pattern.compile("<(?:atom:)?link\\b[^>]*\\brel=\"self\"[^>]*>");
    private static final Pattern HREF = Pattern.compile("href=\"[^\"]*\"");

    private RssBody() {
    }

    /**
     * The feed with its self link pointed at selfLink. Feeds are matched by their self link too, so a test that
     * creates several feeds from one fixture gives each its own.
     */
    public static byte[] withSelfLink(byte[] feed, String selfLink) {
        String xml = new String(feed, StandardCharsets.UTF_8);
        String href = Matcher.quoteReplacement("href=\"" + selfLink + "\"");
        String replaced = SELF_LINK_TAG.matcher(xml).replaceAll(
                match -> HREF.matcher(match.group()).replaceFirst(href));
        return replaced.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] rss(String siteLink, @Nullable String selfLink, String... itemSlugs) {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <rss xmlns:atom="http://www.w3.org/2005/Atom" version="2.0"><channel>
                <title>Stub feed</title>
                """);
        xml.append("<link>").append(siteLink).append("</link>\n");
        if (selfLink != null) {
            xml.append("<atom:link href=\"").append(selfLink).append("\" rel=\"self\" type=\"application/rss+xml\"/>\n");
        }
        for (String slug : itemSlugs) {
            xml.append("<item><title>Item ").append(slug).append("</title><link>https://news.stub.test/")
                    .append(slug).append("</link><guid isPermaLink=\"false\">").append(slug)
                    .append("</guid><pubDate>Tue, 06 Oct 2026 07:30:00 GMT</pubDate></item>\n");
        }
        return xml.append("</channel></rss>").toString().getBytes(StandardCharsets.UTF_8);
    }
}
