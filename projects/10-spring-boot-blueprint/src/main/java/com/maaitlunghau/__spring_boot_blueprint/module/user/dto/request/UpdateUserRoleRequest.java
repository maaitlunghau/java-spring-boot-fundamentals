package com.maaitlunghau.__spring_boot_blueprint.module.user.dto.request;

import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.Role;

import jakarta.validation.constraints.NotNull;

public record UpdateUserRoleRequest(
    @NotNull(message = "Role is required.")
    Role role
) {}
