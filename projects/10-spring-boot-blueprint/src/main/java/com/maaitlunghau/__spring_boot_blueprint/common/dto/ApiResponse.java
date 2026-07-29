package com.maaitlunghau.__spring_boot_blueprint.common.dto;

import java.time.LocalDateTime;

public record ApiResponse<T>(int status, String message, T data, LocalDateTime timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "Success", data, LocalDateTime.now());
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(200, message, data, LocalDateTime.now());
    }

    public static <T> ApiResponse<T> of(int status, String message, T data) {
        return new ApiResponse<>(status, message, data, LocalDateTime.now());
    }

    public static ApiResponse<Void> message(int status, String message) {
        return new ApiResponse<>(status, message, null, LocalDateTime.now());
    }
}
