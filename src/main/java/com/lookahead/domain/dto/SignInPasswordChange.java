package com.lookahead.domain.dto;

/** The checked body of a password change on the sign-in routes: the current password and the new one (confirmed). */
public record SignInPasswordChange(String currentPassword, String newPassword) {
    @Override public String toString() { return "SignInPasswordChange[redacted]"; }
}
