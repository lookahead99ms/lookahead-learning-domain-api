package com.lookahead.domain.db.store.testaccounts;

import java.util.UUID;

/** DLV-970: the local-test seed writes of {@code TestAccountFixtureSeedJob} on one store. */
public interface TestAccountFixtureSeed {
  /** The subject row; false when it already existed (a restart must not restore revoked grants). */
  boolean insertSubject(UUID id);

  /** A grant for {@code topic}, left as it is when one exists. */
  void grant(UUID id, String topic);
}
