package com.nightvenue.api.auth;

public record UserAccount(long id, String email, String fullName, String role, boolean active, String passwordHash) {
}
