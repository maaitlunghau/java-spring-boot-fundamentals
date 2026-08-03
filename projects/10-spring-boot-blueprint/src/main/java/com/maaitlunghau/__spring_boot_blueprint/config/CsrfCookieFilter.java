package com.maaitlunghau.__spring_boot_blueprint.config;

import java.io.IOException;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Spring Security 6 sinh CsrfToken kiểu "lazy" — token chỉ thực sự được ghi vào cookie
 * XSRF-TOKEN khi có gì đó gọi tới csrfToken.getToken(). Không có filter này, cookie
 * XSRF-TOKEN sẽ không bao giờ xuất hiện cho tới khi vô tình có 1 request nào đó tự
 * chạm vào token trước.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute("_csrf");
        if (csrfToken != null) {
            csrfToken.getToken();
        }

        filterChain.doFilter(request, response);
    }
}
