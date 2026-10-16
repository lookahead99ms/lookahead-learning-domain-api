package com.lookahead.domain.access;

import com.lookahead.domain.security.IdentitySettings;
import com.lookahead.learning.content.exception.AccountFailure;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class AccountDirectory {
    public record Account(UUID accountId, String username, String displayName, String email) {}
    private final IdentitySettings identity;
    private final RestClient http;
    private final JdbcTemplate jdbc;
    public AccountDirectory(ObjectProvider<IdentitySettings> settings, JdbcTemplate jdbc) {
        this.identity=settings.getIfAvailable();this.jdbc=jdbc;
        if(identity==null){http=null;return;}
        var client=HttpClient.newBuilder().connectTimeout(identity.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory=new JdkClientHttpRequestFactory(client);factory.setReadTimeout(identity.readTimeout());
        http=RestClient.builder().requestFactory(factory).build();
    }
    public record Page(java.util.List<Account> accounts,int page,boolean hasNext) {}
    public Page list(String query,int page) {
        if(query==null||query.length()>254||query.codePoints().anyMatch(Character::isISOControl)||page<0||page>100000)
            throw new AccountFailure(422,"INVALID_ACCOUNT_DIRECTORY","Use a query up to 254 characters and a page from 0 to 100000.");
        String term=query.strip().toLowerCase(java.util.Locale.ROOT);
        if(identity==null) {
            var rows=jdbc.query("""
                SELECT account_id,username,display_name FROM cloud_accounts WHERE admitted AND enabled
                AND (?='' OR position(? in lower(username))>0 OR position(? in lower(display_name))>0)
                ORDER BY lower(username),account_id LIMIT 51 OFFSET ?
                """,(r,i)->new Account(r.getObject(1,UUID.class),r.getString(2),r.getString(3),null),term,term,term,page*50);
            return new Page(java.util.List.copyOf(rows.subList(0,Math.min(50,rows.size()))),page,rows.size()>50);
        }
        try {
            var uri=org.springframework.web.util.UriComponentsBuilder.fromUriString(identity.upstream()+"/internal/v1/accounts")
                .queryParam("query","{query}").queryParam("page",page).encode().buildAndExpand(term).toUri();
            return http.get().uri(uri).headers(h->h.setBasicAuth("lookahead-domain-verifier",identity.verifierSecret()))
                .exchange((sent,response)-> {
                    if(response.getStatusCode().value()!=200)throw unavailable();
                    byte[] bytes=response.getBody().readNBytes(262145);
                    if(bytes.length>262144)throw unavailable();
                    var result=new tools.jackson.databind.ObjectMapper().readValue(bytes,Page.class);
                    if(result==null||result.accounts()==null||result.accounts().size()>50||result.page()!=page
                       ||result.accounts().stream().anyMatch(a->a==null||a.accountId()==null||a.username()==null||a.displayName()==null))throw unavailable();
                    return result;
                });
        } catch(AccountFailure expected){throw expected;}catch(RuntimeException failure){throw unavailable();}
    }
    public Account lookup(String query) {
        if(query==null||query.isBlank()||query.length()>254||query.codePoints().anyMatch(Character::isISOControl))throw invalid();
        query=query.strip().toLowerCase(java.util.Locale.ROOT);
        if(identity==null) {
            // Cloud identities already admitted by the provider mapping; no email-based linking.
            var rows=jdbc.query("SELECT account_id,username,display_name FROM cloud_accounts WHERE admitted AND enabled AND lower(username)=? LIMIT 2",
                (r,i)->new Account(r.getObject(1,UUID.class),r.getString(2),r.getString(3),null),query);
            if(rows.size()!=1)throw missing();return rows.getFirst();
        }
        try {
            return http.post().uri(identity.upstream()+"/internal/v1/accounts/lookup")
                .headers(h->h.setBasicAuth("lookahead-domain-verifier",identity.verifierSecret()))
                .body(Map.of("query",query)).exchange((sent,response)-> {
                    if(response.getStatusCode().value()==404)throw missing();
                    if(response.getStatusCode().value()!=200)throw unavailable();
                    byte[] bytes=response.getBody().readNBytes(4097);
                    if(bytes.length>4096)throw unavailable();
                    var account=new tools.jackson.databind.ObjectMapper().readValue(bytes,Account.class);
                    if(account.accountId()==null||account.username()==null||account.displayName()==null)throw unavailable();
                    jdbc.update("INSERT INTO platform_subjects(id) VALUES(?) ON CONFLICT (id) DO NOTHING",account.accountId());
                    return account;
                });
        } catch(AccountFailure expected){throw expected;}catch(RuntimeException failure){throw unavailable();}
    }
    private static AccountFailure invalid(){return new AccountFailure(422,"INVALID_ACCOUNT_LOOKUP","Enter an exact email or username.");}
    private static AccountFailure missing(){return new AccountFailure(404,"ACCOUNT_NOT_FOUND","No eligible existing account matched. You can prepare a pending email assignment.");}
    private static AccountFailure unavailable(){return new AccountFailure(503,"ACCOUNT_LOOKUP_UNAVAILABLE","Account lookup is unavailable. Retry later.");}
}
