package com.maaitlunghau.__spring_boot_blueprint.module.user.service;

import org.springframework.data.domain.Pageable;

import com.maaitlunghau.__spring_boot_blueprint.common.dto.PageResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.request.CreateUserRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.request.UpdateProfileRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.request.UpdateUserRoleRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.response.UserResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.Role;

public interface UserService {
    PageResponse<UserResponse> search(String keyword, Role role, Pageable pageable);
    UserResponse getById(Long id);
    UserResponse create(CreateUserRequest request);
    UserResponse updateProfile(Long id, UpdateProfileRequest request);
    UserResponse updateRole(Long id, UpdateUserRoleRequest request);
    void delete(Long id);
}
