package com.maaitlunghau.__spring_boot_blueprint.module.auth.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.maaitlunghau.__spring_boot_blueprint.exception.InvalidRefreshTokenException;
import com.maaitlunghau.__spring_boot_blueprint.exception.RefreshTokenReuseException;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.response.RotationResult;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.entity.RefreshToken;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.entity.RevokeReason;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.repository.RefreshTokenRepository;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.service.RefreshTokenService;

@Service
@Transactional(readOnly = true)
public class RefreshTokenServiceImpl implements RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final long refreshTokenExpirationMs;
    private final long refreshTokenAbsoluteExpirationMs;

    public RefreshTokenServiceImpl(
        RefreshTokenRepository refreshTokenRepository,
        @Value("${app.jwt.refresh-token-expiration}") long refreshTokenExpirationMs,
        @Value("${app.jwt.refresh-token-absolute-expiration}") long refreshTokenAbsoluteExpirationMs
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenExpirationMs = refreshTokenExpirationMs;
        this.refreshTokenAbsoluteExpirationMs = refreshTokenAbsoluteExpirationMs;
    }

    /** 
     * Phát refresh token MỚI cho 1 session mới (login). 
     * Trả raw token cho client. 
     */
    @Override
    @Transactional
    public String issue(Long userId, String sessionId, String deviceInfo, String ip) {
        String rawToken = generateOpaqueToken();

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plus(Duration.ofMillis(refreshTokenExpirationMs));
        LocalDateTime absLocalExpiresAt = now.plus(Duration.ofMillis(refreshTokenAbsoluteExpirationMs));

        refreshTokenRepository.save(
            new RefreshToken(
                userId, 
                sessionId, 
                hash(rawToken), 
                deviceInfo, 
                ip, 
                expiresAt,
                absLocalExpiresAt
            )
        );

        return rawToken;
    }

    /**
     * Rotate: verify raw token, phát token mới cùng session, đánh dấu token cũ revoked.
     * Ném RefreshTokenReuseException nếu token đã revoked trước đó bị dùng lại (theft).
     */
    @Override
    @Transactional
    public RotationResult rotate(String rawOldToken, String deviceInfo, String ip) {
        String oldHash = hash(rawOldToken);
        RefreshToken token = refreshTokenRepository.findByTokenHashForUpdate(oldHash)
            .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token is invalid or expired!"));

        if (token.isExpired()) {
            throw new InvalidRefreshTokenException("Refresh is expired!");
        }
        if (token.isRevoked()) {
            revokeAllSessions(token.getUserId(), RevokeReason.REUSE_DETECTED);
            throw new RefreshTokenReuseException("Refresh token reuse detected. All devices have been signed out.");
        }

        token.revoke(RevokeReason.ROTATED);

        String newRawToken = generateOpaqueToken();
        LocalDateTime expiresAt = LocalDateTime.now().plus(Duration.ofMillis(refreshTokenExpirationMs));

        refreshTokenRepository.save(
            new RefreshToken(
                token.getUserId(), 
                token.getSessionId(), 
                hash(newRawToken), 
                deviceInfo, 
                ip,
                expiresAt, 
                token.getAbsoluteExpiresAt()
            )
        );

        return new RotationResult(newRawToken, token.getUserId(), token.getSessionId());
    }

    @Override
    @Transactional
    public void revokeSession(Long userId, String sessionId, RevokeReason reason) {
        refreshTokenRepository.findByUserIdAndSessionIdAndRevokedFalse(userId, sessionId)
            .ifPresent(t -> t.revoke(reason));
    }

    /** 
    * Thu hồi TOÀN BỘ session
    * Hiện chỉ gọi khi phát hiện reuse 
    */
    @Override
    @Transactional
    public void revokeAllSessions(Long userId, RevokeReason reason) {
        refreshTokenRepository.findByUserIdAndRevokedFalseOrderByCreatedAtDesc(userId)
            .forEach(t -> t.revoke(reason));
    }

    /** 
    * Danh sách session đang active
    * Dùng cho `GET /api/v1/auth/sessions`. 
    */
    @Override
    public List<RefreshToken> listActiveSessions(Long userId) {
        return refreshTokenRepository.findByUserIdAndRevokedFalseOrderByCreatedAtDesc(userId);
    }

    @Override
    public long getRefreshTokenExpirationSeconds() {
        return refreshTokenExpirationMs / 1000;
    }

    private String generateOpaqueToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
