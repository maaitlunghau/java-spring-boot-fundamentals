package com.maaitlunghau.__spring_boot_blueprint.module.user.service.impl;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.maaitlunghau.__spring_boot_blueprint.common.dto.PageResponse;
import com.maaitlunghau.__spring_boot_blueprint.exception.BadRequestException;
import com.maaitlunghau.__spring_boot_blueprint.exception.DuplicateResourceException;
import com.maaitlunghau.__spring_boot_blueprint.exception.ResourceNotFoundException;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.request.CreateUserRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.request.UpdateProfileRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.request.UpdateUserRoleRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.user.dto.response.UserResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.Role;
import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.User;
import com.maaitlunghau.__spring_boot_blueprint.module.user.repository.UserRepository;
import com.maaitlunghau.__spring_boot_blueprint.module.user.repository.spec.UserSpecifications;
import com.maaitlunghau.__spring_boot_blueprint.module.user.service.UserService;

@Service
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public PageResponse<UserResponse> search(String keyword, Role role, Pageable pageable) {
        Page<User> page = userRepository.findAll(
            UserSpecifications.keywordIn(keyword).and(UserSpecifications.hasRole(role)), pageable);
        return PageResponse.from(page.map(UserResponse::from));
    }

    @Override
    public UserResponse getById(Long id) {
        return UserResponse.from(findUserOrThrow(id));
    }

    @Override
    @Transactional
    public UserResponse create(CreateUserRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("Email already exists: " + request.email());
        }

        User user = new User(
            request.fullName(), 
            request.email(), 
            passwordEncoder.encode(request.password()), 
            request.role()
        );

        return UserResponse.from(userRepository.save(user));
    }

    @Override
    @Transactional
    public UserResponse updateProfile(Long id, UpdateProfileRequest request) {
        User user = findUserOrThrow(id);

        String fullName = request.fullName() != null ? request.fullName() : user.getFullName();
        String imageUrl = request.imageUrl() != null ? request.imageUrl() : user.getImageUrl();

        user.updateProfile(fullName, imageUrl);
        return UserResponse.from(user);
    }

    @Override
    @Transactional
    public UserResponse updateRole(Long id, UpdateUserRoleRequest request) {
        User user = findUserOrThrow(id);
        if (user.getRole() == Role.ADMIN && 
            request.role() != Role.ADMIN && 
            userRepository.countByRole(Role.ADMIN) <= 1) {
            throw new BadRequestException("Cannot demote the last ADMIN in the system");
        }
        user.changeRole(request.role());
        return UserResponse.from(user);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        User user = findUserOrThrow(id);
        if (user.getRole() == Role.ADMIN && userRepository.countByRole(Role.ADMIN) <= 1) {
            throw new BadRequestException("Cannot delete the last ADMIN in the system.");
        }

        userRepository.delete(user);
    }

    private User findUserOrThrow(Long id) {
        return userRepository.findById(id)
            .orElseThrow( () -> new ResourceNotFoundException("User", id));
    }
}
