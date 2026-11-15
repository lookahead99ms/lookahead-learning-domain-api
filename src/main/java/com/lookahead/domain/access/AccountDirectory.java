package com.lookahead.domain.access;

import com.lookahead.domain.Events;
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
    private final AccountDirectoryStore store;
    public AccountDirectory(ObjectProvider<IdentitySettings> settings, JdbcTemplate jdbc) { this(settings, new JdbcAccountDirectoryStore(jdbc)); }
    /** DLV-970: the store (PostgreSQL or DynamoDB) is selected by {@code app.persistence.store}. */
    @org.springframework.beans.factory.annotation.Autowired
    public AccountDirectory(ObjectProvider<IdentitySettings> settings, AccountDirectoryStore store) {
        this.identity=settings.getIfAvailable();this.store=store;
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
            // DLV-969: disabled accounts stay listed (the owner can enable them); deleted ones are gone. The owner sees
            // the provider-verified email (a Google account's Gmail).
            return store.list(term,page);
        }
        long started=Events.started();int[] status={0};
        try {
            var uri=org.springframework.web.util.UriComponentsBuilder.fromUriString(identity.upstream()+"/internal/v1/accounts")
                .queryParam("query","{query}").queryParam("page",page).encode().buildAndExpand(term).toUri();
            return answered("ListAccounts",started,http.get().uri(uri).headers(h->h.setBasicAuth("lookahead-domain-verifier",identity.verifierSecret()))
                .exchange((sent,response)-> {
                    status[0]=response.getStatusCode().value();
                    if(response.getStatusCode().value()!=200)throw unavailable();
                    byte[] bytes=response.getBody().readNBytes(262145);
                    if(bytes.length>262144)throw unavailable();
                    var result=new tools.jackson.databind.ObjectMapper().readValue(bytes,Page.class);
                    if(result==null||result.accounts()==null||result.accounts().size()>50||result.page()!=page
                       ||result.accounts().stream().anyMatch(a->a==null||a.accountId()==null||a.username()==null||a.displayName()==null))throw unavailable();
                    return result;
                }));
        } catch(AccountFailure expected){throw refused("ListAccounts",started,status[0],expected);}catch(RuntimeException failure){throw failed("ListAccounts",started,status[0],failure);}
    }
    private static <T> T answered(String operation,long started,T result){Events.upstreamDone("Identity",operation,started);return result;}
    /** Identity answered, but not with a usable account: not found is a refusal, anything else a failure. */
    private static AccountFailure refused(String operation,long started,int status,AccountFailure outcome){
        if("ACCOUNT_NOT_FOUND".equals(outcome.code()))Events.upstreamRefused("Identity",operation,started,"ACCOUNT_NOT_FOUND");
        else Events.upstreamFailed("Identity",operation,started,null,status==0?null:status,status==200?"INVALID_RESPONSE":null);
        return outcome;
    }
    private static AccountFailure failed(String operation,long started,int status,RuntimeException failure){
        Events.upstreamFailed("Identity",operation,started,failure,status==0?Events.status(failure):Integer.valueOf(status),null);
        return unavailable();
    }
    public Account lookup(String query) {
        if(query==null||query.isBlank()||query.length()>254||query.codePoints().anyMatch(Character::isISOControl))throw invalid();
        query=query.strip().toLowerCase(java.util.Locale.ROOT);
        if(identity==null) {
            // Cloud identities already admitted by the provider mapping; no email-based linking. DLV-969 S36: any Cognito
            // username of the account (V19 cloud_identities), or its own username for a row without identity rows.
            return store.lookup(query);
        }
        long started=Events.started();int[] status={0};
        try {
            return answered("LookupAccount",started,http.post().uri(identity.upstream()+"/internal/v1/accounts/lookup")
                .headers(h->h.setBasicAuth("lookahead-domain-verifier",identity.verifierSecret()))
                .body(Map.of("query",query)).exchange((sent,response)-> {
                    status[0]=response.getStatusCode().value();
                    if(response.getStatusCode().value()==404)throw missing();
                    if(response.getStatusCode().value()!=200)throw unavailable();
                    byte[] bytes=response.getBody().readNBytes(4097);
                    if(bytes.length>4096)throw unavailable();
                    var account=new tools.jackson.databind.ObjectMapper().readValue(bytes,Account.class);
                    if(account.accountId()==null||account.username()==null||account.displayName()==null)throw unavailable();
                    store.ensureSubject(account.accountId());
                    return account;
                }));
        } catch(AccountFailure expected){throw refused("LookupAccount",started,status[0],expected);}catch(RuntimeException failure){throw failed("LookupAccount",started,status[0],failure);}
    }
    public Account byId(UUID id) {
        if (id == null) throw invalid();
        if (identity == null) {
            return store.byId(id);
        }
        long started=Events.started();int[] status={0};
        try {
            return answered("GetAccount",started,http.get().uri(identity.upstream()+"/internal/v1/accounts/"+id)
                .headers(h->h.setBasicAuth("lookahead-domain-verifier",identity.verifierSecret()))
                .exchange((sent,response)-> {
                    status[0]=response.getStatusCode().value();
                    if(response.getStatusCode().value()==404)throw missing();
                    if(response.getStatusCode().value()!=200)throw unavailable();
                    byte[] bytes=response.getBody().readNBytes(4097);
                    if(bytes.length>4096)throw unavailable();
                    var account=new tools.jackson.databind.ObjectMapper().readValue(bytes,Account.class);
                    if(account==null||!id.equals(account.accountId()))throw unavailable();
                    return account;
                }));
        } catch(AccountFailure expected){throw refused("GetAccount",started,status[0],expected);}catch(RuntimeException failure){throw failed("GetAccount",started,status[0],failure);}
    }
    private static AccountFailure invalid(){return new AccountFailure(422,"INVALID_ACCOUNT_LOOKUP","Enter an exact email or username.");}
    static AccountFailure missing(){return new AccountFailure(404,"ACCOUNT_NOT_FOUND","No eligible existing account matched. You can prepare a pending email assignment.");}
    private static AccountFailure unavailable(){return new AccountFailure(503,"ACCOUNT_LOOKUP_UNAVAILABLE","Account lookup is unavailable. Retry later.");}
}
