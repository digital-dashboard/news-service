package com.j11a.argus.testsupport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

/** Collects every log event of the root logger while open, so a test can assert on levels, messages and structured fields. */
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

    public List<String> messagesAt(Level level) {
        return at(level).stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** The structured fields of an event, as logged through the SLF4J fluent API. */
    public static Map<String, Object> keyValues(ILoggingEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (event.getKeyValuePairs() != null) {
            event.getKeyValuePairs().forEach(pair -> fields.put(pair.key, pair.value));
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
