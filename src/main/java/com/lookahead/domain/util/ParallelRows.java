package com.lookahead.domain.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The rows of one admin list, read side by side. Each row of the People list or the Test accounts
 * list is several independent DynamoDB reads; one row after another took about 7 s for a page of 35
 * on DEV. The rows run on one small shared pool and come back in the order they were given.
 *
 * <p>Only for reads. A row function must not write, and must not call {@link #map} itself (the pool
 * is small and fixed, so a row that waits for its own sub-rows could wait for ever). The caller's
 * request ID and sign-in travel to the worker, so a log line written while a row is read still
 * names the request.
 */
public final class ParallelRows {
  /**
   * Rows in flight at once, across every request: enough to overlap the reads, small enough to
   * spare the table.
   */
  public static final int PARALLELISM = 8;

  private static final ExecutorService POOL =
      Executors.newFixedThreadPool(
          PARALLELISM,
          runnable -> {
            Thread thread = new Thread(runnable, "row-reads");
            thread.setDaemon(true);
            return thread;
          });

  private ParallelRows() {}

  /**
   * {@code row} for every item, in the items' order. A row's own failure reaches the caller as
   * itself, not wrapped.
   */
  public static <T, R> List<R> map(Collection<T> items, Function<? super T, ? extends R> row) {
    Map<String, String> log = MDC.getCopyOfContextMap();
    SecurityContext signIn = SecurityContextHolder.getContext();
    List<CompletableFuture<R>> futures = new ArrayList<>(items.size());
    for (T item : items)
      futures.add(CompletableFuture.supplyAsync(() -> withCaller(log, signIn, row, item), POOL));
    List<R> rows = new ArrayList<>(futures.size());
    for (CompletableFuture<R> future : futures) rows.add(await(future));
    return rows;
  }

  private static <T, R> R withCaller(
      Map<String, String> log,
      SecurityContext signIn,
      Function<? super T, ? extends R> row,
      T item) {
    if (log != null) MDC.setContextMap(log);
    SecurityContextHolder.setContext(signIn);
    try {
      return row.apply(item);
    } finally {
      MDC.clear();
      SecurityContextHolder.clearContext();
    }
  }

  private static <R> R await(CompletableFuture<R> future) {
    try {
      return future.join();
    } catch (CompletionException failure) {
      if (failure.getCause() instanceof RuntimeException cause) throw cause;
      throw failure;
    }
  }
}
