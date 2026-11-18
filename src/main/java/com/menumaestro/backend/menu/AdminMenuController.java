package com.menumaestro.backend.menu;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.menumaestro.backend.ai.AiClient;
import com.menumaestro.backend.auth.AuthService;
import com.menumaestro.backend.auth.AuthService.UserSession;
import com.menumaestro.backend.common.json.Js;
import com.menumaestro.backend.common.json.Json;
import com.menumaestro.backend.common.validation.Schema;
import com.menumaestro.backend.common.validation.Z;
import com.menumaestro.backend.common.web.RateLimiter;
import com.menumaestro.backend.common.web.RequestBodies;
import com.menumaestro.backend.common.web.Responses;
import com.menumaestro.backend.supabase.PostgrestQuery;
import com.menumaestro.backend.supabase.PostgrestResult;
import com.menumaestro.backend.supabase.SupabaseClient;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Admin menu management. Every route runs with the admin's Supabase session (RLS). */
@RestController
@RequestMapping("/api/admin")
public class AdminMenuController {
  private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
  private static final Schema VAT = Z.object()
    .field("ids", Z.array(MenuSchemas.id()).min(1).max(1000))
    .field("vat", Z.number().finite().min(0).max(100));
  private static final Schema TRANSLATE = Z.union(
    Z.object().field("description", Z.string().trim().min(1).max(5000)),
    Z.object().field("descriptions", Z.array(Z.string().trim().min(1).max(5000)).min(1).max(5)));
  private static final Schema JOB = Z.object().field("type", Z.enumOf("optimize", "auto-fix"));

  private final CatalogService catalog;
  private final AiClient ai;
  private final AiQuota quota;
  private final ImageService images;
  private final MaintenanceJobService jobs;
  private final RateLimiter limiter;

  public AdminMenuController(CatalogService catalog, AiClient ai, AiQuota quota, ImageService images, MaintenanceJobService jobs, RateLimiter limiter) {
    this.catalog = catalog;
    this.ai = ai;
    this.quota = quota;
    this.images = images;
    this.jobs = jobs;
    this.limiter = limiter;
  }

  private static UserSession admin(HttpServletRequest request) { return AuthService.currentAdmin(request); }

  @GetMapping("/menu")
  public ResponseEntity<String> items(HttpServletRequest request) {
    List<ObjectNode> items = new ArrayList<>(CatalogService.allItems(admin(request).client(), "*"));
    String search = Js.slice(Js.lower(Js.trim(request.getParameter("search") == null ? "" : request.getParameter("search"))), 200);
    String category = request.getParameter("category");
    String badge = request.getParameter("badge");
    if (!search.isEmpty()) items.removeIf(item -> !Js.lower(Json.str(item, "name", "") + " " + Json.str(item, "description", "")).contains(search));
    if (category != null && !category.isEmpty() && !"all".equals(category)) items.removeIf(item -> !category.equals(Json.str(item, "category_id")));
    if (badge != null && !badge.isEmpty() && !"all".equals(badge)) items.removeIf(item -> !Json.strings(item, "badges").contains(badge));
    String sort = request.getParameter("sort") == null || request.getParameter("sort").isEmpty() ? "name" : request.getParameter("sort");
    Comparator<ObjectNode> order = switch (sort) {
      case "price_asc" -> Comparator.comparingDouble(item -> item.get("price").asDouble());
      case "price_desc" -> (a, b) -> Double.compare(b.get("price").asDouble(), a.get("price").asDouble());
      case "newest" -> (a, b) -> Json.str(b, "created_at", "").compareTo(Json.str(a, "created_at", ""));
      default -> (a, b) -> MenuController.COLLATOR.compare(Json.str(a, "name", ""), Json.str(b, "name", ""));
    };
    items.sort(order);
    return Responses.ok(Json.obj("data", items));
  }

  @GetMapping("/categories")
  public ResponseEntity<String> categories(HttpServletRequest request) {
    return Responses.ok(Json.obj("data", admin(request).client().from("categories").select("*").order("display_order").execute().unwrap()));
  }

