package com.maaitlunghau.__spring_boot_blueprint.exception;

public class AppException extends RuntimeException {
    protected AppException(String message) {
        super(message);
    }
}
