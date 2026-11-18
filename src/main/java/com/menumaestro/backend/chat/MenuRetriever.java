package com.menumaestro.backend.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.menumaestro.backend.common.json.Js;
import com.menumaestro.backend.common.json.Json;
import com.menumaestro.backend.menu.CatalogService;
import com.menumaestro.backend.menu.CatalogService.Catalog;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Catalog retrieval with an ID-only query cache and narrowing of the previous search
 * (menu-retrieval.js). Every response is hydrated from the current catalog snapshot.
 */
public class MenuRetriever {
  public record Subject(String value, List<String> categoryIds) {
    JsonNode toJson() { return Json.obj("value", value, "categoryIds", categoryIds); }
  }

  /** Canonical retrieval query; {@link #key()} is the cache key. */
  public record Query(List<String> diets, double maxPrice, boolean mild, List<String> avoid, List<Subject> subjects,
                      List<String> keywords, String kind, boolean includeKids, String drinkType, String alcohol) {
    String key() {
      return Json.stringify(Json.obj("diets", diets, "maxPrice", Double.isFinite(maxPrice) ? Json.num(maxPrice) : Json.F.nullNode(), "mild", mild,
        "avoid", avoid, "subjects", subjects.stream().map(Subject::toJson).toList(), "keywords", keywords, "kind", kind == null ? Json.UNDEFINED : kind,
        "includeKids", includeKids, "drinkType", drinkType, "alcohol", alcohol));
    }
  }

  private record Document(ObjectNode item, String text, MenuFilters.BeverageProfile beverage, boolean drink, boolean dessert, boolean kids) {}

  public record Result(List<ObjectNode> items, ObjectNode retrieval, ChatState state) {}

  private final int maxQueries;
  private String version;
  private Map<String, Document> documents = new LinkedHashMap<>();
  private LinkedHashMap<String, List<String>> queries = new LinkedHashMap<>();

  public MenuRetriever() { this(200); }

  public MenuRetriever(int maxQueries) { this.maxQueries = maxQueries; }

  private static List<String> sortedUnique(List<String> values) { return new ArrayList<>(new TreeSet<>(values)); }

  static Query queryFor(JsonNode intent, JsonNode preferences) {
    List<String> diets = new ArrayList<>();
    for (String diet : new String[] {Json.str(intent, "diet"), Json.str(preferences, "diet")}) if (!"any".equals(diet) && diet != null) diets.add(diet);
    double maxPrice = Math.min(numberOr(Json.get(intent, "maxPrice")), numberOr(Json.get(preferences, "maxPrice")));
    boolean mild = Json.truthy(Json.get(intent, "mild")) || Json.truthy(Json.get(preferences, "mild"));
    List<String> avoid = new ArrayList<>();
    for (String term : Json.strings(intent, "avoid")) avoid.add(MenuFilters.normalize(term));
    for (String term : Json.strings(preferences, "avoid")) avoid.add(MenuFilters.normalize(term));
    List<Subject> subjects = new ArrayList<>();
    for (JsonNode subject : Json.list(Json.get(intent, "subjects"))) {
      subjects.add(new Subject(MenuFilters.normalize(Json.str(subject, "value")), sortedUnique(Json.strings(subject, "categoryIds"))));
    }
    subjects.sort((a, b) -> COLLATOR.compare(a.value(), b.value()));
    List<String> keywords = new ArrayList<>();
    for (String word : Json.strings(intent, "keywords")) keywords.add(MenuFilters.normalize(word));
    String drinkType = Json.str(intent, "drinkType");
    String alcohol = Json.str(intent, "alcohol");
    return new Query(sortedUnique(diets), maxPrice, mild, sortedUnique(avoid), subjects, sortedUnique(keywords), Json.str(intent, "kind"),
      Json.truthy(Json.get(intent, "includeKids")), drinkType == null || drinkType.isEmpty() ? "any" : drinkType, alcohol == null || alcohol.isEmpty() ? "any" : alcohol);
  }

  private static final java.util.Comparator<Object> COLLATOR = com.ibm.icu.text.Collator.getInstance(com.ibm.icu.util.ULocale.US).freeze();

  private static double numberOr(JsonNode value) { return value == null || value.isNull() || !value.isNumber() ? Double.POSITIVE_INFINITY : value.doubleValue(); }

  static boolean narrows(Query next, Query prior) {
    return next.keywords().equals(prior.keywords())
      && next.subjects().equals(prior.subjects())
      && java.util.Objects.equals(next.kind(), prior.kind()) && next.includeKids() == prior.includeKids()
      && ("any".equals(prior.drinkType()) || next.drinkType().equals(prior.drinkType()))
      && ("any".equals(prior.alcohol()) || next.alcohol().equals(prior.alcohol()))
      && next.diets().containsAll(prior.diets())
      && next.maxPrice() <= prior.maxPrice() && (!prior.mild() || next.mild())
      && next.avoid().containsAll(prior.avoid());
  }

