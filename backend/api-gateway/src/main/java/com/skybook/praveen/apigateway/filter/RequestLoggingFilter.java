package com.skybook.praveen.apigateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * One structured log line per request: method, path, downstream target
 * (unknown to this filter - just method+path+status+latency+correlation id;
 * "downstream target" per design doc §7 is inferred by a human reading the
 * path against the routing table, not logged separately, to avoid coupling
 * this filter to GatewayRoutesConfig's route definitions).
 *
 * Runs first (Ordered.HIGHEST_PRECEDENCE) so it wraps every other filter
 * and captures the correlation id before JWT/rate-limit filters run, and the
 * final status/latency after they've all completed.
 *
 * Plain Servlet Filter rather than a gateway HandlerFilterFunction - this
 * fleet is otherwise all plain Spring MVC/Servlet, and a global filter
 * applies uniformly to every route without threading it through each
 * RouterFunction bean in GatewayRoutesConfig.
 */
@Slf4j
@Component
@Order(Integer.MIN_VALUE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        // Links the business-facing correlation id to the OTel trace at its
        // origin: the shared JSON log layout (skybook-logback-base.xml)
        // whitelists this MDC key, so every gateway log line carries both
        // correlationId and the agent-injected trace_id side by side
        // (OBSERVABILITY_MODULE.md §5).
        MDC.put("correlationId", correlationId);

        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long latencyMs = System.currentTimeMillis() - start;
            log.info("{} {} -> {} ({} ms) [{}]",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), latencyMs, correlationId);
            MDC.remove("correlationId");
        }
    }
}
