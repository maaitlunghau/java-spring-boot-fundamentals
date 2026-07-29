package com.maaitlunghau.__spring_boot_blueprint.module.user.dto.request;

import jakarta.validation.constraints.NotBlank;

public record UpdateProfileRequest(
    @NotBlank(message = "Full name is required")
    String fullName,

    String imageUrl
) {}
