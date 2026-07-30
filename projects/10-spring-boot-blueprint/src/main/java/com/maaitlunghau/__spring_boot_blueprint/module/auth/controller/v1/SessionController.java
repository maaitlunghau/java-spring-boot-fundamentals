package com.maaitlunghau.__spring_boot_blueprint.module.auth.controller.v1;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.maaitlunghau.__spring_boot_blueprint.common.dto.ApiResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.response.SessionResponse;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.entity.RevokeReason;
import com.maaitlunghau.__spring_boot_blueprint.module.auth.service.RefreshTokenService;
import com.maaitlunghau.__spring_boot_blueprint.module.user.entity.User;

@RestController
@RequestMapping("/api/v1/auth/sessions")
public class SessionController {

    private final RefreshTokenService refreshTokenService;

    public SessionController(RefreshTokenService refreshTokenService) {
        this.refreshTokenService = refreshTokenService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<SessionResponse>>> list(@AuthenticationPrincipal User user) {
        List<SessionResponse> sessions = refreshTokenService.listActiveSessions(user.getId())
            .stream()
            .map(SessionResponse::from)
            .toList();

        return ResponseEntity.ok(ApiResponse.ok(sessions));
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<ApiResponse<Void>> revoke(
        @AuthenticationPrincipal User user,
        @PathVariable String sessionId
    ) {
        refreshTokenService.revokeSession(user.getId(), sessionId, RevokeReason.LOGOUT);
        return ResponseEntity.ok(ApiResponse.message(200, "Revoked session!"));
    }
}
