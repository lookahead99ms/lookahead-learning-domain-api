package com.lookahead.domain.util;

import com.lookahead.domain.model.SignInIdentity;

/**
 * DLV-969 S36: the provider identities (Cognito users) of an account. One account may hold several; a new one is
 * linked only by a provider-verified email that matches exactly one eligible account. The identities are stored by
 * {@code db.store.signin.AccountAggregateStore} (IDENTITY items); this class names the provider of one, a rule the
 * sign-in store and the merge store share.
 */
public final class SignInIdentityRules {
    private SignInIdentityRules(){}

    /** {@code test} on the DEV test issuer, {@code google} for Cognito's "Google_<id>" users, else {@code cognito}. */
    public static String provider(String issuer,String username){
        if(SignInIdentity.TEST_ISSUER.equals(issuer))return "test";
        return username!=null&&username.regionMatches(true,0,"google_",0,7)?"google":"cognito";
    }
}
