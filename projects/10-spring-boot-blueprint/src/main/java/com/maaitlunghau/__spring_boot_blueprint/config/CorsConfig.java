package com.maaitlunghau.__spring_boot_blueprint.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Web app cùng nguồn gốc dùng cookie (access_token/refresh_token/XSRF-TOKEN) nên
 * allowCredentials phải true — mà allowCredentials=true thì KHÔNG được dùng wildcard "*"
 * cho origin (trình duyệt sẽ tự chặn), phải khai rõ đúng origin của frontend.
 */
@Configuration
public class CorsConfig {

    private final String frontendUrl;

    public CorsConfig(@Value("${app.frontend-url}") String frontendUrl) {
        this.frontendUrl = frontendUrl;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowedOrigins(List.of(frontendUrl));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-XSRF-TOKEN"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return source;
    }
}
