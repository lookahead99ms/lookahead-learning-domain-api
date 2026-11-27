package com.lookahead.domain.validator;

import com.lookahead.domain.util.CanonicalUuid;
import com.lookahead.domain.exception.AccountFailure;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import static com.lookahead.domain.model.AuthorReviewModels.*;

/**
 * The shape of a request on the author review routes: the history cursor, the Idempotency-Key and the decision body.
 * DLV-1016: moved word for word from AuthorReviewController; the page size and the comment rules came from
 * AuthorReviewService. Every wrong shape is 422 {@code INVALID_REVIEW_REQUEST}.
 */
@Component
public class AuthorReviewRequestValidator {
    /** The history cursor: none, or a UUID in its canonical form. */
    public UUID cursor(String cursor) { return cursor==null?null:uuid(cursor); }

    /** The Idempotency-Key of a decision: required, a UUID in its canonical form. */
    public UUID key(String key) { return uuid(key); }

    /** A decision: no query string, the five fields (and at most {@code supersedesEventId}), each of its type. */
    public Submission submission(String queryString, Map<String,Object> body) {
        if(queryString!=null)throw invalid();
        if(body==null||!Set.of("artifactVersion","contentHash","ticketId","decision","comment","supersedesEventId").containsAll(body.keySet())
                || !body.keySet().containsAll(Set.of("artifactVersion","contentHash","ticketId","decision","comment")))throw invalid();
        try {
            return new Submission(text(body,"artifactVersion"),text(body,"contentHash"),text(body,"ticketId"),Decision.valueOf(text(body,"decision")),text(body,"comment"),body.get("supersedesEventId")==null?null:uuid(text(body,"supersedesEventId")));
        } catch(IllegalArgumentException failure){throw invalid();}
    }

    /** The page size of a history read: 1 to 100 (DLV-1016: this check was written in AuthorReviewService.history). */
    /** The largest page of review history one request may ask for. */
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * The comment of a decision, stripped, after the checks on the decision itself: a key, a decision and a comment are
     * present; the comment has at most 2000 code points, no control character except new line and tab, no angle
     * bracket and no surrogate; anything but an approval needs words (422 {@code REVIEW_COMMENT_REQUIRED}).
     * DLV-1016: these checks were written in AuthorReviewService.submit; they are here word for word, in the same order.
     */
    public String comment(UUID key, Submission request) {
        if(key==null||request==null||request.decision()==null||request.comment()==null)throw invalid();
        String comment=request.comment().strip();
        if(comment.codePointCount(0,comment.length())>2000 || comment.codePoints().anyMatch(c->(Character.isISOControl(c)&&c!='\n'&&c!='\t')||c=='<'||c=='>'||(c>=0xd800&&c<=0xdfff)))throw invalid();
        if(request.decision()!=Decision.APPROVE&&comment.isBlank())throw new AccountFailure(422,"REVIEW_COMMENT_REQUIRED","Explain what needs to change");
        return comment;
    }

    public AccountFailure invalid(){return new AccountFailure(422,"INVALID_REVIEW_REQUEST","Review request is invalid");}
    private String text(Map<String,Object> body,String key){if(!(body.get(key) instanceof String text))throw invalid();return text;}
    private UUID uuid(String value){UUID id=CanonicalUuid.parse(value);if(id==null)throw invalid();return id;}
}
