package com.gatekeeper.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String REPLICA_HEADER = "X-GateKeeper-Replica";
    public static final String MDC_TRACE_ID_KEY = "traceId";

    private final String replicaId;

    public RequestIdFilter(@Value("${BACKEND_REPLICA_ID:local}") String replicaId) {
        this.replicaId = replicaId;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        String traceId = (incoming != null && !incoming.isBlank() && incoming.length() <= 64)
                ? incoming.replaceAll("[^a-zA-Z0-9\\-_.]", "")
                : UUID.randomUUID().toString();
        if (traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_TRACE_ID_KEY, traceId);
        response.setHeader(REQUEST_ID_HEADER, traceId);
        response.setHeader(REPLICA_HEADER, replicaId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID_KEY);
        }
    }
}

