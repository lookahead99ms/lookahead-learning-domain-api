package com.lookahead.domain.db.store.signin;

import com.lookahead.domain.model.SignInIdentity;
import com.lookahead.domain.model.SignInModels.Account;
import com.lookahead.domain.model.SignInModels.Challenge;
import com.lookahead.domain.model.SignInModels.EmailAccount;
import com.lookahead.domain.model.SignInModels.FamilyBinding;
import com.lookahead.domain.model.SignInModels.Session;
import com.lookahead.domain.model.SignInModels.StoredProfile;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * The stored side of SignInService. DLV-1016: the service's work runs inside a unit of this store. A unit looks up the
 * account behind the identity and hands the work its reads and writes ({@link Work}); every write is one transaction
 * conditioned on the account's control item as the unit read it. When a write loses that race the whole work runs
 * again from a fresh read, three attempts in all, and the last conflict is passed on. The store makes no sign-in
 * decision: what is refused, limited, admitted or logged is decided by the work.
 */
public interface SignIn {
    /** One unit on the account the identity is bound to. */
    <T> T unit(SignInIdentity identity, Function<Work, T> work);
    /**
     * DLV-969: the sign-in admission's unit, which may also record or link the account behind an identity it has not
     * seen before. Several transactions; each is committed when its step returns, and a lost race runs the attempt
     * again from the start (the identity is then looked up as it stands after those commits).
     */
    <T> T firstContact(SignInIdentity identity, Function<FirstContact, T> attempt);

    /**
     * The reads and writes of one attempt. Reads are strongly consistent; each write is one transaction, committed when it returns.
     * DLV-1016: it declares nothing of its own; a helper that needs one job takes that job's interface.
     */
    interface Work extends AttemptAccount, AccountSessions, AccountChallenges, AccountProfile, CredentialChange {}

    /** The attempt and its account: whether the identity is bound, the account as read, the attempt's clock, and the accounts that carry an email. */
    interface AttemptAccount {
        /** Whether the identity was bound to an account when this attempt started. */
        boolean known();
        /** The account as last read in this attempt; null when the identity is bound to an account that is gone. */
        Account account();
        Instant now();
        /** The accounts that carry the email now: not on the test issuer, not deleted. */
        List<EmailAccount> emailAccounts(String email);
    }

    /** The account's stored sign-ins: reading them, recording, re-issuing, ending and labelling one. */
    interface AccountSessions {
        FamilyBinding familyBinding(String family);
        /** Every stored sign-in of the account, ended ones included. */
        List<Session> sessions();
        Session session(UUID id);
        /** Records that the sign-in was used now; conditioned on the sign-in alone (not ended, same generation), and a lost condition is ignored. */
        void touch(UUID session, Instant now);
        UUID addSession(String family, String binding, Instant authenticatedAt, Instant now);
        /** Ends {@code old} and records a new sign-in in its place. */
        UUID reissueSession(UUID old, String family, String binding, Instant authenticatedAt, Instant now);
        void endSessions(List<UUID> sessions, Instant now);
        /** Advances the account's sign-in generation, which ends every sign-in, and re-issues {@code current} under the new one. */
        void endOtherSessions(UUID current);
        void labelSession(UUID session, String label);
    }

    /** The challenges to replace a sign-in when both slots are taken: reading, issuing, answering and cancelling one. */
    interface AccountChallenges {
        List<Challenge> challenges();
        Challenge challenge(String digest);
        /** Deletes the challenges {@code stale} and records a new one. */
        void issueChallenge(List<String> stale, String digest, String family, String binding, Instant authenticatedAt, Instant now, Instant expires);
        /** Records the challenge's sign-in, ends {@code selected} when {@code endSelected}, and marks the challenge with both. */
        UUID replaceSession(UUID selected, boolean endSelected, Challenge challenge, Instant now);
        void cancelChallenge(String digest);
    }

    /** The account's profile: its stored preferences, links and sign-in methods, and the three things the person may save. */
    interface AccountProfile {
        /** Read in this order: preferences, links, identities. */
        StoredProfile profile();
        void saveDisplayName(String name);
        void saveTheme(String theme);
        /** {@code values}: every link kind with its checked address, or null for none. */
        void saveLinks(Map<String, String> values);
    }

    /** The fail-closed mark around a password change at the sign-in provider. */
    interface CredentialChange {
        /** The fail-closed mark before the sign-in provider is asked to change the password. */
        void markCredentialChange();
        /** Clears the mark; with {@code endSignIns} the sign-in generation advances and the challenges {@code open} are cancelled. */
        void settleCredentialChange(boolean endSignIns, List<String> open);
    }

    /**
     * The further steps of the admission's unit for an identity that has no admitted account yet.
     * DLV-1016: it declares nothing of its own; its steps are the three jobs below.
     */
    interface FirstContact extends Work, IdentityBinding, OwnerAdmission, ClaimRefresh {}

    /** An identity seen for the first time gets its account: linked to the one that carries its email, or recorded as new, and then read. */
    interface IdentityBinding {
        /** The revision of the email's sentinel; {@link #link} and {@link #create} are conditioned on it and advance it. */
        long emailRevision(String email);
        /** Binds the identity to {@code candidate}, one of the accounts {@link AttemptAccount#emailAccounts} last answered, conditioned on that read. */
        void link(UUID candidate, String email, long emailRevision, Instant now);
        /** Records a new account for the identity, waiting unless {@code admitted}; its id. */
        UUID create(String email, long emailRevision, boolean admitted, boolean author, Instant now);
        /** Admits the account {@link #create} recorded, with the roles of the prepared access claimed for it. */
        void admitCreated(List<String> roles, Instant now);
        /** Reads the account {@code id} and makes it this attempt's account; null when it is gone. */
        Account follow(UUID id);
        /** Reads the identity's binding again (after {@link #create}). */
        void rebind();
    }

    /** The protected owner identity: whether this is it, admitting its waiting account and recording its author access. */
    interface OwnerAdmission {
        /** Whether the identity is the protected owner identity. */
        boolean ownerIdentity();
        /** Reads this attempt's account again. */
        Account reload();
        /** Admits the waiting account as the owner's own, author access included. */
        void admitWaitingOwner(Instant now);
        /** Records author access on the account and reads it again. */
        void makeAuthor();
    }

    /** The provider's claims written onto the identity and its account. */
    interface ClaimRefresh {
        /** Writes the identity's current claims (username, and {@code email} as its verified email), and the account's profile when the identity is its first. */
        void refreshClaims(String email);
        /** Writes {@code email} as the identity's verified email where it changed (and the account's, for its first identity); reads the account again when anything was written. */
        void refreshEmail(String email);
    }
}
