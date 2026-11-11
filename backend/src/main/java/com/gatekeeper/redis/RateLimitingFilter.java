package com.gatekeeper.redis;

import com.gatekeeper.auth.GateKeeperPrincipal;
import com.gatekeeper.auth.SecurityUtils;
import com.gatekeeper.common.exception.ErrorResponse;
import com.gatekeeper.common.exception.GlobalExceptionHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Optional;

@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private final RedisRateLimiter rateLimiter;
    private final JsonMapper jsonMapper;
    private final boolean enabled;
    private final long authCapacity;
    private final double authRefillPerSecond;
    private final long bookingUserCapacity;
    private final double bookingUserRefillPerSecond;
    private final long bookingIpCapacity;
    private final double bookingIpRefillPerSecond;

    public RateLimitingFilter(
            RedisRateLimiter rateLimiter,
            JsonMapper jsonMapper,
            @Value("${gatekeeper.rate-limit.enabled:true}") boolean enabled,
            @Value("${gatekeeper.rate-limit.auth.capacity:20}") long authCapacity,
            @Value("${gatekeeper.rate-limit.auth.refill-per-second:0.5}") double authRefillPerSecond,
            @Value("${gatekeeper.rate-limit.booking-user.capacity:30}") long bookingUserCapacity,
            @Value("${gatekeeper.rate-limit.booking-user.refill-per-second:2.0}") double bookingUserRefillPerSecond,
            @Value("${gatekeeper.rate-limit.booking-ip.capacity:1000}") long bookingIpCapacity,
            @Value("${gatekeeper.rate-limit.booking-ip.refill-per-second:100.0}") double bookingIpRefillPerSecond
    ) {
        this.rateLimiter = rateLimiter;
        this.jsonMapper = jsonMapper;
        this.enabled = enabled;
        this.authCapacity = authCapacity;
        this.authRefillPerSecond = authRefillPerSecond;
        this.bookingUserCapacity = bookingUserCapacity;
        this.bookingUserRefillPerSecond = bookingUserRefillPerSecond;
        this.bookingIpCapacity = bookingIpCapacity;
        this.bookingIpRefillPerSecond = bookingIpRefillPerSecond;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!enabled || !HttpMethod.POST.matches(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        String clientIp = extractClientIp(request);

        if ("/api/auth/login".equals(path)) {
            RedisRateLimiter.RateLimitResult ipResult = rateLimiter.consume(
                    "gatekeeper:rl:login:ip:" + clientIp,
                    authCapacity,
                    authRefillPerSecond
            );
            applyRateLimitHeaders(response, ipResult);
            if (!ipResult.allowed()) {
                writeTooManyRequests(response, ipResult, "Too many authentication attempts; please retry later");
                return;
            }
        } else if ("/api/auth/register".equals(path)) {
            RedisRateLimiter.RateLimitResult ipResult = rateLimiter.consume(
                    "gatekeeper:rl:register:ip:" + clientIp,
                    Math.max(authCapacity, 50L),
                    Math.max(authRefillPerSecond, 2.0)
            );
            applyRateLimitHeaders(response, ipResult);
            if (!ipResult.allowed()) {
                writeTooManyRequests(response, ipResult, "Too many registration attempts; please retry later");
                return;
            }
        } else if ("/api/orders/hold".equals(path) || (path.startsWith("/api/orders/") && path.endsWith("/confirm"))) {
            RedisRateLimiter.RateLimitResult ipResult = rateLimiter.consume(
                    "gatekeeper:rl:book:ip:" + clientIp,
                    bookingIpCapacity,
                    bookingIpRefillPerSecond
            );
            if (!ipResult.allowed()) {
                applyRateLimitHeaders(response, ipResult);
                writeTooManyRequests(response, ipResult, "Too many booking requests from this network");
                return;
            }

            Optional<GateKeeperPrincipal> principal = SecurityUtils.optionalPrincipal();
            if (principal.isPresent()) {
                RedisRateLimiter.RateLimitResult userResult = rateLimiter.consume(
                        "gatekeeper:rl:book:user:" + principal.get().userId(),
                        bookingUserCapacity,
                        bookingUserRefillPerSecond
                );
                applyRateLimitHeaders(response, userResult);
                if (!userResult.allowed()) {
                    writeTooManyRequests(response, userResult, "Booking rate limit exceeded for your account");
                    return;
                }
            } else {
                applyRateLimitHeaders(response, ipResult);
            }
        }

        filterChain.doFilter(request, response);
    }

    private void applyRateLimitHeaders(HttpServletResponse response, RedisRateLimiter.RateLimitResult result) {
        response.setHeader("X-RateLimit-Limit", String.valueOf(result.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remaining()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(result.resetEpochSeconds()));
    }

    private void writeTooManyRequests(
            HttpServletResponse response,
            RedisRateLimiter.RateLimitResult result,
            String message
    ) throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
        ErrorResponse body = ErrorResponse.of(message, GlobalExceptionHandler.currentTraceId());
        response.getWriter().write(jsonMapper.writeValueAsString(body));
    }

    private static String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }
}

