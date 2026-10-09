package com.fixgo.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Tags every request with an id (MDC key "rid", echoed as X-Request-Id) and logs one line per API call:
 * method, path, status, duration. The query string is never logged. An incoming X-Request-Id is only trusted when
 * it is short and plain, so a client cannot inject newlines or fake log lines.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || path.equals("/api/v1/ping");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader("X-Request-Id");
        String rid = incoming != null && SAFE_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString().substring(0, 8);
        MDC.put("rid", rid);
        response.setHeader("X-Request-Id", rid);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            log.info("http {} {} -> {} {}ms", request.getMethod(), request.getRequestURI(), response.getStatus(),
                    (System.nanoTime() - started) / 1_000_000);
            MDC.remove("rid");
        }
    }
}
