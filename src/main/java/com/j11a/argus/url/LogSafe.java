package com.j11a.argus.url;

import java.nio.channels.ClosedChannelException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Turns exceptions and free text into values that are safe to put in a log line: no query strings, no user-info. */
public final class LogSafe {

    static final int MAX_MESSAGE_LENGTH = 300;
    private static final String UNPARSEABLE_URL = "[url]";
    private static final Pattern URL = Pattern.compile("https?://\\S+");

    private LogSafe() {
    }

    /** Replaces every http(s) URL in the text with its redacted form. */
    public static String redactUrls(String text) {
        return URL.matcher(text).replaceAll(match -> Matcher.quoteReplacement(redactedOrPlaceholder(match.group())));
    }

    /** The root cause's message with URLs redacted and capped; null when there is none. */
    public static @Nullable String errorMessage(Throwable error) {
        String message = rootCause(error).getMessage();
        if (message == null || message.isBlank()) {
            return null;
        }
        String redacted = redactUrls(message.strip());
        return redacted.length() > MAX_MESSAGE_LENGTH ? redacted.substring(0, MAX_MESSAGE_LENGTH) : redacted;
    }

    public static String errorType(Throwable error) {
        return rootCause(error).getClass().getSimpleName();
    }

    private static String redactedOrPlaceholder(String url) {
        String redacted = HttpUrls.redact(url);
        return redacted.isEmpty() ? UNPARSEABLE_URL : redacted;
    }

    /**
     * The root cause, except that a ClosedChannelException is never reported: the JDK HttpClient hangs one under every
     * ConnectException, and it says nothing about why the connection failed.
     */
    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current
                && !(current.getCause() instanceof ClosedChannelException)) {
            current = current.getCause();
        }
        return current;
    }
}
