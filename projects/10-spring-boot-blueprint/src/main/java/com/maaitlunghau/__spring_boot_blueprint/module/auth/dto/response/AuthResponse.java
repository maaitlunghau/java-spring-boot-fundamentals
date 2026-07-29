package com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.response;

public record AuthResponse(
    String accessToken,
    // String refreshToken,
    long expiresIn
) {}
