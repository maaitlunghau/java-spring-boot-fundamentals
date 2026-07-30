package com.maaitlunghau.__spring_boot_blueprint.filter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.maaitlunghau.__spring_boot_blueprint.common.dto.ApiResponse;
import com.maaitlunghau.__spring_boot_blueprint.util.RequestUtils;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Map<String, String> RATE_LIMITED_PATHS = Map.of(
        "/api/v1/auth/login", "login",
        "/api/v1/auth/register", "register",
        "/api/v1/auth/refresh", "refresh"
    );
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String label = RATE_LIMITED_PATHS.get(request.getRequestURI());
        if (label == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = "auth:ratelimit:" + label + ":" + RequestUtils.clientIp(request);

        Long attempts = redisTemplate.opsForValue().increment(key);
        if (attempts != null && attempts == 1L) {
            redisTemplate.expire(key, WINDOW);
        }
        if (attempts != null && attempts > MAX_ATTEMPTS) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.message(429, "Too many requests. Please try again later.")
            ));

            return;
        }

        filterChain.doFilter(request, response);
    }
}