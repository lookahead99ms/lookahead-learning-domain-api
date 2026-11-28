package com.lookahead.domain.job;

import com.lookahead.domain.db.store.signin.SignInRecordStore;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * DLV-970: the {@code sign-in-records} maintenance job. The retired PostgreSQL store kept every
 * sign-in row and read the live ones by index; on DynamoDB every request reads the account's whole
 * {@code SESSION#} list, so dead records must go. One page walks up to 25 directory entries and
 * deletes, per account, the sessions not active for {@link
 * com.lookahead.domain.util.SignInRules#RECORD_RETENTION} (long past the 30-minute idle and 7-day
 * absolute limits, revoked or not) and the challenges whose expiry is older than the 15-minute
 * rate-limit window. {@code FAMILY#} bindings stay: a revoked token family must never be admitted
 * again, and the binding without its session denies it. Nothing relies on TTL.
 */
@Component
public class SignInRecordRetirementJob implements MaintenanceJobRegistry.Job {
  public static final String NAME = "sign-in-records";
  private final SignInRecordStore store;
  private String afterKey;

  /**
   * DLV-1016: the store is an injected bean; the job no longer takes the DynamoDB connection to
   * build it.
   */
  public SignInRecordRetirementJob(SignInRecordStore store) {
    this.store = store;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public synchronized MaintenanceJobRegistry.Page run(boolean afterFirstPage) {
    if (!afterFirstPage) afterKey = null;
    var page = store.page(afterKey);
    int cleaned = 0, failed = 0;
    for (UUID id : page.accounts()) {
      try {
        cleaned += store.retire(id);
      } catch (RuntimeException failure) {
        failed++;
      }
    }
    afterKey = page.afterKey();
    return new MaintenanceJobRegistry.Page(cleaned, 0, failed, afterKey != null);
  }
}
