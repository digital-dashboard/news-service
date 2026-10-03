package com.j11a.argus.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

/**
 * Collects every log event of the root logger while open, so a test can assert on levels, messages and structured
 * fields.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture() {
        appender.start();
        root.addAppender(appender);
    }

    public static LogCapture start() {
        return new LogCapture();
    }

    public List<ILoggingEvent> at(Level level) {
        return List.copyOf(appender.list).stream().filter(event -> event.getLevel() == level).toList();
    }

    /** The events of one level that the given class logged. */
    public List<ILoggingEvent> at(Level level, Class<?> logger) {
        return at(level).stream().filter(event -> logger.getName().equals(event.getLoggerName())).toList();
    }

    /** Fails if the secret appears in any captured event: message, structured fields or MDC, at any level. */
    public void assertNothingLogged(String secret) {
        for (ILoggingEvent event : List.copyOf(appender.list)) {
            assertThat(event.getFormattedMessage() + keyValues(event) + event.getMDCPropertyMap())
                    .doesNotContain(secret);
        }
    }

    public List<String> messagesAt(Level level) {
        return at(level).stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** The structured fields of an event, as logged through the SLF4J fluent API. */
    public static Map<String, Object> keyValues(ILoggingEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>();
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs != null) {
            pairs.forEach(pair -> fields.put(pair.key, pair.value));
        }
        return fields;
    }

    /** A field of an event: its key-value if it has one, otherwise the MDC entry of that name. */
    public static @Nullable Object field(ILoggingEvent event, String key) {
        Object value = keyValues(event).get(key);
        return value != null ? value : event.getMDCPropertyMap().get(key);
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        appender.stop();
    }
}
