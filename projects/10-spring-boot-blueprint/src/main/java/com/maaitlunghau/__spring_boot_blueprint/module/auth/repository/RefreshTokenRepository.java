package com.maaitlunghau.__spring_boot_blueprint.module.auth.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.maaitlunghau.__spring_boot_blueprint.module.auth.entity.RefreshToken;

import jakarta.persistence.LockModeType;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * PESSIMISTIC_WRITE khoá row lại tới khi transaction kết thúc — đây là điểm khác
     * biệt cốt lõi so với bản Redis: chuỗi đọc-rồi-ghi ở đây atomic thật, không cần
     * grace window để né race condition nữa.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rt FROM RefreshToken rt WHERE rt.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    List<RefreshToken> findByUserIdAndRevokedFalseOrderByCreatedAtDesc(Long userId);
    
    Optional<RefreshToken> findByUserIdAndSessionIdAndRevokedFalse(Long userId, String sessionId);

    /**
     * 2 nhóm cần dọn, dùng chung 1 query để job cleanup chỉ cần gọi 1 lần:
     * (1) token chưa từng bị revoke nhưng đã qua idle TTL tự nhiên (session bị bỏ quên),
     * (2) token đã revoke — giữ lại {@code revokedRetentionCutoff} (vd 30 ngày) trước khi
     *     xoá, để còn thời gian audit các event `REUSE_DETECTED` trước khi mất dữ liệu.
     * `idx_refresh_tokens_revoked_revoked_at` phục vụ đúng nhánh (2) của query này.
     */
    @Modifying
    @Query(
        "DELETE FROM RefreshToken rt WHERE rt.expiresAt < :now "
        + "OR (rt.revoked = true AND rt.revokedAt < :revokedRetentionCutoff)"
    )
    void purgeExpiredOrLongRevoked(
        @Param("now") LocalDateTime now,
        @Param("revokedRetentionCutoff") LocalDateTime revokedRetentionCutoff
    );
}
