package com.lookahead.domain.service.content;
import com.lookahead.domain.db.store.content.ProtectedContentFileStore;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.service.access.AccountGrantService;
import com.lookahead.domain.security.token.AccountPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
@Service @Profile("accounts")
public class ProtectedContentService {
    public record Content(byte[] bytes,String mediaType,String publicationVersion) {}
    private final ProtectedContentManifestService policy;
    private final ProtectedContentFileStore contentFiles;
    private final AccountGrantService accounts;
    public ProtectedContentService(ProtectedContentManifestService policy,ProtectedContentFileStore contentFiles,AccountGrantService accounts){this.policy=policy;this.contentFiles=contentFiles;this.accounts=accounts;}
    public Content read(String path,AccountPrincipal principal) {
        var asset=policy.find(path).orElseThrow(()->new AccountFailure(404,"CONTENT_NOT_FOUND","Content not found"));
        if(!asset.tier().equals("public")) {
            if(principal==null || !accounts.isEnabled(principal.accountId()))throw new AccountFailure(401,"AUTHENTICATION_REQUIRED","Sign in to read this content");
            if(asset.tier().equals("pro") && !asset.matchesScopes(accounts.findTopicGrants(principal.accountId())))throw new AccountFailure(403,"CONTENT_SCOPE_REQUIRED","This content requires Pro access for its course");
        }
        policy.requirePublished(asset);
        return new Content(contentFiles.read(asset),asset.mediaType(),policy.version());
    }
}
