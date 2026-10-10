package org.imnu.smartcommunitycare.user.model.request;
import jakarta.validation.constraints.*;
public record CreateAccountRequest(
        @NotBlank @Pattern(regexp="^[A-Za-z0-9_]{3,50}$") String username,
        @NotBlank @Size(min=6,max=64) String password,
        @NotBlank @Size(max=50) String realName,
        @Pattern(regexp="^$|^1[3-9]\\d{9}$") String phone,
        @Email @Size(max=100) String email,
        @NotBlank String role) {}
