package com.maaitlunghau.__fullstack_user_management.service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.maaitlunghau.__fullstack_user_management.dto.request.LoginRequest;
import com.maaitlunghau.__fullstack_user_management.dto.request.RegisterRequest;
import com.maaitlunghau.__fullstack_user_management.dto.request.ResetPasswordRequest;
import com.maaitlunghau.__fullstack_user_management.dto.response.AuthResponse;
import com.maaitlunghau.__fullstack_user_management.entity.RefreshToken;
import com.maaitlunghau.__fullstack_user_management.entity.RevokedReason;
import com.maaitlunghau.__fullstack_user_management.entity.User;
import com.maaitlunghau.__fullstack_user_management.entity.VerificationToken;
import com.maaitlunghau.__fullstack_user_management.exception.BadRequestException;
import com.maaitlunghau.__fullstack_user_management.exception.DuplicateResourceException;
import com.maaitlunghau.__fullstack_user_management.exception.ResourceNotFoundException;
import com.maaitlunghau.__fullstack_user_management.repository.RefreshTokenRepository;
import com.maaitlunghau.__fullstack_user_management.repository.UserRepository;
import com.maaitlunghau.__fullstack_user_management.repository.VerificationTokenRepository;

/**
 * Toàn bộ logic xác thực: đăng ký → verify email → login → refresh (rotation) →
 * logout → quên/đặt lại mật khẩu.
 *
 * Nguyên tắc bảo mật xuyên suốt:
 *  - Token opaque (refresh/verify) chỉ lưu SHA-256 → hash trước khi lưu & tra cứu.
 *  - Refresh token xoay vòng (rotation) mỗi lần dùng; token cũ bị revoked.
 *  - Reuse detection: token đã revoked mà bị dùng lại = bị đánh cắp → thu hồi cả phiên.
 *  - Verification token dùng một lần (markUsed), giữ lại để audit.
 */
@Service
@Transactional
public class AuthService {

    private static final Duration EMAIL_VERIFY_TTL = Duration.ofHours(24);
    private static final Duration PASSWORD_RESET_TTL = Duration.ofHours(1);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final VerificationTokenRepository verificationTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final TokenBlacklist tokenBlacklist;
    private final EmailService emailService;

