package com.maaitlunghau.__spring_boot_blueprint.module.user.dto.response;

import java.time.LocalDateTime;

import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.Role;
import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.User;

public record UserResponse(
    Long id,
    String fullName,
    String email,
    String imageUrl,
    Role role,
    boolean enabled,
    LocalDateTime createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
            user.getId(), 
            user.getFullName(), 
            user.getEmail(), 
            user.getImageUrl(),
            user.getRole(), 
            user.isEnabled(), 
            user.getCreatedAt()
        );
    }
}
