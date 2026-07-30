package com.maaitlunghau.__spring_boot_blueprint.module.auth.service;

import java.util.List;

import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.response.RotationResult;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.entity.RefreshToken;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.entity.RevokeReason;

public interface RefreshTokenService {

    public String issue(Long userId, String sessionId, String deviceInfo, String ip);

    public RotationResult rotate(String rawOldToken, String deviceInfo, String ip);

    public void revokeSession(Long userId, String sessionId, RevokeReason reason);

    public void revokeAllSessions(Long userId, RevokeReason reason);

    public List<RefreshToken> listActiveSessions(Long userId);
}   
