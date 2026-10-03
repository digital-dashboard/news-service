package com.j11a.argus.url;

import java.nio.channels.ClosedChannelException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Turns exceptions and free text into values that are safe to put in a log line: no query strings, no user-info. */
public final class LogSafe {

    public static final int MAX_MESSAGE_LENGTH = 300;
    private static final int MAX_CAUSE_DEPTH = 32;
    private static final String UNPARSEABLE_URL = "[url]";
    private static final String TRAILING_PUNCTUATION = "\"'),;:]>";
    private static final Pattern URL = Pattern.compile("(?i)https?://\\S+");
    // Tokens right after a URL whose query held a space: "?k=ab cd=SECRET" must not leave "cd=SECRET" behind.
    private static final Pattern QUERY_REMAINDER = Pattern.compile("(?:\\s+\\S*=\\S*)+");
    // A scheme-less "user:pw@host/path?query": keeps host and path only.
    private static final Pattern BARE_USER_INFO = Pattern.compile(
            "[^\\s/@:]+:[^\\s/@]+@([\\w.-]+(?::\\d+)?(?:/[^\\s?#]*)?)(?:[?#]\\S*)?");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    private LogSafe() {
    }

    /** Replaces every http(s) URL in the text with its redacted form. */
    public static String redactUrls(String text) {
        Matcher matcher = URL.matcher(text);
        StringBuilder redacted = new StringBuilder();
        int copiedUpTo = 0;
        while (matcher.find()) {
            redacted.append(text, copiedUpTo, matcher.start());
            String match = matcher.group();
            String url = withoutTrailingPunctuation(match);
            redacted.append(redactedOrPlaceholder(url)).append(match, url.length(), match.length());
            copiedUpTo = url.indexOf('?') >= 0 ? afterQueryRemainder(text, matcher.end()) : matcher.end();
            matcher.region(copiedUpTo, text.length());
        }
        redacted.append(text, copiedUpTo, text.length());
        return BARE_USER_INFO.matcher(redacted).replaceAll("$1");
    }

    /**
     * The root cause's message, cleaned by {@link #message}; null when there is none. A ClosedChannelException cause
     * is never reported (see rootCause).
     */
    public static @Nullable String errorMessage(Throwable error) {
        return message(rootCause(error).getMessage());
    }

    public static String errorType(Throwable error) {
        return rootCause(error).getClass().getSimpleName();
    }

    /** Stripped, URLs redacted, control characters replaced, capped; null when there is nothing left to say. */
    public static @Nullable String message(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return sanitize(redactUrls(raw.strip()), MAX_MESSAGE_LENGTH);
    }

    /** Replaces control characters (newlines included) with a space and caps the length. */
    public static String sanitize(String text, int maxLength) {
        String clean = CONTROL.matcher(text).replaceAll(" ");
        return clean.length() > maxLength ? clean.substring(0, maxLength) : clean;
    }

    private static String withoutTrailingPunctuation(String match) {
        int end = match.length();
        while (end > 0 && TRAILING_PUNCTUATION.indexOf(match.charAt(end - 1)) >= 0) {
            end--;
        }
        return match.substring(0, end);
    }

    private static int afterQueryRemainder(String text, int from) {
        Matcher remainder = QUERY_REMAINDER.matcher(text).region(from, text.length());
        return remainder.lookingAt() ? remainder.end() : from;
    }

    private static String redactedOrPlaceholder(String url) {
        String redacted = HttpUrls.redact(url);
        return redacted.isEmpty() ? UNPARSEABLE_URL : redacted;
    }

    /**
     * Stops above a ClosedChannelException cause: the JDK HttpClient typically attaches one under a ConnectException
     * and it does not explain the failure. The depth cap keeps a cause cycle from looping forever.
     */
    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH; depth++) {
            Throwable cause = current.getCause();
            if (cause == null || cause == current || cause instanceof ClosedChannelException) {
                break;
            }
            current = cause;
        }
        return current;
    }
}
