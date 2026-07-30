package com.maaitlunghau.__spring_boot_blueprint.module.auth.service.impl;

import java.util.UUID;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.maaitlunghau.__spring_boot_blueprint.exception.DuplicateResourceException;
import com.maaitlunghau.__spring_boot_blueprint.exception.ResourceNotFoundException;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.request.LoginRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.request.RegisterRequest;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.response.AuthResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.response.RotationResult;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.entity.RevokeReason;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.service.AuthService;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.service.RefreshTokenService;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.service.TokenBlacklistService;
import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.Role;
import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.User;
import com.maaitlunghau.__spring_boot_blueprint.module.user.repository.UserRepository;
import com.maaitlunghau.__spring_boot_blueprint.security.JwtService;

@Service
@Transactional(readOnly = true)
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final TokenBlacklistService tokenBlacklistService;

    public AuthServiceImpl(
        UserRepository userRepository, 
        PasswordEncoder passwordEncoder,
        AuthenticationManager authenticationManager, 
        JwtService jwtService,
        RefreshTokenService refreshTokenService,
        TokenBlacklistService tokenBlacklistService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @Transactional
    public void register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("Email already exists: " + request.email());
        }
        User user = new User(
            request.fullName(),
            request.email(),
            passwordEncoder.encode(request.password()),
            Role.USER
        );
        userRepository.save(user);
    }

    public AuthResponse login(LoginRequest request, String deviceInfo, String ip) {
        authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.email(), request.password())
        );

        User user = userRepository.findByEmail(request.email())
            .orElseThrow(() -> new ResourceNotFoundException("User", request.email()));

        String sessionId = UUID.randomUUID().toString();
        String accessToken = jwtService.generateAccessToken(user, sessionId);
        String refreshToken = refreshTokenService.issue(user.getId(), sessionId, deviceInfo, ip);

        return new AuthResponse(
            accessToken, 
            refreshToken,
            jwtService.getAccessTokenExpirationSeconds()
        );
    }

    @Override
    public AuthResponse refreshToken(String rawRefreshToken, String deviceInfo, String ip) {
        RotationResult rotation = refreshTokenService.rotate(rawRefreshToken, deviceInfo, ip);

        User user = userRepository.findById(rotation.userId())
            .orElseThrow(() -> new ResourceNotFoundException("User", rotation.userId()));

        String accessToken = jwtService.generateAccessToken(user, rotation.sessionId());

        return new AuthResponse(
            accessToken, 
            rotation.newRawRefreshToken(), 
            jwtService.getAccessTokenExpirationSeconds()
        );
    }

    @Override
    public void logout(String accessToken) {
        String jti = jwtService.extractJti(accessToken);
        String sessionId = jwtService.extractSessionId(accessToken);
        String username = jwtService.extractUsername(accessToken);
        long remaining = jwtService.remainingSeconds(accessToken);

        tokenBlacklistService.blacklist(jti, remaining);

        User user = userRepository.findByEmail(username)
            .orElseThrow(() -> new ResourceNotFoundException("User", username));

        refreshTokenService.revokeSession(user.getId(), sessionId, RevokeReason.LOGOUT);
    }
}