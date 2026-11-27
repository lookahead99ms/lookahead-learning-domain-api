package com.lookahead.domain.security.token;

import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.service.testaccounts.TestAccountModeService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Verifies the operator's Basic credentials on the internal test-account routes: Local only, the fixed operator name,
 * and the operator secret compared in constant time. A verified caller holds the test-account operator role.
 */
public final class TestAccountOperatorVerifier implements AuthenticationProvider {
    private final TestAccountModeService policy;
    private final byte[] expected;
    /** The validated operator secret, compared as its UTF-8 bytes. */
    public TestAccountOperatorVerifier(TestAccountModeService policy, String secret) { this(policy, secret.getBytes(StandardCharsets.UTF_8)); }
    public TestAccountOperatorVerifier(TestAccountModeService policy, byte[] expected) { this.policy = policy; this.expected = expected; }
    @Override public Authentication authenticate(Authentication request) {
        try { policy.requireLocal(); }
        catch (AccountFailure disabled) {
            throw new BadCredentialsException("Test account maintenance is disabled");
        }
        boolean matches = MessageDigest.isEqual(expected,
                String.valueOf(request.getCredentials()).getBytes(StandardCharsets.UTF_8));
        if (expected.length < 32 || !"lookahead-test-account-operator".equals(request.getName()) || !matches)
            throw new BadCredentialsException("Invalid operator credentials");
        return UsernamePasswordAuthenticationToken.authenticated(request.getName(), null,
                List.of(new SimpleGrantedAuthority("ROLE_TEST_ACCOUNT_OPERATOR")));
    }
    @Override public boolean supports(Class<?> type) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type);
    }
}
