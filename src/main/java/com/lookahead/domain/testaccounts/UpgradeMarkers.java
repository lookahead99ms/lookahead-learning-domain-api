package com.lookahead.domain.testaccounts;

import com.lookahead.domain.access.StaffAliases;
import com.lookahead.domain.membership.MembershipService;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * DLV-969 (owner, 2026-11-04): the "upgraded by" marker of a DEV test account. Once a purchase request is approved the
 * account carries it until its next Reset (or Restore): who sent the request (the gate holder its purchase-created audit
 * row records), who approved it, the plan and when. Nothing is stored for it: it is the latest approved request
 * ({@link MembershipService#approvedRequest}) recorded after the account's latest {@code test_account_resets} row, so a
 * Reset clears it. Only the owner sees names; everyone sees "self" and the permanent role labels.
 */
final class UpgradeMarkers {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private final JdbcTemplate jdbc;
    private final MembershipService memberships;
    private final StaffAliases aliases;

    UpgradeMarkers(JdbcTemplate jdbc, MembershipService memberships, StaffAliases aliases) {
        this.jdbc = jdbc; this.memberships = memberships; this.aliases = aliases;
    }

    /** The account's marker, or null: nothing approved since its latest Reset, or no DEV purchase requests. */
    MembershipService.ApprovedRequest find(UUID account) {
        return memberships == null ? null : memberships.approvedRequest(account);
    }

    /** The permanent role label of {@code person} ("Owner", "Admin 2"); "Unknown" when not known. */
    String label(UUID person) {
        return person == null || aliases == null ? "Unknown" : aliases.label(person);
    }

    /**
     * The test-account list's {@code upgradedBy}: {@code {self, label, name?, approvedBy:{self, label, name?},
     * plan:{code,name}, kind, approvedAt}}; {@code name} (display name) for the owner only.
     */
    ObjectNode row(MembershipService.ApprovedRequest marker, UUID viewer, boolean named) {
        if (marker == null) return null;
        var node = person(marker.requestedBy(), viewer, named);
        node.set("approvedBy", person(marker.approvedBy(), viewer, named));
        node.putObject("plan").put("code", marker.planCode()).put("name", marker.planName());
        node.put("kind", marker.kind()).put("approvedAt", marker.approvedAt().toString());
        return node;
    }

    /** The test-session picker's compact {@code upgradedBy}: {@code {self, label, name?, plan: "<plan name>", approvedAt}}. */
    ObjectNode compact(MembershipService.ApprovedRequest marker, UUID viewer, boolean named) {
        if (marker == null) return null;
        return person(marker.requestedBy(), viewer, named).put("plan", marker.planName()).put("approvedAt", marker.approvedAt().toString());
    }

    private ObjectNode person(UUID person, UUID viewer, boolean named) {
        var node = NODES.objectNode().put("self", person != null && person.equals(viewer)).put("label", label(person));
        if (named && person != null)
            node.put("name", jdbc.queryForList("SELECT display_name FROM cloud_accounts WHERE account_id=?", String.class, person).stream().findFirst().orElse(null));
        return node;
    }
}
