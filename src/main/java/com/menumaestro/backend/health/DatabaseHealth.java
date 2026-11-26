package com.menumaestro.backend.health;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.menumaestro.backend.auth.AuthService;
import com.menumaestro.backend.common.json.Json;
import com.menumaestro.backend.common.web.Responses;
import com.menumaestro.backend.supabase.PostgrestResult;
import com.menumaestro.backend.supabase.SupabaseClient;

import jakarta.annotation.PreDestroy;

/**
 * Supabase keep-alive and database health check.
 *
 * <ul>
 *   <li>Once a day the server runs a tiny query against Supabase, so a free-tier project is never
 *       paused for inactivity even if nobody visits the site.</li>
 *   <li>{@code GET /api/health/db} runs the same query (at most every 30 seconds; repeated calls
 *       reuse the last result) and answers 200 when the database responds or 503 when it does not,
 *       for an external uptime monitor.</li>
 * </ul>
 */
@RestController
public class DatabaseHealth {
  private static final Logger log = LoggerFactory.getLogger(DatabaseHealth.class);
  private static final long CACHE_MS = 30_000;

  public record Check(boolean ok, long latencyMs, Instant checkedAt, String error) {}

  private final SupabaseClient db;
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("supabase-keepalive").factory());
  private volatile Check last;

  public DatabaseHealth(AuthService auth) { this.db = auth.publicClient(); }

  @EventListener(ApplicationReadyEvent.class)
  void start() {
    // First ping shortly after start, then every 24 hours.
    scheduler.scheduleAtFixedRate(() -> {
      Check check = ping();
      log.info(Json.stringify(Json.obj("event", "supabase.keepalive", "ok", check.ok(), "latencyMs", check.latencyMs(),
        "error", check.error() == null ? Json.UNDEFINED : check.error())));
    }, 1, TimeUnit.DAYS.toMinutes(1), TimeUnit.MINUTES);
  }

  /** Runs a one-row read of the public categories table. Never throws. */
  public Check ping() {
    long start = System.nanoTime();
    Check check;
    try {
      PostgrestResult result = db.from("categories").select("id").limit(1).execute();
      long latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      check = result.failed()
        ? new Check(false, latency, Instant.now(), String.valueOf(result.error().message()))
        : new Check(true, latency, Instant.now(), null);
    } catch (RuntimeException error) {
      check = new Check(false, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), Instant.now(), String.valueOf(error.getMessage()));
    }
    last = check;
    return check;
  }

  @GetMapping("/api/health/db")
  public ResponseEntity<String> health() {
    Check check = last;
    if (check == null || check.checkedAt().isBefore(Instant.now().minusMillis(CACHE_MS))) check = ping();
    return Responses.status(check.ok() ? 200 : 503, Json.obj("ok", check.ok(), "db", check.ok() ? "ok" : "down",
      "latencyMs", check.latencyMs(), "checkedAt", check.checkedAt().toString()));
  }

  @PreDestroy
  void stop() { scheduler.shutdownNow(); }
}
