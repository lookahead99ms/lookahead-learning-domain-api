package com.lookahead.domain.util;

import static org.assertj.core.api.Assertions.*;

import com.lookahead.domain.exception.AccountFailure;
import com.lookahead.domain.log.RequestLog;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The admin lists read their rows side by side: the order holds, rows overlap, and a row's failure
 * stays itself.
 */
class ParallelRowsTest {
  @AfterEach
  void clear() {
    MDC.clear();
    SecurityContextHolder.clearContext();
  }

  @Test
  void keepsTheOrderOfTheItemsAndKeepsANullRow() {
    var items = new ArrayList<Integer>();
    for (int i = 0; i < 50; i++) items.add(i);
    // The first rows take longest, so an order by completion would be reversed.
    List<String> rows =
        ParallelRows.map(
            items,
            i -> {
              pause(50 - i);
              return i % 10 == 0 ? null : "row " + i;
            });
    assertThat(rows).hasSize(50);
    for (int i = 0; i < 50; i++) assertThat(rows.get(i)).isEqualTo(i % 10 == 0 ? null : "row " + i);
    assertThat(ParallelRows.map(List.<Integer>of(), i -> i)).isEmpty();
  }

  @Test
  void rowsOverlapButNeverMoreThanThePoolAllows() {
    var inside = new AtomicInteger();
    var peak = new AtomicInteger();
    var items = new ArrayList<Integer>();
    for (int i = 0; i < 40; i++) items.add(i);
    ParallelRows.map(
        items,
        i -> {
          peak.accumulateAndGet(inside.incrementAndGet(), Math::max);
          pause(20);
          inside.decrementAndGet();
          return i;
        });
    assertThat(peak.get()).isBetween(2, ParallelRows.PARALLELISM);
  }

  @Test
  void aRowsFailureReachesTheCallerAsItself() {
    assertThatThrownBy(() ->
            ParallelRows.map(
                List.of(1, 2, 3),
                i -> {
                  if (i == 2) throw new AccountFailure(503, "SERVICE_UNAVAILABLE", "Try again");
                  return i;
                }))
        .isInstanceOf(AccountFailure.class)
        .hasMessage("Try again");
    assertThatThrownBy(() ->
            ParallelRows.map(
                List.of(1),
                i -> {
                  throw new IllegalStateException("synthetic");
                }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("synthetic");
  }

  @Test
  void theCallersRequestIdAndSignInReachEveryRowAndAreClearedAfterwards() {
    MDC.put(RequestLog.KEY, "request-1");
    var signedIn = new TestingAuthenticationToken("owner", "n/a");
    SecurityContextHolder.getContext().setAuthentication(signedIn);
    var items = new ArrayList<Integer>();
    for (int i = 0; i < 24; i++) items.add(i);
    List<String> seen =
        ParallelRows.map(
            items,
            i ->
                MDC.get(RequestLog.KEY)
                    + "/"
                    + SecurityContextHolder.getContext().getAuthentication().getName());
    assertThat(seen).containsOnly("request-1/owner");
    // The caller keeps its own context, and a worker that served the request carries nothing into
    // the next one.
    assertThat(MDC.get(RequestLog.KEY)).isEqualTo("request-1");
    MDC.clear();
    SecurityContextHolder.clearContext();
    List<String> later =
        ParallelRows.map(
            items,
            i ->
                MDC.get(RequestLog.KEY)
                    + "/"
                    + SecurityContextHolder.getContext().getAuthentication());
    assertThat(later).containsOnly("null/null");
  }

  private static void pause(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
