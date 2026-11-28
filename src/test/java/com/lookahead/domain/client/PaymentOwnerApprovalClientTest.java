package com.lookahead.domain.client;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * DLV-1016: the service asks the payment client whether purchases wait for approval; it no longer
 * looks at its class.
 */
class PaymentOwnerApprovalClientTest {
  /** A provider that pays at once and remembers what it was asked. */
  private static final class Provider implements PaymentClient {
    final List<String> calls = new ArrayList<>();

    @Override
    public Result create(String key, int amountCents, String currency) {
      calls.add("create " + key);
      return new Result("pi_1", "succeeded", amountCents, currency);
    }

    @Override
    public Result confirm(String id, String key) {
      calls.add("confirm " + id);
      return new Result(id, "succeeded", 1, "USD");
    }

    @Override
    public Result status(String id) {
      calls.add("status " + id);
      return new Result(id, "succeeded", 1, "USD");
    }
  }

  @Test
  void holdsAPurchaseBackAsARequestAndPaysOnlyWhenApproved() {
    var provider = new Provider();
    PaymentClient client = new PaymentOwnerApprovalClient(provider);
    assertThat(client.holdsRequests()).isTrue();
    assertThat(client.create("membership-1", 900, "USD"))
        .isEqualTo(new PaymentClient.Result(null, PaymentClient.AWAITING, 900, "USD"));
    assertThat(provider.calls).as("a request pays nothing").isEmpty();
    assertThat(client.approve("membership-1", 900, "USD"))
        .isEqualTo(new PaymentClient.Result("pi_1", "succeeded", 900, "USD"));
    client.confirm("pi_1", "membership-1");
    client.status("pi_1");
    assertThat(provider.calls)
        .containsExactly("create membership-1", "confirm pi_1", "status pi_1");
    assertThat(PaymentClient.AWAITING).isEqualTo("awaiting_owner_approval");
    assertThat(PaymentClient.DECLINED).isEqualTo("declined_by_owner");
  }

  @Test
  void aClientThatPaysAtOnceHoldsNoRequestsAndHasNothingToApprove() {
    PaymentClient provider = new Provider();
    assertThat(provider.holdsRequests()).isFalse();
    assertThatThrownBy(() -> provider.approve("membership-1", 900, "USD"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("This payment client holds no purchase requests");
  }
}
