package com.paytm.wallet.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * Minimal auth: a bearer token identifies the caller. The token value itself IS the user id -
 * no session store, no JWT parsing. This exercise is not about auth sophistication; it is about
 * concurrency correctness, so we keep this intentionally small.
 */
@Component
@Order(2)
public class BearerAuthFilter extends HttpFilter {

    private static final String PREFIX = "Bearer ";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (isExempt(request)) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(PREFIX) || header.length() <= PREFIX.length()) {
            writeUnauthorized(response, request);
            return;
        }

        String userId = header.substring(PREFIX.length()).trim();
        if (userId.isEmpty()) {
            writeUnauthorized(response, request);
            return;
        }

        try {
            AuthContext.set(userId);
            chain.doFilter(request, response);
        } finally {
            AuthContext.clear();
        }
    }

    private boolean isExempt(HttpServletRequest request) {
        String path = request.getRequestURI();
        // /logs is deliberately public - see LogsController.
        return path.startsWith("/actuator") || path.equals("/logs");
    }

    private void writeUnauthorized(HttpServletResponse response, HttpServletRequest request) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = Map.of(
                "error", "unauthorized",
                "message", "Missing or malformed Authorization: Bearer <token> header",
                "timestamp", Instant.now().toString(),
                "correlationId", String.valueOf(org.slf4j.MDC.get(CorrelationIdFilter.MDC_KEY))
        );
        objectMapper.writeValue(response.getWriter(), body);
    }
}
