package com.lookahead.domain.db.store.access;

import static com.lookahead.domain.db.store.signin.AccountItems.*;

import com.lookahead.domain.db.store.dynamodb.DynamoTableNames;
import com.lookahead.domain.db.store.dynamodb.DynamoWrites;
import com.lookahead.domain.db.store.membership.MembershipErasure;
import com.lookahead.domain.db.store.plan.StudyPlanAccountErasure;
import com.lookahead.domain.db.store.signin.AccountAggregateStore;
import com.lookahead.domain.db.store.signin.AccountAggregateStore.Control;
import com.lookahead.domain.db.store.testaccounts.TestAccountRegistry;
import com.lookahead.domain.model.AccountRemovalModels.Target;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DLV-970: Disable, Enable and Delete on DynamoDB. An admitted account goes through the {@link
 * AccountControlStore} library (revision- and generation-fenced {@code CONTROL} transition, durable
 * command receipt, insert-only admission event, terminal {@code REMOVED} marker and the erasure
 * job); a waiting account (DLV-971 Reject), which that library refuses, is handled here with the
 * same item shapes: Disable/Enable change its state under the control revision, Delete removes it
 * the way the 48-hour cleanup does. Sign-ins end through {@code authGeneration}; erasure runs after
 * the state change and is resumable ({@link AccountErasureStore}).
 */
@Repository
public class AccountRemovalStore implements AccountRemoval {
  private final AccountAggregateStore accounts;
  private final AccountControlStore controls;
  private final AccountErasureStore erasure;
  private final ObjectProvider<TestAccountRegistry> testAccounts;

  @Autowired
  public AccountRemovalStore(
      DynamoDbClient client,
      DynamoTableNames tables,
      StaffAccess staff,
      ObjectProvider<TestAccountRegistry> testAccounts,
      ObjectProvider<StudyPlanAccountErasure> plans,
      ObjectProvider<MembershipErasure> memberships) {
    this(client, tables, staff, testAccounts, plans, memberships, Clock.systemUTC());
  }

  public AccountRemovalStore(
      DynamoDbClient client,
      DynamoTableNames tables,
      StaffAccess staff,
      ObjectProvider<TestAccountRegistry> testAccounts,
      ObjectProvider<StudyPlanAccountErasure> plans,
      ObjectProvider<MembershipErasure> memberships,
      Clock clock) {
    this.accounts = new AccountAggregateStore(client, tables, clock);
    this.controls = new AccountControlStore(client, tables);
    this.erasure = new AccountErasureStore(accounts, staff, plans, memberships);
    this.testAccounts = testAccounts;
  }

  public AccountErasureStore erasure() {
    return erasure;
  }

  @Override
  public Target options(UUID id) {
    var control = accounts.control(id);
    return control == null
        ? null
        : new Target(
            control.issuer(),
            control.subject(),
            control.username(),
            control.admitted(),
            control.author(),
            control.stateLabel(),
            List.of());
  }

  /**
   * The account as read, for the service to decide on; each further value is read when it is asked
   * for, in the order the checks always ran.
   */
  private final class Read implements Subject {
    private final Control c;
    private Target target;

    Read(Control c) {
      this.c = c;
    }

    @Override
    public boolean author() {
      return c.author();
    }

    @Override
    public boolean managedTest() {
      var registry = testAccounts.getIfAvailable();
      return c.managedTest() || (registry != null && registry.isTestAccount(c.id()));
    }

    @Override
    public Target target() {
      if (target == null) target = AccountRemovalStore.this.target(c);
      return target;
    }

    @Override
    public boolean removed() {
      return c.removed();
    }

    @Override
    public boolean disabled() {
      return c.disabled();
    }

    @Override
    public boolean active() {
      return c.active();
    }
  }

  @Override
  public Subject subject(UUID id) {
    var c = accounts.control(id);
    return c == null ? null : new Read(c);
  }

  @Override
  public void disable(UUID actor, Subject subject) {
    var c = ((Read) subject).c;
    if (c.admitted()) transition(actor, c, AccountControlStore.Action.DISABLE, "DISABLED");
    else waitingState(actor, c, "DISABLED", c.authGeneration() + 1, "disable");
  }

