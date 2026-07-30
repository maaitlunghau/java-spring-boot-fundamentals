package com.maaitlunghau.__spring_boot_blueprint.module.auth.entity;

import java.time.LocalDateTime;

import com.maaitlunghau.__spring_boot_blueprint.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;

@Entity
@Getter
@Table(name = "refresh_tokens", indexes = {
    @Index(name = "idx_refresh_tokens_token_hash", columnList = "token_hash", unique = true),
    @Index(name = "idx_refresh_tokens_user_session", columnList = "user_id, session_id"),
    @Index(name = "idx_refresh_tokens_revoked_revoked_at", columnList = "revoked, revoked_at")
})
public class RefreshToken extends BaseEntity {

    @Column(nullable = false, name = "user_id")
    private Long userId;

    @Column(nullable = false, name = "session_id", length = 36)
    private String sessionId;

    @Column(nullable = false, name = "token_hash", unique = true, length = 64)
    private String tokenHash;

    @Column(name = "device_info")
    private String deviceInfo;

    @Column(length = 45)
    private String ip;

    @Column(nullable = false, name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(nullable = false, name = "absolute_expires_at")
    private LocalDateTime absoluteExpiresAt;

    @Column(nullable = false)
    private boolean revoked = false;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoked_reason", length = 30)
    @Enumerated(EnumType.STRING)
    private RevokeReason revokedReason;

    protected RefreshToken() {
    }

    public RefreshToken(
        Long userId, 
        String sessionId, 
        String tokenHash, 
        String deviceInfo, 
        String ip,
        LocalDateTime expiresAt, 
        LocalDateTime absoluteExpiresAt
    ) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.tokenHash = tokenHash;
        this.deviceInfo = deviceInfo;
        this.ip = ip;
        this.expiresAt = expiresAt;
        this.absoluteExpiresAt = absoluteExpiresAt;
    }

    public void revoke(RevokeReason reason) {
        this.revoked = true;
        this.revokedAt = LocalDateTime.now();
        this.revokedReason = reason;
    }

    public boolean isExpired() {
        LocalDateTime now = LocalDateTime.now();
        return expiresAt.isBefore(now) || absoluteExpiresAt.isBefore(now);
    }
}
