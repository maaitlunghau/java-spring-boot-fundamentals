package com.maaitlunghau.__spring_boot_blueprint.module.auth.service;

public interface TokenBlacklistService {
    public void blacklist(String jti, long remainingSeconds);
    public boolean isBlacklisted(String jti);
}   
