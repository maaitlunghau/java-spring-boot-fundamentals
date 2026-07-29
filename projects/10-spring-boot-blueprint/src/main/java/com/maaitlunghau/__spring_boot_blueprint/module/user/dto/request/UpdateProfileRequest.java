package com.maaitlunghau.__spring_boot_blueprint.module.user.dto.request;

public record UpdateProfileRequest(
    String fullName,
    String imageUrl
) {}
