package com.lookahead.domain.cloud;

import com.lookahead.domain.testaccounts.TestAccountRoster;

/**
 * DLV-969 S36: the provider identities (Cognito users) of an account. One account may hold several; a new one is
 * linked only by a provider-verified email that matches exactly one eligible account. The rows live in the DynamoDB
 * store ({@link DynamoCloudAccounts} IDENTITY items); this class keeps the rules both the registry and the import share.
 */
public final class CloudIdentities {
    private CloudIdentities(){}

    /** {@code test} on the DEV test issuer, {@code google} for Cognito's "Google_<id>" users, else {@code cognito}. */
    public static String provider(String issuer,String username){
        if(TestAccountRoster.TEST_ISSUER.equals(issuer))return "test";
        return username!=null&&username.regionMatches(true,0,"google_",0,7)?"google":"cognito";
    }
    /** The one comparison form of an email: lower case (ASCII addresses). */
    public static String normalizeEmail(String email){return email.toLowerCase(java.util.Locale.ROOT);}
}
