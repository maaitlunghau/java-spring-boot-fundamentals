package com.maaitlunghau.__spring_boot_blueprint.module.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank(message = "Full name is required.") 
    String fullName,
    
    @Email(message = "Email is invalid.") 
    @NotBlank(message = "Email is required.") 
    String email,

    @NotBlank(message = "Password is required.") 
    @Size(min = 6, message = "Password must be at least 6 characters long.") 
    String password
) {}
