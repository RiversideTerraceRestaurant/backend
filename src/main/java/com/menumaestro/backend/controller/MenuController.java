package com.menumaestro.backend.controller;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.menumaestro.backend.client.AiClient;
import com.menumaestro.backend.client.supabase.PostgrestResult;
import com.menumaestro.backend.dto.MenuSchemas;
import com.menumaestro.backend.exception.ApiException;
import com.menumaestro.backend.security.ClientIp;
import com.menumaestro.backend.security.RateLimiter;
import com.menumaestro.backend.service.AuthService;
import com.menumaestro.backend.service.CatalogService;
import com.menumaestro.backend.util.Js;
import com.menumaestro.backend.util.Json;
import com.menumaestro.backend.util.RequestBodies;
import com.menumaestro.backend.util.Responses;
import com.menumaestro.backend.validation.Schema;
import com.menumaestro.backend.validation.Z;

import jakarta.servlet.http.HttpServletRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Public menu endpoints: health, categories, paginated menu, page and dish view tracking. */
@RestController
public class MenuController {
  static final java.util.Comparator<Object> COLLATOR = com.ibm.icu.text.Collator.getInstance(com.ibm.icu.util.ULocale.US).freeze();
  private static final Schema PAGE = Z.coerceNumber().integer().min(1).max(100000);
  private static final Schema PAGE_VIEW = Z.object().field("page_path", Z.string().max(200));

  private final CatalogService catalog;
  private final AuthService auth;
  private final AiClient ai;
  private final RateLimiter limiter;
  private final ClientIp clientIp;
  private final Map<String, Long> viewed = new ConcurrentHashMap<>();

  public MenuController(CatalogService catalog, AuthService auth, AiClient ai, RateLimiter limiter, ClientIp clientIp) {
    this.catalog = catalog;
    this.auth = auth;
    this.ai = ai;
    this.limiter = limiter;
    this.clientIp = clientIp;
  }

  @GetMapping("/api/health")
  public ResponseEntity<String> health() { return Responses.ok(Json.obj("ok", true, "aiConfigured", ai.configured())); }

  @GetMapping("/api/categories")
  public ResponseEntity<String> categories() { return Responses.ok(Json.obj("data", catalog.current().categories())); }

  @GetMapping("/api/menu")
  public ResponseEntity<String> menu(HttpServletRequest request) {
    List<ObjectNode> items = new ArrayList<>(catalog.current().items());
    String search = Js.slice(Js.lower(Js.trim(param(request, "search", ""))), 200);
    String category = request.getParameter("category");
    String sort = param(request, "sort", "default");
    if (!search.isEmpty()) items.removeIf(item -> !Js.lower(Json.str(item, "name", "") + " " + Json.str(item, "description", "")).contains(search));
    if (category != null && !category.isEmpty() && !"all".equals(category)) items.removeIf(item -> !category.equals(Json.str(item, "category_id")));
    Comparator<ObjectNode> order = switch (sort) {
      case "price-asc" -> Comparator.comparingDouble(item -> item.get("price").asDouble());
      case "price-desc" -> (a, b) -> Double.compare(b.get("price").asDouble(), a.get("price").asDouble());
      case "name-asc" -> (a, b) -> COLLATOR.compare(Json.str(a, "name", ""), Json.str(b, "name", ""));
      case "name-desc" -> (a, b) -> COLLATOR.compare(Json.str(b, "name", ""), Json.str(a, "name", ""));
      default -> (a, b) -> Json.str(a, "created_at", "").compareTo(Json.str(b, "created_at", ""));
    };
    items.sort(order);
    String rawPage = request.getParameter("page");
    int page = PAGE.parse(rawPage == null || rawPage.isEmpty() ? Json.num(1) : Json.node(rawPage)).asInt();
    int from = Math.min(items.size(), (page - 1) * 20);
    int to = Math.min(items.size(), page * 20);
    return Responses.ok(Json.obj("data", items.subList(from, to), "count", items.size()));
  }

  @PostMapping("/api/page-views")
  public ResponseEntity<String> pageView(HttpServletRequest request) {
    limiter.limit("page:" + clientIp.of(request), 20);
    JsonNode body = PAGE_VIEW.parse(RequestBodies.json(request));
    String agent = request.getHeader("user-agent") == null ? "" : request.getHeader("user-agent");
    auth.publicClient().from("page_views").insert(Json.obj("page_path", Json.str(body, "page_path"), "user_agent", Js.slice(agent, 500))).execute().unwrap();
    return Responses.ok(Json.obj("ok", true));
  }

  @PostMapping("/api/menu/{id}/views")
  public ResponseEntity<String> itemView(@PathVariable String id, HttpServletRequest request) {
    MenuSchemas.ID.parse(Json.node(id));
    String ip = clientIp.of(request);
    limiter.limit("views:" + ip, 30);
    String key = ip + ":" + id;
    if (viewed.getOrDefault(key, 0L) > System.currentTimeMillis()) return Responses.ok(Json.obj("ok", true));
    if (catalog.current().items().stream().noneMatch(item -> id.equals(Json.str(item, "id")))) throw new ApiException(404, "Dish not found.");
    PostgrestResult result = auth.publicClient().from("menu_item_views").insert(Json.obj("menu_item_id", id)).execute();
    // Tracking is optional until the migration is installed; menu browsing still works.
    if (result.failed()) return Responses.ok(Json.obj("ok", false, "trackingAvailable", false));
    viewed.put(key, System.currentTimeMillis() + 30000);
    if (viewed.size() > 5000) viewed.values().removeIf(until -> until < System.currentTimeMillis());
    return Responses.ok(Json.obj("ok", true));
  }

  private static String param(HttpServletRequest request, String name, String fallback) {
    String value = request.getParameter(name);
    return value == null || value.isEmpty() ? fallback : value;
  }
}
