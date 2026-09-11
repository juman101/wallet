package com.paytm.wallet.common;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public, unauthenticated log viewer - the exercise asks for logs to be "publicly viewable."
 * Not a general-purpose production pattern (a real system would use a log aggregator with its
 * own access control); here it exists specifically so an evaluator can watch domain events
 * stream during a burst without needing Render dashboard access. See InMemoryLogAppender.
 *
 * <p>Served as newline-delimited JSON (one already-valid JSON object per line, oldest first) -
 * not wrapped in a JSON envelope, so each line stays directly readable instead of coming back as
 * an escaped JSON string nested inside another JSON string.
 */
@RestController
public class LogsController {

    private static final int MAX_LIMIT = 2000;
    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    @GetMapping(value = "/logs")
    public ResponseEntity<String> recentLogs(@RequestParam(name = "limit", defaultValue = "200") int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<String> lines = InMemoryLogAppender.recent(bounded);
        return ResponseEntity.ok().contentType(NDJSON).body(String.join("\n", lines));
    }
}
