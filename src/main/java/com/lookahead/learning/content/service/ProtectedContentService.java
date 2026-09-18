package com.lookahead.learning.content.service;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.repository.AccountRepository;
import com.lookahead.learning.content.security.AccountPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
@Service @Profile("accounts")
public class ProtectedContentService {
    public record Content(byte[] bytes,String mediaType,String publicationVersion) {}
    private final ProtectedContentPolicy policy;
    private final AccountRepository accounts;
    public ProtectedContentService(ProtectedContentPolicy policy,AccountRepository accounts){this.policy=policy;this.accounts=accounts;}
    public Content read(String path,AccountPrincipal principal) {
        var asset=policy.find(path).orElseThrow(()->new AccountFailure(404,"CONTENT_NOT_FOUND","Content not found"));
        if(!asset.tier().equals("public")) {
            if(principal==null || !accounts.isEnabled(principal.accountId()))throw new AccountFailure(401,"AUTHENTICATION_REQUIRED","Sign in to read this content");
            if(asset.tier().equals("pro") && !asset.matchesScopes(accounts.findTopicGrants(principal.accountId())))throw new AccountFailure(403,"CONTENT_SCOPE_REQUIRED","This content requires Pro access for its course");
        }
        return new Content(policy.read(asset),asset.mediaType(),policy.version());
    }
}