  /**
   * @param previous the conversation state, or null
   * @param forceCatalog skip previous references, cache and narrowing
   */
  public synchronized Result retrieve(Catalog catalog, JsonNode intent, JsonNode preferences, ChatState previous, boolean forceCatalog) {
    List<ObjectNode> items = catalog.items();
    List<ObjectNode> categories = catalog.categories();
    String nextVersion = catalog.catalogVersion() != null ? catalog.catalogVersion()
      : CatalogService.sha256(Json.stringify(Json.arrOf(Json.arr(items), Json.arr(categories))));
    if (!nextVersion.equals(version)) {
      version = nextVersion;
      Map<String, String> names = new HashMap<>();
      for (ObjectNode category : categories) names.put(Json.str(category, "id"), MenuFilters.normalize(MenuFilters.tpl(category.get("name")) + " " + MenuFilters.tpl(category.get("display_name"))));
      Map<String, Document> docs = new LinkedHashMap<>();
      for (ObjectNode item : items) {
        String category = names.getOrDefault(Json.str(item, "category_id"), "");
        StringBuilder translations = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, JsonNode> entry : item.properties()) {
          if (!entry.getKey().startsWith("description_")) continue;
          if (!first) translations.append(' ');
          first = false;
          JsonNode value = entry.getValue();
          translations.append(Json.truthy(value) ? MenuFilters.tpl(value) : "");
        }
        String text = MenuFilters.normalize(MenuFilters.tpl(item.get("name")) + " " + MenuFilters.tpl(item.get("description")) + " "
          + String.join(" ", Json.strings(item, "badges")) + " " + translations + " " + category);
        docs.put(Json.str(item, "id"), new Document(item, text, MenuFilters.beverageProfile(item, category),
          Js.test("drink|cocktail|mocktail|beer|wine|coffee|spirit|beverage|juice|tea\\b", category),
          Js.test("dessert|sweet", category), Js.test("kids?|children", category)));
      }
      documents = docs;
      queries = new LinkedHashMap<>();
    }
    Query query = queryFor(intent, preferences);
    String key = query.key();
    List<String> ids = null;
    String strategy = "catalog";
    int inspected = 0;
    List<String> references = Json.strings(intent, "referenceIds");
    ChatState.Search search = null;
    if (previous != null) {
      if (Boolean.TRUE.equals(previous.exhaustive)) search = new ChatState.Search(previous.catalogVersion, previous.query, previous.candidateIds);
      else search = previous.search;
    }
    if (!forceCatalog && "previous".equals(Json.str(intent, "scope")) && previous != null && previous.selectedIds != null && !previous.selectedIds.isEmpty()
      && previous.selectedIds.containsAll(references)) {
      // An empty reference list means all dishes just shown (e.g. a comparison).
      ids = references.isEmpty() ? previous.selectedIds : references;
      strategy = "previous";
    } else if (!forceCatalog && queries.containsKey(key)) {
      ids = queries.remove(key);
      queries.put(key, ids);
      strategy = "cache";
    } else if (!forceCatalog && search != null && version.equals(search.catalogVersion()) && search.query() != null && narrows(query, search.query())) {
      ids = search.candidateIds();
      strategy = "narrowed";
    }
    LinkedHashMap<String, Integer> rejected = new LinkedHashMap<>();
    for (String reason : List.of("kids", "beverageType", "alcohol", "kind", "subjects", "keywords", "preferences")) rejected.put(reason, 0);
    if (!"cache".equals(strategy)) {
      List<Document> pool = new ArrayList<>();
      if (ids != null) { for (String id : ids) { Document doc = documents.get(id); if (doc != null) pool.add(doc); } }
      else pool.addAll(documents.values());
      inspected = pool.size();
      List<String> matched = new ArrayList<>();
      for (Document doc : pool) {
        String reason = reject(doc, query, categories, strategy, intent, preferences);
        if (reason != null) rejected.merge(reason, 1, Integer::sum);
        else matched.add(Json.str(doc.item(), "id"));
      }
      ids = matched;
      if (!"previous".equals(strategy)) {
        queries.put(key, ids);
        if (queries.size() > maxQueries) queries.remove(queries.keySet().iterator().next());
      }
    }
    List<ObjectNode> result = new ArrayList<>();
    for (String id : ids) result.add(documents.get(id).item());
    ObjectNode retrieval = Json.obj("strategy", strategy, "inspected", inspected, "matched", ids.size(), "rejected", rejected, "verifiedCatalog", forceCatalog);
    ChatState state = new ChatState();
    state.catalogVersion = version;
    state.query = query;
    state.candidateIds = List.copyOf(ids);
    state.intent = (ObjectNode) intent;
    state.exhaustive = !"previous".equals(strategy);
    // Keep the last full search across detail questions without nesting conversation states.
    if ("previous".equals(strategy) && search != null) state.search = new ChatState.Search(search.catalogVersion(), search.query(), search.candidateIds());
    return new Result(result, retrieval, state);
  }

  private static String reject(Document doc, Query query, List<ObjectNode> categories, String strategy, JsonNode intent, JsonNode preferences) {
    if (!query.includeKids() && doc.kids()) return "kids";
    if (!"any".equals(query.drinkType()) && !doc.beverage().types().contains(query.drinkType())) return "beverageType";
    if (doc.drink() && !"any".equals(query.alcohol()) && !doc.beverage().alcohol().equals(query.alcohol())) return "alcohol";
    if (!categories.isEmpty() && !"any".equals(query.kind())) {
      boolean reject = "drink".equals(query.kind()) ? !doc.drink() : "dessert".equals(query.kind()) ? !doc.dessert() : doc.drink() || doc.dessert();
      if (reject) return "kind";
    }
    if (!"previous".equals(strategy) && !query.subjects().isEmpty()
      && query.subjects().stream().noneMatch(subject -> MenuSubjects.matchesMenuSubject(doc.item(), subject.toJson(), doc.text()))) return "subjects";
    // A prior-item question is resolved by ID, so keywords need not repeat the dish name.
    if (!"previous".equals(strategy) && !query.keywords().isEmpty() && !query.keywords().stream().allMatch(word -> doc.text().contains(word))) return "keywords";
    List<ObjectNode> single = List.of(doc.item());
    return MenuFilters.filterItems(MenuFilters.filterItems(single, preferences), intent).isEmpty() ? "preferences" : null;
  }

  static ArrayNode ids(List<String> values) { return Json.strArray(values); }
}
