package com.lookahead.domain.cloud;

import com.lookahead.domain.testaccounts.TestAccountRoster;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DLV-969 S36: the provider identities (Cognito users) of an account, V19 {@code cloud_identities}. One account may
 * hold several; a new one is linked only by a provider-verified email that matches exactly one eligible account.
 */
public final class CloudIdentities {
    private CloudIdentities(){}
    /**
     * The account of one (issuer, subject): its identity row, or, for a row a release before V19 created without one,
     * the account whose first identity it is. Four parameters: issuer, subject, issuer, subject.
     */
    public static final String ACCOUNT_OF_IDENTITY="""
        SELECT i.account_id FROM cloud_identities i WHERE i.issuer=? AND i.subject=?
        UNION ALL SELECT f.account_id FROM cloud_accounts f WHERE f.issuer=? AND f.subject=?
          AND NOT EXISTS(SELECT 1 FROM cloud_identities x WHERE x.issuer=f.issuer AND x.subject=f.subject)""";

    /** {@code test} on the DEV test issuer, {@code google} for Cognito's "Google_<id>" users, else {@code cognito}. */
    public static String provider(String issuer,String username){
        if(TestAccountRoster.TEST_ISSUER.equals(issuer))return "test";
        return username!=null&&username.regionMatches(true,0,"google_",0,7)?"google":"cognito";
    }
    /** Records one identity; a repeat of the same (issuer, subject) is left as it is. */
    public static void insert(JdbcTemplate jdbc,UUID account,String issuer,String subject,String username,String verifiedEmail,String source){
        jdbc.update("INSERT INTO cloud_identities(id,account_id,issuer,subject,username,provider,verified_email,link_source) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT (issuer,subject) DO NOTHING",
            UUID.randomUUID(),account,issuer,subject,username,provider(issuer,username),verifiedEmail,source);
    }
    /**
     * Every Cognito username the account holds now, oldest first. {@code accountUsername} (the account's own) is added
     * first only while the account's own (issuer, subject) has no identity row anywhere (a row from before V19); once
     * that row exists, under this account or another one a merge moved it to, only the rows decide.
     */
    public static List<String> usernames(JdbcTemplate jdbc,UUID account,String accountUsername){
        var names=jdbc.queryForList("SELECT username FROM cloud_identities WHERE account_id=? AND provider<>'test' ORDER BY linked_at,id",String.class,account);
        var all=new java.util.ArrayList<String>(names==null?List.of():names);
        boolean ownMissing=Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT NOT EXISTS(SELECT 1 FROM cloud_accounts a JOIN cloud_identities i ON i.issuer=a.issuer AND i.subject=a.subject WHERE a.account_id=?)",Boolean.class,account));
        if(ownMissing&&accountUsername!=null&&!all.contains(accountUsername))all.addFirst(accountUsername);
        return List.copyOf(all);
    }
    /** The one advisory-lock and comparison form of an email: lower case (as SQL lower() on ASCII addresses). */
    public static String normalizeEmail(String email){return email.toLowerCase(java.util.Locale.ROOT);}
    /** The account's sign-in methods, oldest first: provider and, when verified, email. Test identities are left out. */
    public static List<java.util.Map<String,Object>> signInMethods(JdbcTemplate jdbc,UUID account){
        var methods=jdbc.query("SELECT provider,verified_email FROM cloud_identities WHERE account_id=? AND provider<>'test' ORDER BY linked_at,id",(r,i)->{
            var method=new java.util.LinkedHashMap<String,Object>();method.put("provider",r.getString(1));
            if(r.getString(2)!=null)method.put("email",r.getString(2));return java.util.Collections.unmodifiableMap(method);},account);
        return methods==null?List.of():List.copyOf(methods);
    }
    /**
     * DLV-969 S36: the merge can be offered to this account: it and at least one other account, neither a test account
     * nor deleted, carry {@code email}, and none of them is disabled (owner state or operator enabled=false).
     */
    public static boolean mergeable(JdbcTemplate jdbc,UUID account,String email){
        if(email==null||email.isBlank())return false;
        var rows=jdbc.queryForList("SELECT count(*) AS total,count(*) FILTER (WHERE NOT c.enabled OR EXISTS(SELECT 1 FROM cloud_account_states d WHERE d.account_id=c.account_id)) AS blocked,"
            +"coalesce(bool_or(c.account_id=?),false) AS own FROM cloud_accounts c WHERE c.account_id IN ("+sameEmail("?")
            +" AND NOT EXISTS(SELECT 1 FROM cloud_account_states s WHERE s.account_id=m.account_id AND s.state='removed'))",account,email,email);
        if(rows==null||rows.isEmpty())return false;
        var row=rows.getFirst();
        return Boolean.TRUE.equals(row.get("own"))&&((Number)row.get("total")).longValue()>=2&&((Number)row.get("blocked")).longValue()==0;
    }
    /**
     * Accounts that carry {@code email} (any identity, or the account's own verified email) and are not on the test
     * issuer. The email expression is used twice; the caller adds its own conditions on {@code m}.
     */
    static String sameEmail(String email){
        return "SELECT m.account_id FROM cloud_accounts m WHERE m.issuer<>'"+TestAccountRoster.TEST_ISSUER+"' AND m.account_id IN ("
            +"SELECT e.account_id FROM cloud_identities e WHERE e.verified_email IS NOT NULL AND lower(e.verified_email)=lower("+email+") "
            +"UNION SELECT c.account_id FROM cloud_accounts c WHERE c.verified_email IS NOT NULL AND lower(c.verified_email)=lower("+email+"))";
    }
    /** Link candidates for a new identity: same verified email, enabled, never disabled or deleted (V18), not a test account. */
    static String candidates(){
        return sameEmail("?")+" AND m.enabled AND NOT EXISTS(SELECT 1 FROM cloud_account_states s WHERE s.account_id=m.account_id)";
    }
    /**
     * Owner-only duplicate flag: how many OTHER accounts, not deleted and not test accounts, share the verified email
     * held in {@code email} (a column or ?) by the account in {@code account} (a column or ?). Placeholders bind as
     * email, email, account.
     */
    public static String sameEmailAccounts(String email,String account){
        return "(SELECT count(*) FROM ("+sameEmail(email)+" AND m.account_id<>"+account
            +" AND NOT EXISTS(SELECT 1 FROM cloud_account_states s WHERE s.account_id=m.account_id AND s.state='removed')) same)";
    }
    /** {@link #sameEmailAccounts(String,String)} for one account; 0 without an email. */
    public static int sameEmailAccounts(JdbcTemplate jdbc,UUID account,String email){
        if(email==null||email.isBlank())return 0;
        Integer count=jdbc.queryForObject("SELECT "+sameEmailAccounts("?","?"),Integer.class,email,email,account);
        return count==null?0:count;
    }
}
