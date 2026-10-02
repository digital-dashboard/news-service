package com.j11a.argus.feed.parse;

import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jspecify.annotations.Nullable;

public final class ExcerptBuilder {

    public static final int MAX_EXCERPT_LENGTH = 500;
    private static final String ELLIPSIS = "…";
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0]+");

    private ExcerptBuilder() {
    }

    public static String fromHtml(@Nullable String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document document = Jsoup.parseBodyFragment(html);
        String text = WHITESPACE.matcher(document.body().text()).replaceAll(" ").strip();
        return truncate(text);
    }

    private static String truncate(String text) {
        if (text.length() <= MAX_EXCERPT_LENGTH) {
            return text;
        }
        int end = Character.isWhitespace(text.charAt(MAX_EXCERPT_LENGTH))
                ? MAX_EXCERPT_LENGTH
                : text.lastIndexOf(' ', MAX_EXCERPT_LENGTH);
        if (end <= 0) {
            end = MAX_EXCERPT_LENGTH;
            if (Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
        }
        return text.substring(0, end).stripTrailing() + ELLIPSIS;
    }
}
