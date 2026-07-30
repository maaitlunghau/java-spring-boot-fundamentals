package com.maaitlunghau.__spring_boot_blueprint.config;

import java.io.IOException;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.maaitlunghau.__spring_boot_blueprint.module.auth.service.TokenBlacklistService;
import com.maaitlunghau.__spring_boot_blueprint.security.JwtService;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Chạy MỘT LẦN mỗi request: 
 * - đọc AT từ Header Authorization, 
 * - xác thực
 * - nạp danh tính vào SecurityContext
 * 
 * Không tự trả 401/403 - để EntryPoint/AccessDeniedHandler xử lí.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final TokenBlacklistService tokenBlacklistService;

    public JwtAuthenticationFilter(
        JwtService jwtService, 
        UserDetailsService userDetailsService,
        TokenBlacklistService tokenBlacklistService
    ) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.tokenBlacklistService = tokenBlacklistService;
    }
  
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String token = resolveToken(request);
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String username = jwtService.extractUsername(token);
            String jti = jwtService.extractJti(token);

            if (username != null && 
                SecurityContextHolder.getContext().getAuthentication() == null && 
                !tokenBlacklistService.isBlacklisted(jti)
            ) {
            // tại sao lại check SecurityContextHolder.getContext().getAuthentication() == null ?
            // - Vì trong 1 request, filter này có thể bị đi qua lại hoặc về sau có thể có thêm cơ chế xác thực khác chạy trước nó.
            // - Check này là một câu hỏi phòng thủ: "đã có ai gắn thẻ tên vào context chưa?"
            // - Nếu rồi thì đừng làm lại/đè lên. 
            // - Không có nó thì filter vẫn chạy đúng trong trường hợp đơn giản — nhưng có nó thì an toàn hơn khi hệ thống phức tạp lên.
            
            // @AuthenticationPrincipal:
            // - annotation dùng ở tham số method trong Controller
            // - nhằm để lấy "danh tính" mà filter JwtAuthenticationFilter này vừa gắn vào context 
            // - đỡ phải viết lại: SecurityContextHolder.getContext().getAuthentication().getPrincipal()
            // Ví dụ:
            // public UserResponse me(@AuthenticationPrincipal User user) { ... }

            // LƯU Ý: 
            // Quyết định request này có được đi tiếp hay ko là việc của
            // - SecurityConfig (authorizeHttpRequets)
            // - AuthenticationEntryPoint
            // chứ không phải của filter

            // filter: dù success hay failed vẫn luôn để request tiếp tục đi qua filter/servlet còn lại.
            // (nếu filterChain.doFilter quên gọi, request sẽ bị "treo" ở đây)

            // filter: chỉ để nạp danh tính vào context (SecurityContextHolder)

            // để filter JwtAuthenticationFilter này hoạt động, cần đăng ký ở SecurityConfig

                UserDetails userDetails = userDetailsService.loadUserByUsername(username);

                if (jwtService.isTokenValid(token, userDetails.getUsername())) { 
                    var auth = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities()
                    );

                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth); // QUAN TRỌNG NHẤT
                }
            }

        } catch (JwtException | IllegalArgumentException ex) {
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }

        return null;
    }
}
