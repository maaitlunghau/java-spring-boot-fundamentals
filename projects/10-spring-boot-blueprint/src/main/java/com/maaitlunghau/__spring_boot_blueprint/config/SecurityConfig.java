package com.maaitlunghau.__spring_boot_blueprint.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;

import com.maaitlunghau.__spring_boot_blueprint.filter.RateLimitFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Chỉ đòi CSRF token cho request GHI dữ liệu (POST/PUT/PATCH/DELETE) VÀ không mang
     * theo header Bearer. Request có Bearer hợp lệ vốn miễn nhiễm CSRF tự nhiên — trình
     * duyệt không bao giờ tự gắn header Authorization vào request giả mạo cross-site như
     * nó tự gắn cookie — nên bắt CSRF token với client Postman/mobile chỉ làm gãy API
     * của họ một cách vô cớ, không tăng thêm bảo mật nào.
     */
    private static final RequestMatcher CSRF_REQUIRED_MATCHER = request -> {
        String method = request.getMethod();
        boolean isMutatingMethod = "POST".equals(method) || "PUT".equals(method)
            || "PATCH".equals(method) || "DELETE".equals(method);
        if (!isMutatingMethod) {
            return false;
        }

        String authHeader = request.getHeader("Authorization");
        boolean hasBearerToken = authHeader != null && authHeader.startsWith("Bearer ");
        return !hasBearerToken;
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final CustomAccessDeniedHandler customAccessDeniedHandler;
    private final RateLimitFilter rateLimitFilter;

    public SecurityConfig(
        JwtAuthenticationFilter jwtAuthenticationFilter,
        CustomAuthenticationEntryPoint authenticationEntryPoint,
        CustomAccessDeniedHandler customAccessDeniedHandler,
        RateLimitFilter rateLimitFilter
    ) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.customAccessDeniedHandler = customAccessDeniedHandler;
        this.rateLimitFilter = rateLimitFilter;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                // register/login: chưa có session/CSRF cookie nào để so khớp — bắt buộc miễn.
                // refresh/logout: hậu quả bị CSRF thấp (tối đa là bị cấp token mới/bị đăng xuất),
                // giữ miễn cho đơn giản, giống project 09. KHÔNG dùng wildcard "/api/v1/auth/**" —
                // sẽ vô tình miễn luôn DELETE /api/v1/auth/sessions/{id} (thu hồi session, có hậu
                // quả thật, phải được CSRF bảo vệ như mọi endpoint ghi dữ liệu khác).
                .ignoringRequestMatchers(
                    "/api/v1/auth/register", "/api/v1/auth/login",
                    "/api/v1/auth/refresh", "/api/v1/auth/logout"
                )
                .requireCsrfProtectionMatcher(CSRF_REQUIRED_MATCHER)
            )
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(e -> e
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(customAccessDeniedHandler)
            )
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) 
        throws Exception {
        return config.getAuthenticationManager();
    }
}