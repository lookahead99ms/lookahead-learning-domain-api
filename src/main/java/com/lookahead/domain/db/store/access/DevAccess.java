package com.lookahead.domain.db.store.access;

import com.lookahead.domain.model.DevAccessModels.Row;
import com.lookahead.domain.model.DevAccessModels.Waiting;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** DLV-970: the waiting list and the admission write of {@code DevAccessService} on one store. */
public interface DevAccess {
  /**
   * Waiting accounts (not admitted, not disabled or deleted), oldest request first, with duplicate
   * count and sign-in methods.
   */
  List<Waiting> waiting();

  /** The account read for update, or null. */
  Row lock(UUID accountId);

  /** What {@link #prepare} read; the admission write is made on exactly this reading. */
  interface Prepared {}

  /**
   * Reads the account again just before it is admitted; null when it is gone. The caller refuses,
   * assigns the roles, then calls {@link #admit}.
   */
  Prepared prepare(Row row);

  /**
   * Admits the prepared account: admitted now with the actor, and the admission event with {@code
   * roles}; returns the admission time.
   */
  Instant admit(UUID actor, Prepared prepared, List<String> roles);
}
