package com.lookahead.domain.review;

import com.lookahead.domain.persistence.PersistenceStore;
import com.lookahead.learning.content.exception.AccountFailure;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.lookahead.domain.review.ReviewModels.*;

/** DLV-970: the PostgreSQL author review store; the SQL and transaction of {@link AuthorReviewService} before the split, unchanged. */
@Component
@Profile("accounts & local-test & resource & !prod & !production")
@ConditionalOnProperty(name="app.deployment-environment",havingValue="local")
@ConditionalOnProperty(name="app.local-test.author-enabled",havingValue="true")
@ConditionalOnProperty(name = PersistenceStore.PROPERTY, havingValue = "postgresql", matchIfMissing = true)
public class JdbcAuthorReviewStore implements AuthorReviewStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public JdbcAuthorReviewStore(JdbcTemplate jdbc,PlatformTransactionManager transactions) {
        this.jdbc=jdbc;this.transaction=new TransactionTemplate(transactions);
    }
    @Override public List<Event> latest(UUID actorId,String id,int limit) {
        return jdbc.query("SELECT * FROM author_review_events WHERE actor_id=? AND artifact_id=? ORDER BY event_sequence DESC LIMIT ?",(rs,n)->read(rs),actorId,id,limit);
    }
    @Override public Optional<List<Event>> before(UUID actorId,String id,UUID cursor,int limit) {
        var anchor=jdbc.queryForList("SELECT event_sequence FROM author_review_events WHERE actor_id=? AND artifact_id=? AND event_id=?",Long.class,actorId,id,cursor);
        if(anchor.isEmpty())return Optional.empty();
        return Optional.of(jdbc.query("SELECT * FROM author_review_events WHERE actor_id=? AND artifact_id=? AND event_sequence<? ORDER BY event_sequence DESC LIMIT ?",(rs,n)->read(rs),actorId,id,anchor.getFirst(),limit));
    }
    @Override public Receipt submit(UUID actorId,String id,UUID key,String hash,Artifact artifact,Submission normalized) {
        String comment=normalized.comment();
        return transaction.execute(status->{
            // Serialize this actor's submissions across API instances, including first-event races.
            var locked=jdbc.queryForList("SELECT id FROM platform_subjects WHERE id=? FOR UPDATE",UUID.class,actorId);
            if(locked.isEmpty())throw new AccountFailure(401,"AUTHENTICATION_REQUIRED","Sign in to continue");
            var prior=jdbc.query("SELECT * FROM author_review_events WHERE actor_id=? AND idempotency_key=?",(rs,n)->Map.entry(rs.getString("request_hash"),read(rs)),actorId,key);
            if(!prior.isEmpty()) {
                if(!prior.getFirst().getKey().equals(hash))throw new AccountFailure(409,"REVIEW_IDEMPOTENCY_CONFLICT","Use the original payload when retrying a review");
                return new Receipt(prior.getFirst().getValue(),true,"PENDING_MAIN_RECONCILIATION");
            }
            var latest=jdbc.query("SELECT * FROM author_review_events WHERE actor_id=? AND artifact_id=? ORDER BY event_sequence DESC LIMIT 1",(rs,n)->read(rs),actorId,id);
            UUID expected=latest.isEmpty()?null:latest.getFirst().eventId();
            if(!Objects.equals(expected,normalized.supersedesEventId()))throw new AccountFailure(409,"REVIEW_SUPERSESSION_CONFLICT","Reload review history before changing the decision");
            UUID eventId=UUID.randomUUID();
            var event=jdbc.queryForObject("INSERT INTO author_review_events(event_id,artifact_id,artifact_version,content_hash,ticket_id,decision,comment,actor_id,idempotency_key,request_hash,supersedes_event_id) VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING *",(rs,n)->read(rs),eventId,id,artifact.artifactVersion(),artifact.contentHash(),artifact.ticketId(),normalized.decision().name(),comment,actorId,key,hash,expected);
            return new Receipt(event,false,"PENDING_MAIN_RECONCILIATION");
        });
    }
    private static Event read(ResultSet rs)throws SQLException {
        return new Event(rs.getObject("event_id",UUID.class),rs.getString("artifact_id"),rs.getString("artifact_version"),rs.getString("content_hash"),rs.getString("ticket_id"),Decision.valueOf(rs.getString("decision")),rs.getString("comment"),rs.getObject("actor_id",UUID.class),rs.getTimestamp("recorded_at").toInstant(),rs.getObject("idempotency_key",UUID.class),rs.getObject("supersedes_event_id",UUID.class));
    }
}
