package com.lookahead.domain.review;

import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.service.AuthorPreviewAccessService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import static com.lookahead.domain.review.ReviewModels.*;

@Service
@Profile("accounts & local-test & resource & !prod & !production")
@ConditionalOnProperty(name="app.deployment-environment",havingValue="local")
@ConditionalOnProperty(name="app.local-test.author-enabled",havingValue="true")
public class AuthorReviewService {
    private final AuthorPreviewAccessService access;
    private final ReviewArtifactCatalog catalog;
    private final AuthorReviewStore store;
    private final ObjectMapper mapper;
    // DLV-970: the transaction lives in the store (PostgreSQL or DynamoDB); validation and policy stay here.
    public AuthorReviewService(AuthorPreviewAccessService access,ReviewArtifactCatalog catalog,AuthorReviewStore store,ObjectMapper mapper) {
        this.access=access;this.catalog=catalog;this.store=store;this.mapper=mapper;
    }
    public List<Artifact> artifacts(AccountPrincipal actor) { access.requireAccess(actor);return catalog.list(); }
    public History history(AccountPrincipal actor,String id,int limit,UUID cursor) {
        access.requireAccess(actor);catalog.require(id);
        if(limit<1||limit>100)throw invalid();
        List<Event> events;
        if(cursor==null) events=store.latest(actor.accountId(),id,limit+1);
        else events=store.before(actor.accountId(),id,cursor,limit+1).orElseThrow(()->new AccountFailure(404,"REVIEW_EVENT_NOT_FOUND","Review event is unavailable"));
        boolean more=events.size()>limit;var page=List.copyOf(events.subList(0,Math.min(limit,events.size())));
        return new History(page,more?page.getLast().eventId():null);
    }
    public Receipt submit(AccountPrincipal actor,String id,UUID key,Submission request) {
        access.requireAccess(actor);
        if(key==null||request==null||request.decision()==null||request.comment()==null)throw invalid();
        String comment=request.comment().strip();
        if(comment.codePointCount(0,comment.length())>2000 || comment.codePoints().anyMatch(c->(Character.isISOControl(c)&&c!='\n'&&c!='\t')||c=='<'||c=='>'||(c>=0xd800&&c<=0xdfff)))throw invalid();
        if(request.decision()!=Decision.APPROVE&&comment.isBlank())throw new AccountFailure(422,"REVIEW_COMMENT_REQUIRED","Explain what needs to change");
        var artifact=catalog.require(id);
        if(!artifact.artifactVersion().equals(request.artifactVersion())||!artifact.contentHash().equals(request.contentHash())||!artifact.ticketId().equals(request.ticketId()))
            throw new AccountFailure(409,"REVIEW_ARTIFACT_STALE","Reload the current review artifact before deciding");
        var normalized=new Submission(request.artifactVersion(),request.contentHash(),request.ticketId(),request.decision(),comment,request.supersedesEventId());
        String hash=digest(id,normalized);
        return store.submit(actor.accountId(),id,key,hash,artifact,normalized);
    }
    private String digest(String id,Submission request) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(List.of(id,request)).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception failure){throw new IllegalStateException("Unable to encode review request");}
    }
    private static AccountFailure invalid(){return new AccountFailure(422,"INVALID_REVIEW_REQUEST","Review request is invalid");}
}
