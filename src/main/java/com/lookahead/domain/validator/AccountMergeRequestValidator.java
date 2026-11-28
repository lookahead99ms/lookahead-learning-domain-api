package com.lookahead.domain.validator;

import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.util.CanonicalUuid;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The body of an account merge: {@code keepAccountId} only, a UUID. DLV-1016: moved word for word
 * from AccountMergeController.
 */
@Component
public class AccountMergeRequestValidator {
  /** The account to keep; anything else is 400 {@code INVALID_MERGE_REQUEST}. */
  public UUID keepAccountId(Object payload) {
    if (!(payload instanceof Map<?, ?> body)
        || !body.keySet().equals(Set.of("keepAccountId"))
        || !(body.get("keepAccountId") instanceof String id)) throw invalid();
    // Upper-case letters are accepted here, as before: the text is read in lower case.
    UUID keep = CanonicalUuid.parse(id.toLowerCase(Locale.ROOT));
    if (keep == null) throw invalid();
    return keep;
  }

  private static AccountFailure invalid() {
    return new AccountFailure(400, "INVALID_MERGE_REQUEST", "Send keepAccountId only.");
  }
}
