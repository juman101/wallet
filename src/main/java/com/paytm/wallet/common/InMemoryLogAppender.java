package com.paytm.wallet.common;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.UnsynchronizedAppenderBase;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Backs the public {@code GET /logs} endpoint. The exercise asks for "publicly viewable" logs;
 * Render's own log console is private to the account owner, so rather than depend on a screen
 * recording, the running service keeps a bounded in-memory ring buffer of its own structured
 * JSON log lines (the exact same lines STDOUT gets, same encoder) and serves them itself.
 *
 * <p>Wired as a second Logback appender on the root logger (see logback-spring.xml) - not a
 * Spring bean, since Logback configures itself before the Spring context exists. The buffer is a
 * static field for that reason; {@link LogsController} reads it via {@link #recent(int)}.
 */
public class InMemoryLogAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private static final int DEFAULT_CAPACITY = 2000;
    private static final Deque<String> BUFFER = new ArrayDeque<>(DEFAULT_CAPACITY);
    private static final Object LOCK = new Object();

    private Encoder<ILoggingEvent> encoder;

    public void setEncoder(Encoder<ILoggingEvent> encoder) {
        this.encoder = encoder;
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (encoder == null) {
            return;
        }
        String line = new String(encoder.encode(event), StandardCharsets.UTF_8).strip();
        synchronized (LOCK) {
            if (BUFFER.size() >= DEFAULT_CAPACITY) {
                BUFFER.removeFirst();
            }
            BUFFER.addLast(line);
        }
    }

    /** Most recent {@code limit} raw JSON log lines, oldest first. */
    public static List<String> recent(int limit) {
        synchronized (LOCK) {
            int size = BUFFER.size();
            int skip = Math.max(0, size - limit);
            List<String> out = new ArrayList<>(Math.min(limit, size));
            int i = 0;
            for (String line : BUFFER) {
                if (i++ >= skip) {
                    out.add(line);
                }
            }
            return out;
        }
    }
}
