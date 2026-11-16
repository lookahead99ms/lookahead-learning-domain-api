package com.lookahead.domain.access;

import java.util.UUID;

// DLV-970 cross-lane: provided by lane C. Ends the account's open memberships (the SQL superseded_at=now() of
// membership_purchases): history stays for the audit; safe to repeat.
public interface MembershipErasure {
    void endMemberships(UUID account);
}
