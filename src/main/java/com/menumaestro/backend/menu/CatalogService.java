package com.menumaestro.backend.menu;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.menumaestro.backend.auth.AuthService;
import com.menumaestro.backend.common.json.Json;
import com.menumaestro.backend.common.web.ApiException;
import com.menumaestro.backend.supabase.PostgrestResult;
import com.menumaestro.backend.supabase.SupabaseClient;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The in-memory text catalog shared by public menu reads and AI retrieval. Only scalar dish
 * data and image URL strings are cached; it is preloaded at startup, refreshed every five
 * minutes and invalidated after admin writes.
 */
@Service
public class CatalogService {
  private static final Logger log = LoggerFactory.getLogger(CatalogService.class);

  public static final List<String> ITEM_FIELDS;
  static {
    List<String> fields = new ArrayList<>(List.of("id", "name", "description"));
    fields.addAll(MenuSchemas.TRANSLATION_KEYS);
    fields.addAll(List.of("price", "vat", "category_id", "badges", "image_url", "thumbnail_url", "created_at", "updated_at"));
    ITEM_FIELDS = List.copyOf(fields);
  }
  public static final List<String> CATEGORY_FIELDS = List.of("id", "name", "display_name", "display_order", "is_active", "created_at", "updated_at");

  /** Catalog snapshot. counts are the menu_item_view_counts rows. */
  public record Catalog(List<ObjectNode> categories, List<ObjectNode> items, String catalogVersion, List<JsonNode> counts, boolean popularityAvailable) {
    public Catalog withoutPopularity() { return new Catalog(categories, items, catalogVersion, List.of(), false); }
  }

  public record ViewCounts(List<JsonNode> counts, boolean popularityAvailable) {}

  private final SupabaseClient publicClient;
  private final CatalogCache<Catalog> cache;

  public CatalogService(AuthService auth) {
    this.publicClient = auth.publicClient();
    this.cache = new CatalogCache<>(this::load, 300000,
      error -> log.warn(Json.stringify(Json.obj("event", "catalog.refresh_failed", "status", error instanceof ApiException api ? api.status() : 503))));
  }

  /** Paginates server-side to avoid Supabase's default 1000-row cap. */
  public static List<ObjectNode> allItems(SupabaseClient client, String columns) {
    List<ObjectNode> items = new ArrayList<>();
    for (int from = 0; ; from += 500) {
      JsonNode page = client.from("menu_items").select(columns).order("id").range(from, from + 499).execute().unwrap();
      for (JsonNode row : page) items.add((ObjectNode) row);
      if (page.size() < 500) return items;
    }
  }

  public static ViewCounts countsFor(SupabaseClient client) {
    PostgrestResult result = client.rpc("get_menu_item_view_counts", null);
    List<JsonNode> counts = result.failed() || result.data() == null ? List.of() : Json.list(result.data());
    boolean available = !result.failed() && counts.stream().anyMatch(row -> Json.get(row, "view_count") != null && row.get("view_count").asDouble(0) > 0);
    return new ViewCounts(counts, available);
  }

  private static ObjectNode pick(JsonNode row, List<String> fields) {
    ObjectNode out = Json.obj();
    for (String field : fields) if (row.has(field)) out.set(field, row.get(field));
    return out;
  }

  Catalog load() {
    CompletableFuture<PostgrestResult> categoryResult = CompletableFuture.supplyAsync(() ->
      publicClient.from("categories").select(String.join(",", CATEGORY_FIELDS)).eq("is_active", true).order("display_order").execute(), Async.EXECUTOR);
    CompletableFuture<List<ObjectNode>> rows = CompletableFuture.supplyAsync(() -> allItems(publicClient, String.join(",", ITEM_FIELDS)), Async.EXECUTOR);
    CompletableFuture<ViewCounts> popularity = CompletableFuture.supplyAsync(() -> countsFor(publicClient), Async.EXECUTOR)
      .exceptionally(error -> new ViewCounts(List.of(), false));
    List<ObjectNode> categories = new ArrayList<>();
    for (JsonNode category : Async.join(categoryResult).unwrap()) categories.add(pick(category, CATEGORY_FIELDS));
    Set<String> active = new HashSet<>();
    for (ObjectNode category : categories) active.add(Json.str(category, "id"));
    List<ObjectNode> items = new ArrayList<>();
    for (ObjectNode item : Async.join(rows)) if (active.contains(Json.str(item, "category_id"))) items.add(pick(item, ITEM_FIELDS));
    String version = sha256(Json.stringify(Json.arrOf(Json.arr(categories), Json.arr(items))));
    ViewCounts counts = Async.join(popularity);
    return new Catalog(List.copyOf(categories), List.copyOf(items), version, counts.counts(), counts.popularityAvailable());
  }

  public static String sha256(String text) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
    catch (Exception error) { throw new IllegalStateException(error); }
  }

  public Catalog current() { return cache.current(); }

  public void invalidate() { cache.invalidate(); }

  @EventListener(ApplicationReadyEvent.class)
  public void start() { cache.start(); }

  @PreDestroy
  public void stop() { cache.stop(); }
}