    private final long accessExpirationMs;
    private final long refreshExpirationMs;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       VerificationTokenRepository verificationTokenRepository,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtService jwtService,
                       TokenBlacklist tokenBlacklist,
                       EmailService emailService,
                       @Value("${app.jwt.access-token-expiration}") long accessExpirationMs,
                       @Value("${app.jwt.refresh-token-expiration}") long refreshExpirationMs) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.verificationTokenRepository = verificationTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.tokenBlacklist = tokenBlacklist;
        this.emailService = emailService;
        this.accessExpirationMs = accessExpirationMs;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    // ===================== REGISTER =====================

    public void register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("Email đã tồn tại: " + request.email());
        }
        User user = new User(request.email(),
            passwordEncoder.encode(request.password()), request.fullName());
        // role mặc định USER, isEmailVerified = false đã set sẵn trong entity
        userRepository.save(user);

        sendEmailVerification(user);
    }

    /** Phát verification token mới (opaque) và gửi email kích hoạt. */
    private void sendEmailVerification(User user) {
        String rawToken = jwtService.generateOpaqueToken();
        VerificationToken token = new VerificationToken(
            jwtService.hashToken(rawToken), user,
            VerificationToken.Type.EMAIL_VERIFY, Instant.now().plus(EMAIL_VERIFY_TTL));
        verificationTokenRepository.save(token);
        emailService.sendVerificationEmail(user.getEmail(), rawToken);
    }

    // ===================== VERIFY EMAIL =====================

    public void verifyEmail(String rawToken) {
        VerificationToken token = findUsableToken(rawToken, VerificationToken.Type.EMAIL_VERIFY);
        token.getUser().markEmailVerified();
        token.markUsed();
    }

    // ===================== LOGIN =====================

    public AuthResponse login(LoginRequest request, String ipAddress, String userAgent) {
        // Ném BadCredentialsException nếu sai mật khẩu, DisabledException nếu bị vô hiệu hoá
        authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.email(), request.password()));

        User user = userRepository.findByEmail(request.email())
            .orElseThrow(() -> new ResourceNotFoundException("User", request.email()));

        // if (!user.isEmailVerified()) {
        //     throw new BadRequestException("Email chưa được xác thực. Kiểm tra hộp thư để kích hoạt.");
        // }

        return issueNewSession(user, ipAddress, userAgent);
    }

    /**
     * Mở một PHIÊN mới (sessionId mới) và phát access + refresh token.
     * Dùng chung cho login thường và social login (Phase 3).
     */
    public AuthResponse issueNewSession(User user, String ipAddress, String userAgent) {
        return issueTokens(user, UUID.randomUUID().toString(), ipAddress, userAgent);
    }

    // ===================== REFRESH (rotation + reuse detection) =====================

    // noRollbackFor: khi phát hiện reuse ta thu hồi cả phiên RỒI ném lỗi báo client.
    // Nếu để mặc định, exception sẽ rollback luôn việc thu hồi vừa làm → phải giữ commit.
    @Transactional(noRollbackFor = BadRequestException.class)
    public AuthResponse refresh(String rawRefreshToken, String ipAddress, String userAgent) {
        RefreshToken stored = refreshTokenRepository
            .findByTokenHash(jwtService.hashToken(rawRefreshToken))
            .orElseThrow(() -> new BadRequestException("Refresh token không hợp lệ"));

        // Reuse detection: token đã bị thu hồi mà vẫn được dùng lại → nghi bị đánh cắp
        if (stored.isRevoked()) {
            revokeSession(stored.getSessionId(), RevokedReason.REUSE_DETECTED);
            throw new BadRequestException(
                "Refresh token đã bị thu hồi. Nghi ngờ bị đánh cắp — đã đăng xuất toàn bộ phiên này.");
        }
        if (stored.isExpired()) {
            throw new BadRequestException("Refresh token đã hết hạn, vui lòng đăng nhập lại.");
        }

        // Rotation: phát token mới cùng sessionId, thu hồi token cũ (đánh dấu ROTATED)
        User user = stored.getUser();
        String newRawRefresh = jwtService.generateOpaqueToken();
        String newHash = jwtService.hashToken(newRawRefresh);
        stored.rotatedTo(newHash);

        RefreshToken rotated = new RefreshToken(newHash, user, stored.getSessionId(),
            Instant.now().plusMillis(refreshExpirationMs), ipAddress, userAgent);
        refreshTokenRepository.save(rotated);

        String access = jwtService.generateAccessToken(user);
        return AuthResponse.of(access, newRawRefresh, accessExpirationMs / 1000);
    }

    // ===================== LOGOUT =====================

    public void logout(String accessToken, String rawRefreshToken) {
        // Tầng 1: chặn access token hiện tại tới khi nó hết hạn tự nhiên
        String jti = jwtService.extractJti(accessToken);
        tokenBlacklist.blacklist(jti, jwtService.remainingSeconds(accessToken));

        // Tầng 2: thu hồi đúng phiên chứa refresh token này (đăng xuất thiết bị hiện tại)
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            refreshTokenRepository.findByTokenHash(jwtService.hashToken(rawRefreshToken))
                .ifPresent(rt -> revokeSession(rt.getSessionId(), RevokedReason.LOGOUT));
        }
    }

    // ===================== FORGOT PASSWORD =====================

    public void forgotPassword(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            // Vô hiệu các token reset cũ còn hiệu lực → chỉ link mới nhất dùng được
            verificationTokenRepository
                .findByUserAndTypeAndIsUsedFalse(user, VerificationToken.Type.PASSWORD_RESET)
                .forEach(VerificationToken::markUsed);

            String rawToken = jwtService.generateOpaqueToken();
            VerificationToken token = new VerificationToken(
                jwtService.hashToken(rawToken), user,
                VerificationToken.Type.PASSWORD_RESET, Instant.now().plus(PASSWORD_RESET_TTL));
            verificationTokenRepository.save(token);
            emailService.sendPasswordResetEmail(user.getEmail(), rawToken);
        });
        // Luôn trả về như nhau dù email có tồn tại hay không → không tiết lộ email nào có thật
    }

    // ===================== RESET PASSWORD =====================

    public void resetPassword(ResetPasswordRequest request) {
        VerificationToken token = findUsableToken(request.token(), VerificationToken.Type.PASSWORD_RESET);
        User user = token.getUser();
        user.changePassword(passwordEncoder.encode(request.newPassword()));
        token.markUsed();
        // Đổi mật khẩu → thu hồi mọi phiên, buộc đăng nhập lại ở tất cả thiết bị
        revokeAllSessions(user, RevokedReason.PASSWORD_CHANGED);
    }

    // ===================== helpers =====================

    /** Phát access + refresh token cho một phiên; lưu hash refresh + ip/userAgent. */
    private AuthResponse issueTokens(User user, String sessionId, String ipAddress, String userAgent) {
        String access = jwtService.generateAccessToken(user);
        String rawRefresh = jwtService.generateOpaqueToken();
        RefreshToken refreshToken = new RefreshToken(
            jwtService.hashToken(rawRefresh), user, sessionId,
            Instant.now().plusMillis(refreshExpirationMs), ipAddress, userAgent);
        refreshTokenRepository.save(refreshToken);
        return AuthResponse.of(access, rawRefresh, accessExpirationMs / 1000);
    }

    /** Tra verification token theo hash + kiểm tra đúng loại và còn dùng được. */
    private VerificationToken findUsableToken(String rawToken, VerificationToken.Type expectedType) {
        VerificationToken token = verificationTokenRepository
            .findByTokenHash(jwtService.hashToken(rawToken))
            .orElseThrow(() -> new BadRequestException("Token không hợp lệ"));
        if (token.getType() != expectedType || !token.isUsable()) {
            throw new BadRequestException("Token không hợp lệ hoặc đã hết hạn");
        }
        return token;
    }

    /** Thu hồi mọi token còn hiệu lực của một phiên (dirty checking tự flush). */
    private void revokeSession(String sessionId, RevokedReason reason) {
        refreshTokenRepository.findBySessionIdAndIsRevokedFalse(sessionId)
            .forEach(token -> token.revoke(reason));
    }

    /** Thu hồi mọi phiên của một user (mọi thiết bị). */
    private void revokeAllSessions(User user, RevokedReason reason) {
        refreshTokenRepository.findByUserAndIsRevokedFalse(user)
            .forEach(token -> token.revoke(reason));
    }
}
