package com.lookahead.domain;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DLV-969: captures the operational lines of {@link Events} ({@code lookahead.event}, {@code .payment}, {@code .limit},
 * {@code .upstream}) in a test, the way ErrorLoggingTest captures the error handlers. Use in try-with-resources.
 */
public final class LogCapture implements AutoCloseable {
    private static final String[] NAMES = {"lookahead.event", "lookahead.payment", "lookahead.limit", "lookahead.upstream"};
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final List<Logger> loggers = new ArrayList<>();
    private final List<Level> levels = new ArrayList<>();

    private LogCapture() {
        appender.start();
        for (String name : NAMES) {
            var logger = (Logger) LoggerFactory.getLogger(name);
            loggers.add(logger); levels.add(logger.getLevel());
            logger.setLevel(Level.INFO);
            logger.addAppender(appender);
        }
    }

    public static LogCapture start() { return new LogCapture(); }

    /** Every captured line, oldest first (copied: the appender may still be written by another thread). */
    public List<ILoggingEvent> lines() { synchronized (appender) { return List.copyOf(appender.list); } }

    /** The lines whose {@code event.action} is {@code action}. */
    public List<ILoggingEvent> lines(String action) {
        return lines().stream().filter(line -> action.equals(keys(line).get("event.action"))).toList();
    }

    /** The one line with {@code event.action} {@code action}. */
    public ILoggingEvent only(String action) {
        var found = lines(action);
        assertThat(found).as("lines with event.action " + action + " in " + messages()).hasSize(1);
        return found.getFirst();
    }

    public List<String> messages() { return lines().stream().map(ILoggingEvent::getFormattedMessage).toList(); }

    /** The line's key-value pairs in order; a key given twice keeps its last value. */
    public static Map<String, Object> keys(ILoggingEvent line) {
        var keys = new LinkedHashMap<String, Object>();
        if (line.getKeyValuePairs() != null) line.getKeyValuePairs().forEach(pair -> keys.put(pair.key, pair.value));
        return keys;
    }

    /** No captured message or key value contains any of {@code values} (names, emails, secrets). */
    public void containsNone(String... values) {
        for (var line : lines()) {
            var text = new StringBuilder(line.getFormattedMessage());
            keys(line).values().forEach(value -> text.append(' ').append(value));
            for (String value : values) assertThat(text.toString()).as("log line must not carry personal data").doesNotContainIgnoringCase(value);
        }
    }

    @Override public void close() {
        for (int i = 0; i < loggers.size(); i++) { loggers.get(i).detachAppender(appender); loggers.get(i).setLevel(levels.get(i)); }
        appender.stop();
    }
}
