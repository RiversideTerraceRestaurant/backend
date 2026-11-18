package com.menumaestro.backend.menu;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Promise.all-style helpers on virtual threads for parallel Supabase calls. */
public final class Async {
  public static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

  private Async() {}

  /** Awaits a future, rethrowing the original runtime exception. */
  public static <T> T join(CompletableFuture<T> future) {
    try { return future.join(); }
    catch (CompletionException error) {
      if (error.getCause() instanceof RuntimeException runtime) throw runtime;
      if (error.getCause() instanceof Error fatal) throw fatal;
      throw error;
    }
  }
}
