package com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

public record RefreshTokenRequest(
    @NotBlank(message = "Refresh Token is required")
    String refreshToken
) {}
