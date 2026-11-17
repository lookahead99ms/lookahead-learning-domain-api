package com.lookahead.domain.cloud;

import com.lookahead.learning.content.security.AccountPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DLV-969: the DEV/PROD sign-in registry (an abstract class rather than an interface so existing interface-free mocks keep working): first contact, admission of a sign-in into one of two slots, challenges,
 * revocation, profile, preferences and links. DLV-970: {@link DynamoCloudSignInRegistry} keeps the policy on DynamoDB
 * items with revision-fenced transactions (the PostgreSQL implementation, row locks per account, retired 2026-11-17).
 */
public abstract class CloudSignInRegistry {
    public record Entry(UUID signInId,boolean current,String label,String clientDescription,Instant createdAt,Instant lastActiveAt) {}
    public record Inventory(int limit,List<Entry> entries) {}
    /**
     * mergeAvailable (DLV-969 S36): this sign-in's verified email has two or more accounts that can take part (not test
     * accounts, not deleted) and none of them is disabled, so the page may offer the merge email (CloudMergeService).
     */
    public record Admission(UUID accountId,UUID signInId,String challengeToken,Instant expiresAt,boolean mergeAvailable) {
        public Admission(UUID accountId,UUID signInId,String challengeToken,Instant expiresAt){this(accountId,signInId,challengeToken,expiresAt,false);}
        @Override public String toString(){return "Admission[redacted]";}
    }
    public record ChallengeView(int limit,List<Entry> entries,Instant expiresAt) {}

    static final int CHALLENGES_PER_WINDOW=5;

    /** DLV-971: prepared access by email (StaffAccessService) admits a first contact at once; null leaves every first contact waiting. */
    public abstract void configureStaffAccess(com.lookahead.domain.access.StaffAccessService access);
    /** DLV-969: a sign-in without an account records a waiting account (first contact) and still answers 403. */
    public abstract Admission admit(CognitoIdentity identity,String proof);
    public abstract AccountPrincipal authenticate(CognitoIdentity identity,String proof);
    public abstract Inventory inventory(CognitoIdentity identity,String proof);
    public abstract ChallengeView challenge(CognitoIdentity identity,String proof,String token);
    public abstract Admission replace(CognitoIdentity identity,String proof,String token,UUID selected);
    public abstract void cancel(CognitoIdentity identity,String proof,String token);
    public abstract boolean revoke(CognitoIdentity identity,String proof,UUID target);
    public abstract void revokeOthers(CognitoIdentity identity,String proof);
    public abstract Entry rename(CognitoIdentity identity,String proof,String label);
    public abstract void terminate(CognitoIdentity identity,String proof);
    public abstract Map<String,Object> profile(CognitoIdentity identity,String proof,String displayName);
    public abstract Map<String,Object> preferences(CognitoIdentity identity,String proof,String theme);
    public abstract Map<String,Object> links(CognitoIdentity identity,String proof,Map<String,String> links);
    public abstract void changeCredentials(CognitoIdentity identity,String proof,Runnable providerChange);

    public static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
