package com.maaitlunghau.__spring_boot_blueprint.module.user.controller.v1;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.maaitlunghau.__spring_boot_blueprint.common.dto.ApiResponse;
import com.maaitlunghau.__spring_boot_blueprint.common.dto.PageResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.request.CreateUserRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.request.UpdateProfileRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.request.UpdateUserRoleRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.response.UserResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.Role;
import com.maaitlunghau.__spring_boot_blueprint.module.user.service.UserService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<UserResponse>>> search(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) Role role,
        Pageable pageable
    ) {
        return ResponseEntity.ok(ApiResponse.ok(userService.search(keyword, role, pageable)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(userService.getById(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<UserResponse>> create(@Valid @RequestBody CreateUserRequest request) {
        UserResponse created = userService.create(request);
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.of(201, "Created successfully", created));
    }

    @PatchMapping("/{id}/profile")
    public ResponseEntity<ApiResponse<UserResponse>> updateProfile(
        @PathVariable Long id,
        @Valid @RequestBody UpdateProfileRequest request
    ) {
        UserResponse updated = userService.updateProfile(id, request);
        return ResponseEntity.ok(ApiResponse.ok("Updated profile successfully", updated));
    }

    @PutMapping("/{id}/role")
    public ResponseEntity<ApiResponse<UserResponse>> updateRole(
        @PathVariable Long id,
        @Valid @RequestBody UpdateUserRoleRequest request
    ) {
        UserResponse updated = userService.updateRole(id, request);
        return ResponseEntity.ok(ApiResponse.ok("Updated role successfully", updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        userService.delete(id);
        return ResponseEntity.ok(ApiResponse.message(200, "Deleted successfully"));
    }
}
