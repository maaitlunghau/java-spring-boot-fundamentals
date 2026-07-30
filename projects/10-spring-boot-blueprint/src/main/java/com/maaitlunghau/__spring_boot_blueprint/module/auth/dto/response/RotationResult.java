package com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.response;

public record RotationResult(
    String newRawRefreshToken, 
    Long userId, 
    String sessionId
) {}