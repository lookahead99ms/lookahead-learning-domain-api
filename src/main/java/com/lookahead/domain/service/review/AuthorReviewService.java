package com.lookahead.domain.service.review;

import static com.lookahead.domain.model.AuthorReviewModels.*;

import com.lookahead.domain.db.store.review.AuthorReview;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.content.AuthorPreviewAccessService;
import com.lookahead.domain.util.Sha256;
import com.lookahead.domain.validator.AuthorReviewRequestValidator;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@Profile("accounts & local-test & resource & !prod & !production")
@ConditionalOnProperty(name = "app.deployment-environment", havingValue = "local")
@ConditionalOnProperty(name = "app.local-test.author-enabled", havingValue = "true")
public class AuthorReviewService {
  private final AuthorPreviewAccessService access;
  private final AuthorReviewArtifactService catalog;
  private final AuthorReview store;
  private final ObjectMapper mapper;
  private final AuthorReviewRequestValidator shapes;

  // DLV-970: the transaction lives in the store; policy stays here. DLV-1016: the checks on the
  // request are in the validator.
  public AuthorReviewService(
      AuthorPreviewAccessService access,
      AuthorReviewArtifactService catalog,
      AuthorReview store,
      ObjectMapper mapper,
      AuthorReviewRequestValidator shapes) {
    this.access = access;
    this.catalog = catalog;
    this.store = store;
    this.mapper = mapper;
    this.shapes = shapes;
  }

  public List<Artifact> artifacts(AccountPrincipal actor) {
    access.requireAccess(actor);
    return catalog.list();
  }

  public History history(AccountPrincipal actor, String id, int limit, UUID cursor) {
    access.requireAccess(actor);
    catalog.require(id);
    // Exception, decided 2026-11-27: the page-size bound is compared here, not in
    // AuthorReviewRequestValidator, so that
    // the check guards limit+1 below in the same method (CodeQL java/tainted-arithmetic). Same rule
    // and answer as before.
    if (limit < 1 || limit > AuthorReviewRequestValidator.MAX_PAGE_SIZE) throw shapes.invalid();
    List<Event> events;
    if (cursor == null) events = store.latest(actor.accountId(), id, limit + 1);
    else
      events =
          store
              .before(actor.accountId(), id, cursor, limit + 1)
              .orElseThrow(() ->
                  new AccountFailure(404, "REVIEW_EVENT_NOT_FOUND", "Review event is unavailable"));
    boolean more = events.size() > limit;
    var page = List.copyOf(events.subList(0, Math.min(limit, events.size())));
    return new History(page, more ? page.getLast().eventId() : null);
  }

  /**
   * DLV-1016: any IllegalArgumentException raised while a decision is handled answers 422
   * INVALID_REVIEW_REQUEST (AuthorReviewStore raises one, from Decision.valueOf or UUID.fromString,
   * for a stored review event it cannot read). AuthorReviewController caught it around this call;
   * the catch is here now, around the same work.
   */
  public Receipt submit(AccountPrincipal actor, String id, UUID key, Submission request) {
    try {
      return submitChecked(actor, id, key, request);
    } catch (IllegalArgumentException failure) {
      throw shapes.invalid();
    }
  }

  private Receipt submitChecked(AccountPrincipal actor, String id, UUID key, Submission request) {
    access.requireAccess(actor);
    String comment = shapes.comment(key, request);
    var artifact = catalog.require(id);
    if (!artifact.artifactVersion().equals(request.artifactVersion())
        || !artifact.contentHash().equals(request.contentHash())
        || !artifact.ticketId().equals(request.ticketId()))
      throw new AccountFailure(
          409, "REVIEW_ARTIFACT_STALE", "Reload the current review artifact before deciding");
    var normalized =
        new Submission(
            request.artifactVersion(),
            request.contentHash(),
            request.ticketId(),
            request.decision(),
            comment,
            request.supersedesEventId());
    String hash = digest(id, normalized);
    // DLV-1016: the store reports why its append was not made; the three refusals are made here,
    // right after its call.
    try {
      return store.submit(actor.accountId(), id, key, hash, artifact, normalized);
    } catch (AuthorReview.NotRecorded refused) {
      throw switch (refused.reason()) {
        case ACTOR_UNKNOWN ->
            new AccountFailure(401, "AUTHENTICATION_REQUIRED", "Sign in to continue");
        case IDEMPOTENCY_CONFLICT ->
            new AccountFailure(
                409,
                "REVIEW_IDEMPOTENCY_CONFLICT",
                "Use the original payload when retrying a review");
        case SUPERSESSION_CONFLICT ->
            new AccountFailure(
                409,
                "REVIEW_SUPERSESSION_CONFLICT",
                "Reload review history before changing the decision");
      };
    }
  }

  private String digest(String id, Submission request) {
    try {
      return Sha256.hex(mapper.writeValueAsString(List.of(id, request)));
    } catch (Exception failure) {
      throw new IllegalStateException("Unable to encode review request");
    }
  }
}
