package com.maaitlunghau.__spring_boot_blueprint.exception;

public class ResourceNotFoundException extends AppException {
    public ResourceNotFoundException(String resource, Object identifier) {
        super(resource + " not found: " + identifier);
    }
}
