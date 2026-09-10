package com.paytm.wallet.common;

import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emits one structured JSON log line per meaningful domain event (wallet.created,
 * transfer.debited, transfer.declined.insufficient_funds, ...). correlation_id is already in
 * MDC (see CorrelationIdFilter) so every line here is traceable back to the originating
 * HTTP request without repeating it manually.
 */
public final class DomainEvents {

    private static final Logger log = LoggerFactory.getLogger("domain-events");

    private DomainEvents() {
    }

    public static void emit(String event, Map<String, Object> fields) {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("event", event);
        ordered.putAll(fields);
        log.info(event, StructuredArguments.entries(ordered));
    }

    public static Builder builder(String event) {
        return new Builder(event);
    }

    public static final class Builder {
        private final String event;
        private final Map<String, Object> fields = new LinkedHashMap<>();

        private Builder(String event) {
            this.event = event;
        }

        public Builder field(String key, Object value) {
            fields.put(key, value);
            return this;
        }

        public void emit() {
            DomainEvents.emit(event, fields);
        }
    }
}