  @Override
  public void enable(UUID actor, Subject subject) {
    var c = ((Read) subject).c;
    if (c.admitted()) transition(actor, c, AccountControlStore.Action.ENABLE, "ACTIVE");
    else waitingState(actor, c, "ACTIVE", c.authGeneration(), "enable");
  }

  @Override
  public void remove(UUID actor, Subject subject) {
    var c = ((Read) subject).c;
    UUID id = c.id();
    if (!c.admitted()) {
      // DLV-971: a waiting account has no learning data and no permanent record to keep; it goes
      // the way an
      // expired request goes, so the same person signing in again appears as a new request.
      var tx = accounts.transaction();
      accounts.admission(
          tx, id, c.issuer(), c.subject(), actor, "remove", List.of(), accounts.now());
      accounts.deleteWaiting(tx, c, accounts.identities(id));
      tx.commit();
      return;
    }
    erasure.queue(actor, id);
    transition(actor, c, AccountControlStore.Action.REMOVE, "REMOVED");
    erasure.erase(actor, id);
  }

  /** Already deleted: a retry finishes an interrupted erasure. */
  @Override
  public void finishErasure(UUID actor, UUID id) {
    if (erasure.pending(id)) erasure.erase(actor, id);
  }

  @Override
  public void erase(UUID actor, UUID id) {
    erasure.erase(actor, id);
  }

  /**
   * The account's own username stands in only while its first identity has no binding anywhere (an
   * account from before V19); once a merge moved that binding elsewhere, only the identities
   * decide.
   */
  private Target target(Control c) {
    var usernames = accounts.usernames(c.id());
    boolean ownMissing = usernames.isEmpty() && accounts.binding(c.issuer(), c.subject()).isEmpty();
    return new Target(
        c.issuer(),
        c.subject(),
        c.username(),
        c.admitted(),
        c.author(),
        c.stateLabel(),
        ownMissing ? List.of(c.username()) : usernames);
  }

  /**
   * An admitted account changes state through the control library, acting as the owner's own
   * identity. A change that was not made and is not a lost race is {@link NotChanged}.
   */
  private void transition(
      UUID actor, Control target, AccountControlStore.Action action, String desired) {
    String name = action.name().toLowerCase(Locale.ROOT);
    var owner = accounts.control(actor);
    if (owner == null)
      throw new NotChanged(NotChanged.Reason.OWNER_IDENTITY_UNKNOWN, target.id(), name, null);
    var command =
        new AccountControlStore.Command(
            new AccountControlStore.Owner(actor, owner.issuer(), owner.subject()),
            target.id(),
            UUID.randomUUID(),
            target.revision(),
            action,
            accounts.now());
    try {
      controls.transition(command);
    } catch (DynamoWrites.Conflict raced) {
      // A revision race: the state may already be the desired one (another Disable), else the
      // caller reloads and retries (409).
      var now = accounts.control(target.id());
      if (now != null && desired.equals(now.state())) return;
      throw raced;
    } catch (IllegalStateException refused) {
      // A lost revision race that still reached the desired state counts as done; anything else is
      // reported, not guessed.
      var now = accounts.control(target.id());
      if (now != null && desired.equals(now.state())) return;
      throw new NotChanged(NotChanged.Reason.ACCOUNT_CONTROL_REFUSED, target.id(), name, refused);
    }
  }

  /**
   * A waiting account (the control library refuses unadmitted accounts): the same control fields,
   * event and directory flag.
   */
  private void waitingState(UUID actor, Control c, String state, long generation, String action) {
    Instant now = accounts.now();
    var tx = accounts.transaction();
    accounts.control(
        tx,
        c,
        attrs(
            "accountState",
            s(state),
            "authGeneration",
            n(generation),
            "changedAt",
            time(now),
            "changedBy",
            s(actor.toString())),
        "\"admitted\"=?",
        bool(false));
    accounts.directory(tx, c, Map.of("accountState", s(state)));
    accounts.admission(tx, c.id(), c.issuer(), c.subject(), actor, action, List.of(), now);
    try {
      tx.commit();
    } catch (DynamoWrites.Conflict raced) {
      var now2 = accounts.control(c.id());
      if (now2 == null || !state.equals(now2.state())) throw raced;
    }
  }
}
