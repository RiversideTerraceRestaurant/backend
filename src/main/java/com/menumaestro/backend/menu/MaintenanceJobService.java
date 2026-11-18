package com.menumaestro.backend.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.menumaestro.backend.ai.AiClient;
import com.menumaestro.backend.common.json.Js;
import com.menumaestro.backend.common.json.Json;
import com.menumaestro.backend.common.web.ApiException;
import com.menumaestro.backend.config.AppConfig;
import com.menumaestro.backend.supabase.SupabaseClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Background maintenance (image optimization and missing-translation auto-fix), one at a time, kept in memory. */
@Service
public class MaintenanceJobService {
  /** Mutable job state; serialized under its own lock while the worker updates it. */
  public static final class Job {
    final String id;
    final String owner;
    final String type;
    final long startedAt;
    String status = "running";
    int current;
    int total;
    int updated;
    int skipped;
    final List<String> errors = new ArrayList<>();

    Job(String owner, String type) {
      this.id = UUID.randomUUID().toString();
      this.owner = owner;
      this.type = type;
      this.startedAt = System.currentTimeMillis();
    }

    public synchronized ObjectNode toJson() {
      return Json.obj("id", id, "owner", owner, "type", type, "status", status, "startedAt", startedAt,
        "current", current, "total", total, "updated", updated, "skipped", skipped, "errors", List.copyOf(errors));
    }

    synchronized void error(String message) { errors.add(message); }
  }

  private final Map<String, Job> jobs = new ConcurrentHashMap<>();
  private final AiClient ai;
  private final AiQuota quota;
  private final ImageService images;
  private final CatalogService catalog;
  private final AppConfig config;

  public MaintenanceJobService(AiClient ai, AiQuota quota, ImageService images, CatalogService catalog, AppConfig config) {
    this.ai = ai;
    this.quota = quota;
    this.images = images;
    this.catalog = catalog;
    this.config = config;
  }

  public ObjectNode get(String id, String userId) {
    Job job = jobs.get(id);
    if (job == null || !job.owner.equals(userId)) throw new ApiException(404, "Job not found.");
    return job.toJson();
  }

  public synchronized ObjectNode start(String type, String userId, SupabaseClient client) {
    jobs.values().removeIf(job -> System.currentTimeMillis() - job.startedAt > 3600000 && !"running".equals(job.status));
    if (jobs.values().stream().anyMatch(job -> "running".equals(job.status))) throw new ApiException(409, "A maintenance task is already running.");
    Job job = new Job(userId, type);
    jobs.put(job.id, job);
    ObjectNode snapshot = job.toJson();
    Thread.ofVirtual().name("maintenance-" + job.id).start(() -> run(job, client));
    return snapshot;
  }

  private void run(Job job, SupabaseClient client) {
    try {
      List<ObjectNode> items = CatalogService.allItems(client, "*");
      synchronized (job) { job.total = items.size(); }
      for (ObjectNode item : items) {
        boolean changed = false;
        String name = Json.str(item, "name");
        try {
          String description = Json.str(item, "description", "");
          if ("auto-fix".equals(job.type) && !Js.trim(description).isEmpty() && MenuSchemas.TRANSLATION_KEYS.stream().anyMatch(key -> blank(item, key))) {
            quota.use();
            ObjectNode translated = ai.translate(List.of(description)).get(0);
            ObjectNode update = Json.obj();
            for (String key : MenuSchemas.TRANSLATION_KEYS) if (blank(item, key)) update.set(key, translated.get(key));
            client.from("menu_items").update(update).eq("id", Json.str(item, "id")).execute().unwrap();
            changed = true;
          }
        } catch (RuntimeException error) { job.error(name + ": " + error.getMessage()); }
        try { changed = images.optimizeItem(client, item, config.supabaseUrl()) != null || changed; }
        catch (RuntimeException error) { job.error(name + ": " + error.getMessage()); }
        synchronized (job) {
          if (changed) job.updated++; else job.skipped++;
          job.current++;
        }
      }
      synchronized (job) { job.status = "complete"; }
    } catch (RuntimeException error) {
      synchronized (job) { job.status = "failed"; job.errors.add(error.getMessage()); }
    } finally {
      catalog.invalidate();
    }
  }

  private static boolean blank(JsonNode item, String key) {
    String value = Json.str(item, key);
    return value == null || Js.trim(value).isEmpty();
  }
}
