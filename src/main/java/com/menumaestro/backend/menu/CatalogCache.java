package com.menumaestro.backend.menu;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A shared process-local snapshot. Warm reads never expire it or fetch the database;
 * startup, explicit invalidation and the background timer own all refreshes. Concurrent
 * refreshes share one pending load, and a write racing with a refresh prevents the old
 * snapshot from being published.
 */
public class CatalogCache<T> {
  private final Supplier<T> load;
  private final long refreshIntervalMs;
  private final Consumer<Throwable> onError;
  private final Executor executor;
  private T snapshot;
  private CompletableFuture<T> pending;
  private long generation;
  private ScheduledExecutorService scheduler;
  private ScheduledFuture<?> timer;

  public CatalogCache(Supplier<T> load, long refreshIntervalMs, Consumer<Throwable> onError) {
    this(load, refreshIntervalMs, onError, Executors.newVirtualThreadPerTaskExecutor());
  }

  public CatalogCache(Supplier<T> load, long refreshIntervalMs, Consumer<Throwable> onError, Executor executor) {
    this.load = load;
    this.refreshIntervalMs = refreshIntervalMs;
    this.onError = onError;
    this.executor = executor;
  }

  public synchronized CompletableFuture<T> refresh() {
    if (pending != null) return pending;
    CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
      for (;;) {
        long started;
        synchronized (this) { started = generation; }
        T next;
        try { next = load.get(); }
        catch (RuntimeException error) {
          synchronized (this) { if (started != generation) continue; }
          throw error;
        }
        synchronized (this) {
          if (started != generation) continue;
          snapshot = next;
          return next;
        }
      }
    }, executor);
    pending = future;
    future.whenComplete((value, error) -> { synchronized (this) { if (pending == future) pending = null; } });
    return future;
  }

  private void backgroundRefresh() {
    refresh().exceptionally(error -> {
      onError.accept(error instanceof CompletionException && error.getCause() != null ? error.getCause() : error);
      return null;
    });
  }

  public synchronized CompletableFuture<T> get() {
    return snapshot != null ? CompletableFuture.completedFuture(snapshot) : refresh();
  }

  /** Blocking read for request threads; unwraps the loader's exception. */
  public T current() {
    try { return get().join(); }
    catch (CompletionException error) {
      if (error.getCause() instanceof RuntimeException runtime) throw runtime;
      throw error;
    }
  }

  public void invalidate() {
    synchronized (this) {
      snapshot = null;
      generation++;
    }
    backgroundRefresh();
  }

  public synchronized void start() {
    if (timer == null) {
      scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "catalog-refresh");
        thread.setDaemon(true);
        return thread;
      });
      timer = scheduler.scheduleAtFixedRate(this::backgroundRefresh, refreshIntervalMs, refreshIntervalMs, TimeUnit.MILLISECONDS);
    }
    backgroundRefresh();
  }

  public synchronized void stop() {
    if (timer != null) timer.cancel(false);
    if (scheduler != null) scheduler.shutdownNow();
    timer = null;
    scheduler = null;
  }
}
