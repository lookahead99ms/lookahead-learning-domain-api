package com.lookahead.domain.config;

import com.lookahead.domain.db.store.testaccounts.TestAccountIdentities;
import com.lookahead.domain.security.config.SignInBffSecret;
import com.lookahead.domain.security.config.SignInTokenTrust;
import com.lookahead.domain.service.access.StaffAliasService;
import com.lookahead.domain.service.membership.MembershipService;
import com.lookahead.domain.service.testaccounts.TestAccountModeService;
import com.lookahead.domain.service.testaccounts.TestAccountUpgradeMarkerService;
import com.lookahead.domain.service.testaccounts.TestSessionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The test-session beans that are not security wiring: the service and the upgrade markers of its picker. DEV only
 * ({@code app.deployment-environment=dev}), the same condition as the filter chain of the internal test-session routes
 * in {@code security.config.TestSessionSecurityConfiguration}, where these two bean methods were (DLV-1016).
 */
@Configuration
@ConditionalOnExpression("'${app.deployment-environment:}' == 'dev'")
public class TestSessionConfiguration {
    /** DLV-969 (owner, 2026-11-04): the picker shows who upgraded each account (none without DEV purchase requests). */
    @Bean TestAccountUpgradeMarkerService testSessionUpgradeMarkers(TestAccountIdentities store,
            ObjectProvider<MembershipService> memberships, ObjectProvider<StaffAliasService> aliases) {
        return new TestAccountUpgradeMarkerService(store, memberships.getIfAvailable(), aliases.getIfAvailable());
    }
    @Bean TestSessionService testSessionService(TestAccountIdentities store, TestAccountModeService policy, SignInTokenTrust trust, SignInBffSecret bffSecret,
            TestAccountUpgradeMarkerService testSessionUpgradeMarkers) {
        return new TestSessionService(store, policy, trust.issuer(), bffSecret.value(), testSessionUpgradeMarkers);
    }
}
