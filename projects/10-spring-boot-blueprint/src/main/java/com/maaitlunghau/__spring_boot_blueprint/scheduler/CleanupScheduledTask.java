package com.maaitlunghau.__spring_boot_blueprint.scheduler;

import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.maaitlunghau.__spring_boot_blueprint.module.auth.repository.RefreshTokenRepository;

@Component
public class CleanupScheduledTask {

    private final RefreshTokenRepository refreshTokenRepository;
    private static final long REVOKED_RETENTION_DAYS = 30;

    public CleanupScheduledTask(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void purgeExpiredRefreshTokens() {
        LocalDateTime now = LocalDateTime.now();
        refreshTokenRepository.purgeExpiredOrLongRevoked(now, now.minusDays(REVOKED_RETENTION_DAYS));
    }
}
