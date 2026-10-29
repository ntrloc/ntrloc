package org.ntrloc.ui;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The server's log lines since the current test opened its page, for a failed UI test's capture
 * (server.log, beside the browser's console.log). The server runs in this JVM, so its lines pass
 * through here; which ones depends on the levels the test application.yml sets.
 *
 * Attached on each page open rather than once: Spring Boot resets logging when a context starts,
 * which drops any appender added before it.
 */
final class ServerLogCapture extends AppenderBase<ILoggingEvent> {

    private static final String NAME = "ui-test-server-log";
    private static final int MAX_LINES = 2000;
    private static final ServerLogCapture INSTANCE = new ServerLogCapture();

    private final Deque<String> lines = new ArrayDeque<>();

    private ServerLogCapture() {
        setName(NAME);
    }

    /** Starts a fresh capture: attached to the root logger if it isn't already, and emptied. */
    static void restart() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (root.getAppender(NAME) == null) {
            INSTANCE.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
            INSTANCE.start();
            root.addAppender(INSTANCE);
        }
        synchronized (INSTANCE.lines) {
            INSTANCE.lines.clear();
        }
    }

    static List<String> lines() {
        synchronized (INSTANCE.lines) {
            return List.copyOf(INSTANCE.lines);
        }
    }

    @Override
    protected void append(ILoggingEvent event) {
        String line = Instant.ofEpochMilli(event.getTimeStamp()) + " " + event.getLevel() + " "
                + event.getLoggerName() + " - " + event.getFormattedMessage();
        synchronized (lines) {
            if (lines.size() == MAX_LINES) {
                lines.removeFirst();
            }
            lines.addLast(line);
        }
    }
}