  @PostMapping("/menu")
  public ResponseEntity<String> createItem(HttpServletRequest request) { return saveItem(request, null); }

  @PatchMapping("/menu/{id}")
  public ResponseEntity<String> updateItem(@PathVariable String id, HttpServletRequest request) { return saveItem(request, id); }

  private ResponseEntity<String> saveItem(HttpServletRequest request, String id) {
    SupabaseClient client = admin(request).client();
    ObjectNode body = (ObjectNode) MenuSchemas.ITEM.parse(RequestBodies.json(request));
    String warning = null;
    JsonNode existing = id == null ? null
      : client.from("menu_items").select("*").eq("id", MenuSchemas.ID.parse(Json.node(id)).stringValue()).single().execute().unwrap();
    String description = Json.str(body, "description", "");
    boolean changed = existing == null || !description.equals(Json.str(existing, "description"));
    if (!Js.trim(description).isEmpty() && changed && MenuSchemas.TRANSLATION_KEYS.stream().anyMatch(key -> blank(body, key))) {
      try {
        quota.use();
        ObjectNode translations = ai.translate(List.of(description)).get(0);
        for (String key : MenuSchemas.TRANSLATION_KEYS) if (blank(body, key)) body.set(key, translations.get(key));
      } catch (RuntimeException error) {
        warning = "Auto-translation is unavailable. The item was saved with the supplied descriptions.";
      }
    }
    PostgrestQuery query = client.from("menu_items");
    query = id != null ? query.update(body).eq("id", id) : query.insert(body);
    JsonNode data = query.select().single().execute().unwrap();
    catalog.invalidate();
    return Responses.status(id != null ? 200 : 201, Json.obj("data", data, "warning", warning == null ? Json.UNDEFINED : warning));
  }

  @DeleteMapping("/menu/{id}")
  public ResponseEntity<String> deleteItem(@PathVariable String id, HttpServletRequest request) {
    admin(request).client().from("menu_items").delete().eq("id", MenuSchemas.ID.parse(Json.node(id)).stringValue()).execute().unwrap();
    catalog.invalidate();
    return Responses.ok(Json.obj("ok", true));
  }

  @PostMapping("/vat")
  public ResponseEntity<String> vat(HttpServletRequest request) {
    JsonNode body = VAT.parse(RequestBodies.json(request));
    admin(request).client().from("menu_items").update(Json.obj("vat", body.get("vat"))).in("id", Json.strings(body, "ids")).execute().unwrap();
    catalog.invalidate();
    return Responses.ok(Json.obj("ok", true));
  }

  @PostMapping("/categories")
  public ResponseEntity<String> createCategory(HttpServletRequest request) { return saveCategory(request, null); }

  @PatchMapping("/categories/{id}")
  public ResponseEntity<String> updateCategory(@PathVariable String id, HttpServletRequest request) { return saveCategory(request, id); }

  private ResponseEntity<String> saveCategory(HttpServletRequest request, String id) {
    JsonNode input = RequestBodies.json(request);
    ObjectNode body = (ObjectNode) (id != null ? MenuSchemas.CATEGORY.partial().parse(input) : MenuSchemas.CATEGORY.parse(input));
    String name = Json.str(body, "name");
    if (name != null && !name.isEmpty()) body.put("name", Js.replaceAll(Js.lower(name), "\\s+", "_"));
    PostgrestQuery query = admin(request).client().from("categories");
    query = id != null ? query.update(body).eq("id", MenuSchemas.ID.parse(Json.node(id)).stringValue()) : query.insert(body);
    JsonNode data = query.select().single().execute().unwrap();
    catalog.invalidate();
    return Responses.ok(Json.obj("data", data));
  }

