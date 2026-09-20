package com.lookahead.domain.review;

import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.service.AuthorPreviewAccessService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static com.lookahead.domain.review.ReviewModels.*;

@Service
@Profile("accounts & local-test & resource & !prod & !production")
@ConditionalOnProperty(name="app.deployment-environment",havingValue="local")
@ConditionalOnProperty(name="app.local-test.author-enabled",havingValue="true")
public class AuthorReviewService {
    private final AuthorPreviewAccessService access;
    private final ReviewArtifactCatalog catalog;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    public AuthorReviewService(AuthorPreviewAccessService access,ReviewArtifactCatalog catalog,JdbcTemplate jdbc,
                              PlatformTransactionManager transactions,ObjectMapper mapper) {
        this.access=access;this.catalog=catalog;this.jdbc=jdbc;this.transaction=new TransactionTemplate(transactions);this.mapper=mapper;
    }
    public List<Artifact> artifacts(AccountPrincipal actor) { access.requireAccess(actor);return catalog.list(); }
    public History history(AccountPrincipal actor,String id,int limit,UUID cursor) {
        access.requireAccess(actor);catalog.require(id);
        if(limit<1||limit>100)throw invalid();
        List<Event> events;
        if(cursor==null) events=jdbc.query("SELECT * FROM author_review_events WHERE actor_id=? AND artifact_id=? ORDER BY event_sequence DESC LIMIT ?",(rs,n)->read(rs),actor.accountId(),id,limit+1);
        else {
            var anchor=jdbc.queryForList("SELECT event_sequence FROM author_review_events WHERE actor_id=? AND artifact_id=? AND event_id=?",Long.class,actor.accountId(),id,cursor);
            if(anchor.isEmpty())throw new AccountFailure(404,"REVIEW_EVENT_NOT_FOUND","Review event is unavailable");
            events=jdbc.query("SELECT * FROM author_review_events WHERE actor_id=? AND artifact_id=? AND event_sequence<? ORDER BY event_sequence DESC LIMIT ?",(rs,n)->read(rs),actor.accountId(),id,anchor.getFirst(),limit+1);
        }
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
        return transaction.execute(status->{
            // Serialize this actor's submissions across API instances, including first-event races.
            var locked=jdbc.queryForList("SELECT id FROM platform_subjects WHERE id=? FOR UPDATE",UUID.class,actor.accountId());
            if(locked.isEmpty())throw new AccountFailure(401,"AUTHENTICATION_REQUIRED","Sign in to continue");
            var prior=jdbc.query("SELECT * FROM author_review_events WHERE actor_id=? AND idempotency_key=?",(rs,n)->Map.entry(rs.getString("request_hash"),read(rs)),actor.accountId(),key);
            if(!prior.isEmpty()) {
                if(!prior.getFirst().getKey().equals(hash))throw new AccountFailure(409,"REVIEW_IDEMPOTENCY_CONFLICT","Use the original payload when retrying a review");
                return new Receipt(prior.getFirst().getValue(),true,"PENDING_MAIN_RECONCILIATION");
            }
            var latest=jdbc.query("SELECT * FROM author_review_events WHERE actor_id=? AND artifact_id=? ORDER BY event_sequence DESC LIMIT 1",(rs,n)->read(rs),actor.accountId(),id);
            UUID expected=latest.isEmpty()?null:latest.getFirst().eventId();
            if(!Objects.equals(expected,normalized.supersedesEventId()))throw new AccountFailure(409,"REVIEW_SUPERSESSION_CONFLICT","Reload review history before changing the decision");
            UUID eventId=UUID.randomUUID();
            var event=jdbc.queryForObject("INSERT INTO author_review_events(event_id,artifact_id,artifact_version,content_hash,ticket_id,decision,comment,actor_id,idempotency_key,request_hash,supersedes_event_id) VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING *",(rs,n)->read(rs),eventId,id,artifact.artifactVersion(),artifact.contentHash(),artifact.ticketId(),normalized.decision().name(),comment,actor.accountId(),key,hash,expected);
            return new Receipt(event,false,"PENDING_MAIN_RECONCILIATION");
        });
    }
    private String digest(String id,Submission request) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(List.of(id,request)).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception failure){throw new IllegalStateException("Unable to encode review request");}
    }
    private static Event read(ResultSet rs)throws SQLException {
        return new Event(rs.getObject("event_id",UUID.class),rs.getString("artifact_id"),rs.getString("artifact_version"),rs.getString("content_hash"),rs.getString("ticket_id"),Decision.valueOf(rs.getString("decision")),rs.getString("comment"),rs.getObject("actor_id",UUID.class),rs.getTimestamp("recorded_at").toInstant(),rs.getObject("idempotency_key",UUID.class),rs.getObject("supersedes_event_id",UUID.class));
    }
    private static AccountFailure invalid(){return new AccountFailure(422,"INVALID_REVIEW_REQUEST","Review request is invalid");}
}
