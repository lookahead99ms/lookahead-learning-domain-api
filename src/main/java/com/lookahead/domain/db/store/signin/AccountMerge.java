package com.lookahead.domain.db.store.signin;

import com.lookahead.domain.db.store.plan.StudyPlanFacts;
import com.lookahead.domain.model.AccountMergeModels.Member;
import com.lookahead.domain.model.AccountMergeModels.Merged;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The stored side of the account merge. DLV-1016: AccountMergeService asks for each read in turn,
 * makes its checks between them and then asks for one fenced commit on exactly what was read; no
 * check and no refusal is made here. The two commits ({@link #recordLink}, {@link #merge}) answer
 * whether they held; what a lost race means is the service's.
 */
public interface AccountMerge {
  /**
   * A link as stored (the token itself is never stored, only its digest). {@code used}: used up by
   * a merge or replaced.
   */
  record Link(String email, Instant expiresAt, String digest, Instant createdAt, boolean used) {}

  /**
   * An account that carries an email; {@code testAccount}: a managed test account, or one in the
   * test-account registry.
   */
  record Carrier(UUID id, boolean testAccount) {}

  /**
   * What a new request does to an earlier link of the email: {@code delete} removes it, otherwise
   * it is marked used now.
   */
  record LinkChange(Link link, boolean delete) {}

  /**
   * What the merge page shows of one account, as stored; {@code signInMethods} is empty for an
   * account without identity items.
   */
  record CandidateFacts(
      String displayName,
      List<Map<String, Object>> signInMethods,
      Instant requestedAt,
      Instant lastSignInAt,
      List<StudyPlanFacts.Card> plans,
      boolean paidMembership) {}

  /**
   * The accounts of a pool as read, in the pool's order (one that vanished meanwhile is left out);
   * {@link #merge} commits on exactly these reads.
   */
  interface Snapshot {
    List<Member> members();
  }

  Instant now();

  /** The link stored for a token digest, or null. */
  Link link(String digest);

  /** Every link recorded for the email, oldest first. */
  List<Link> links(String email);

  /** The revision of the email's sentinel; the commits are conditioned on it and advance it. */
  long emailRevision(String email);

  /** The accounts that carry the email now: not on the test issuer, not deleted. */
  List<Carrier> carriers(String email);

  /**
   * Whether the account carries the email at all (deleted and test accounts included, the test
   * issuer not).
   */
  boolean carries(String email, UUID account);

  Snapshot read(List<UUID> pool);

  CandidateFacts candidate(Snapshot snapshot, UUID account);

  /**
   * The request's commit, under the email's sentinel as read: the earlier links changed in the
   * given order, then the new link recorded. False when another request, link or merge for the
   * email was first; nothing was written.
   */
  boolean recordLink(
      String email,
      long emailRevision,
      UUID requestedBy,
      List<LinkChange> earlier,
      String digest,
      Instant now,
      Instant expires);

  /**
   * The merge's commit, under the email's sentinel and every account's control as read: every
   * identity of the others moved to the kept account, every sign-in of all of them ended, each
   * other account marked deleted (or, when it was still waiting, removed), the link used. Answers
   * the accounts that went, in the snapshot's order; null when another writer was first, and then
   * nothing was written.
   */
  Merged merge(Snapshot snapshot, Link link, long emailRevision, UUID actor, UUID keep);
}