  @DeleteMapping("/categories/{id}")
  public ResponseEntity<String> deleteCategory(@PathVariable String id, HttpServletRequest request) {
    admin(request).client().from("categories").delete().eq("id", MenuSchemas.ID.parse(Json.node(id)).stringValue()).execute().unwrap();
    catalog.invalidate();
    return Responses.ok(Json.obj("ok", true));
  }

  @GetMapping("/analytics")
  public ResponseEntity<String> analytics(HttpServletRequest request) {
    SupabaseClient client = admin(request).client();
    // Restaurant reporting day begins at midnight in Vietnam (UTC+7).
    long dayStart = Math.floorDiv(System.currentTimeMillis() + 7 * 3600000L, 86400000L) * 86400000L - 7 * 3600000L;
    CompletableFuture<PostgrestResult> total = CompletableFuture.supplyAsync(() -> client.from("page_views").select("*", true, true).execute(), Async.EXECUTOR);
    CompletableFuture<PostgrestResult> today = CompletableFuture.supplyAsync(() ->
      client.from("page_views").select("*", true, true).gte("viewed_at", ISO.format(Instant.ofEpochMilli(dayStart))).execute(), Async.EXECUTOR);
    CompletableFuture<CatalogService.ViewCounts> views = CompletableFuture.supplyAsync(() -> CatalogService.countsFor(client), Async.EXECUTOR);
    PostgrestResult totalResult = Async.join(total);
    PostgrestResult todayResult = Async.join(today);
    CatalogService.ViewCounts counts = Async.join(views);
    totalResult.unwrap();
    todayResult.unwrap();
    List<ObjectNode> items = CatalogService.allItems(client, "*");
    List<JsonNode> sorted = new ArrayList<>(counts.counts());
    sorted.sort((a, b) -> Double.compare(b.get("view_count").asDouble(0), a.get("view_count").asDouble(0)));
    ArrayNode top = Json.arr();
    for (JsonNode row : sorted.subList(0, Math.min(5, sorted.size()))) {
      ObjectNode entry = Json.copy(row);
      String name = items.stream().filter(item -> Json.str(item, "id", "").equals(Json.str(row, "menu_item_id"))).map(item -> Json.str(item, "name")).findFirst().orElse(null);
      entry.put("name", name == null || name.isEmpty() ? "Deleted dish" : name);
      top.add(entry);
    }
    return Responses.ok(Json.obj("totalViews", totalResult.count() == null ? 0 : totalResult.count(), "todayViews", todayResult.count() == null ? 0 : todayResult.count(),
      "trackingAvailable", counts.popularityAvailable(), "topItems", top));
  }

  @PostMapping("/translate")
  public ResponseEntity<String> translate(HttpServletRequest request) {
    limiter.limit("translate:" + admin(request).userId(), 10);
    quota.use();
    JsonNode body = TRANSLATE.parse(RequestBodies.json(request));
    boolean many = Json.has(body, "descriptions");
    List<ObjectNode> result = ai.translate(many ? Json.strings(body, "descriptions") : List.of(Json.str(body, "description")));
    return Responses.ok(many ? Json.obj("items", result) : result.get(0));
  }

  @PostMapping("/images")
  public ResponseEntity<String> uploadImage(HttpServletRequest request) {
    limiter.limit("upload:" + admin(request).userId(), 30);
    byte[] bytes = RequestBodies.raw(request, 15 * 1024 * 1024);
    return Responses.created(images.uploadImages(admin(request).client(), bytes));
  }

  @GetMapping("/jobs/{id}")
  public ResponseEntity<String> job(@PathVariable String id, HttpServletRequest request) {
    return Responses.ok(jobs.get(id, admin(request).userId()));
  }

  @PostMapping("/jobs")
  public ResponseEntity<String> startJob(HttpServletRequest request) {
    String type = Json.str(JOB.parse(RequestBodies.json(request)), "type");
    return Responses.status(202, jobs.start(type, admin(request).userId(), admin(request).client()));
  }

  private static boolean blank(JsonNode item, String key) {
    String value = Json.str(item, key);
    return value == null || Js.trim(value).isEmpty();
  }
}
