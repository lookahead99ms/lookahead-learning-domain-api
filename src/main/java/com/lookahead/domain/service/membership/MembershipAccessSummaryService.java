package com.lookahead.domain.service.membership;

import com.lookahead.domain.db.store.membership.MembershipAccessSummary;
import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.security.token.AccountPrincipal;
import com.lookahead.domain.service.access.StaffAccessService;
import com.lookahead.domain.util.CatalogIds;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** A read projection of actual purchases and grants. Never persisted as a role. */
@Service
public class MembershipAccessSummaryService {
  public record Summary(String label, List<String> labels, List<String> grantedCourseIds) {}

  private final MembershipAccessSummary store;
  private final StaffAccessService staff;
  private final Clock clock;

  @Autowired
  public MembershipAccessSummaryService(MembershipAccessSummary store, StaffAccessService staff) {
    this(store, staff, Clock.systemUTC());
  }

  public MembershipAccessSummaryService(
      MembershipAccessSummary store, StaffAccessService staff, Clock clock) {
    this.store = store;
    this.staff = staff;
    this.clock = clock;
  }

  public Summary get(AccountPrincipal actor, UUID target) {
    AccountPrincipal.requireSignedIn(actor, "Sign in to continue");
    if (!actor.accountId().equals(target)) {
      var permissions = staff.permissions(actor);
      if (!permissions.manageAccess()
          && !permissions.triageFeedback()
          && !"co-author".equals(permissions.feedbackRole()))
        throw new AccountFailure(403, "STAFF_REQUIRED", "Author, co-author or admin required");
    }
    Instant now = clock.instant();
    var purchases = store.activeSnapshots(target, now);
    var courses = store.grantedTopics(target, now);
    return summarize(purchases, courses);
  }

  static Summary summarize(List<JsonNode> purchases, Set<String> grants) {
    var labels = new TreeSet<String>();
    for (var plan : purchases) {
      String label =
          ("promo".equals(plan.path("group").asText()) ? "Promo" : "Regular")
              + " · "
              + plan.path("name").asText();
      if ("courses".equals(plan.path("scope").path("kind").asText())) {
        var chosen = new TreeSet<String>();
        plan.path("scope").path("courseIds").forEach(value -> chosen.add(value.asText()));
        if (!chosen.isEmpty()) label += " · " + String.join(", ", chosen);
      }
      labels.add(label);
    }
    var courses =
        new TreeSet<>(CatalogIds.curriculumGrants(grants)); // DLV-968: staff scopes are not courses
    if (!courses.isEmpty()) labels.add("Granted · " + String.join(", ", courses));
    if (labels.isEmpty()) labels.add("Free");
    return new Summary(String.join(" + ", labels), List.copyOf(labels), List.copyOf(courses));
  }
}
