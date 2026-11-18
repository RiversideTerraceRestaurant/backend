package com.menumaestro.backend.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import com.menumaestro.backend.ai.AiClient;
import com.menumaestro.backend.common.json.Js;
import com.menumaestro.backend.common.json.Json;
import com.menumaestro.backend.common.validation.ValidationException;
import com.menumaestro.backend.common.web.ApiException;
import com.menumaestro.backend.menu.CatalogService.Catalog;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Menu-grounded recommendations for one chat turn (recommend() in recommendations.js):
 * intent extraction with evidence checks, catalog retrieval, Special/price ranking,
 * requested-group coverage and an AI-written description of exactly the selected dishes.
 */
public final class RecommendationService {
  /** Answers restaurant questions: (memory, questions, language) -> {reply, items, sources, mode}. */
  public interface RestaurantAnswerer { ObjectNode answer(JsonNode memory, List<String> questions, String language); }

  private final AiClient ai;
  private final MenuRetriever retriever;
  private final Function<Boolean, Catalog> getCatalog;
  private final RestaurantAnswerer restaurant;
  private final Consumer<ChatState> onState;
  private final Consumer<ObjectNode> onDiagnostic;

  // Per-turn mutable variables of the original closure.
  private ObjectNode input;
  private RecommendationPrompts.Copy strings;
  private JsonNode memory;
  private List<String> questions;

  public RecommendationService(AiClient ai, MenuRetriever retriever, Function<Boolean, Catalog> getCatalog, RestaurantAnswerer restaurant,
                               Consumer<ChatState> onState, Consumer<ObjectNode> onDiagnostic) {
    this.ai = ai;
    this.retriever = retriever;
    this.getCatalog = getCatalog;
    this.restaurant = restaurant;
    this.onState = onState;
    this.onDiagnostic = onDiagnostic;
  }

  private static RecommendationPrompts.Copy copyFor(String language) {
    return RecommendationPrompts.COPY.getOrDefault(language, RecommendationPrompts.COPY.get("en"));
  }

  private String language() { return Json.str(input, "language"); }

  private String lastContent() {
    JsonNode messages = input.get("messages");
    return Json.str(messages.get(messages.size() - 1), "content");
  }

  private ObjectNode restaurantReply() {
    ObjectNode answer = Json.copy(restaurant.answer(memory, questions, language()));
    answer.put("language", language());
    return answer;
  }

  private static ObjectNode spread(JsonNode base, Object... pairs) {
    ObjectNode copy = Json.copy(base);
    for (int i = 0; i < pairs.length; i += 2) copy.set((String) pairs[i], Json.node(pairs[i + 1]));
    return copy;
  }

  private static double numberOr(JsonNode value) { return value == null || value.isNull() ? Double.POSITIVE_INFINITY : value.doubleValue(); }

  private static ArrayNode union(List<String> first, List<String> second) {
    LinkedHashSet<String> values = new LinkedHashSet<>(first);
    values.addAll(second);
    return Json.strArray(values);
  }

  public ObjectNode recommend(ObjectNode chatInput, ChatState previous) {
    input = chatInput;
    strings = copyFor(language());
    String lastContent = lastContent();
    MenuFilters.BeverageRequest beverageRequest = MenuFilters.explicitBeverageRequest(lastContent);
    JsonNode preferences = input.get("preferences");
    ObjectNode intent = Json.copy(preferences);
    intent.set("keywords", Json.arr());
    intent.put("popular", "popular".equals(Json.str(input, "action")));
    intent.put("clarification", "");
    intent.put("kind", "food");
    intent.put("includeKids", false);
    String mode = "filters";
    String queryMode = null;
    ArrayNode searchTerms = null;
    memory = previous == null ? null : previous.memory;
    ChatState menuContext = previous;
    // Full guest messages and verified memory remain available; old assistant prose is shortened.
    ArrayNode promptMessages = Json.arr();
    for (JsonNode message : input.get("messages")) {
      promptMessages.add("assistant".equals(Json.str(message, "role")) ? spread(message, "content", Js.slice(Json.str(message, "content"), 320)) : message);
    }
    // Keep bounded guest-question anchors independently of the model's lossy summary.
    List<String> anchors = new ArrayList<>(previous == null || previous.questions == null ? List.of() : previous.questions);
    anchors.add(Js.slice(lastContent, 240));
    questions = new ArrayList<>(anchors.subList(Math.max(0, anchors.size() - 20), anchors.size()));
    onState.accept(ChatState.with(menuContext, memory, questions));
    String action = Json.str(input, "action");
    if ("vegetarian".equals(action)) intent.put("diet", "vegetarian");
    if ("mild".equals(action)) intent.put("mild", true);
    if ("chat".equals(action)) {
      try {
        ObjectNode prompt = Json.obj(
          "previousQuestions", questions.subList(0, questions.size() - 1),
          "conversationContext", memory == null ? Json.F.nullNode() : memory,
          "previousMenu", previous != null && previous.selectedItems != null && !previous.selectedItems.isEmpty()
            ? Json.obj("intent", previous.intent == null ? Json.UNDEFINED : previous.intent, "selectedItems", previous.selectedItems) : null,
          "language", language(),
          "pageContext", Json.truthy(input.get("context")) ? input.get("context") : Json.node("menu"),
          "filters", preferences,
          "conversation", promptMessages);
        final ChatState validationContext = menuContext;
        final ObjectNode validationInput = input;
        ObjectNode raw = ai.json(RecommendationPrompts.INTENT_SYSTEM, prompt, RecommendationPrompts.INTENT_JSON, 1400, value -> {
          ObjectNode parsed = (ObjectNode) RecommendationPrompts.INTENT.parse(value);
          MenuQueryResolver.Plan checked = MenuQueryResolver.resolve(parsed, validationInput, validationContext, Json.str(parsed, "queryMode"),
            (ArrayNode) parsed.get("searchTerms"), beverageRequest, null);
          if (!checked.valid()) throw new IllegalStateException("Unverified query evidence");
        });
        ObjectNode extracted = (ObjectNode) RecommendationPrompts.INTENT.parse(raw);
        String detectedLanguage = Json.str(extracted, "detectedLanguage");
        JsonNode updatedMemory = extracted.get("memory");
        boolean forgetContext = Json.bool(extracted, "forgetContext");
        String extractedMode = Json.str(extracted, "queryMode");
        ArrayNode extractedTerms = (ArrayNode) extracted.get("searchTerms");
        extracted.remove(List.of("detectedLanguage", "memory", "forgetContext", "queryMode", "searchTerms"));
        if (detectedLanguage != null) {
          input = spread(input, "language", detectedLanguage);
          strings = RecommendationPrompts.COPY.get(detectedLanguage);
        }
        queryMode = extractedMode;
        searchTerms = extractedTerms;
        if (updatedMemory != null && !updatedMemory.isNull()) memory = updatedMemory;
        if (forgetContext) {
          menuContext = null;
          questions = new ArrayList<>();
          memory = Json.obj("summary", "", "keywords", Json.arr());
        }
        JsonNode manual = preferences.get("maxPrice");
        JsonNode extractedMax = extracted.get("maxPrice");
        JsonNode maxPrice = manual == null || manual.isNull() ? extractedMax
          : extractedMax == null || extractedMax.isNull() ? manual : Json.num(Math.min(manual.doubleValue(), extractedMax.doubleValue()));
        intent = spread(extracted,
          "maxPrice", maxPrice,
          "mild", Json.bool(preferences, "mild") || Json.bool(extracted, "mild"),
          "avoid", union(Json.strings(preferences, "avoid"), Json.strings(extracted, "avoid")));
        mode = "ai";
      } catch (RuntimeException error) {
        int status = error instanceof ApiException api ? api.status() : 502;
        onDiagnostic.accept(Json.obj("event", "chat.ai_failure", "stage", "intent", "status", status,
          "reason", error instanceof ValidationException ? "invalid_intent" : "provider_failure"));
        ObjectNode priorIntent = previous == null ? null : previous.intent;
        if (beverageRequest != null && beverageRequest.simple()) {
          JsonNode priorDrink = priorIntent != null && "drink".equals(Json.str(priorIntent, "kind")) ? priorIntent : Json.obj();
          double priceLimit = Math.min(numberOr(priorDrink.get("maxPrice")), numberOr(intent.get("maxPrice")));
          String preferredDiet = Json.str(preferences, "diet");
          intent = spread(intent, "topic", "menu", "kind", "drink", "drinkType", beverageRequest.type(), "scope", "catalog", "referenceIds", Json.arr(), "alcohol", "any",
            "maxPrice", Double.isFinite(priceLimit) ? Json.num(priceLimit) : Json.F.nullNode(),
            "diet", !"any".equals(preferredDiet) ? Json.node(preferredDiet) : MenuQueryResolver.or(priorDrink.get("diet"), intent.get("diet")),
            "mild", MenuQueryResolver.or(priorDrink.get("mild"), intent.get("mild")),
            "avoid", union(Json.strings(priorDrink, "avoid"), Json.strings(intent, "avoid")));
        } else if (MenuFilters.isAlcoholOnlyFollowup(lastContent) && priorIntent != null && "drink".equals(Json.str(priorIntent, "kind"))) {
          // This exact follow-up only adds a known restriction to a previously resolved request.
          intent = Json.copy(priorIntent);
        } else {
          if (restaurant != null && "home".equals(Json.str(input, "context"))) return restaurantReply();
          // Other free text still requires intent extraction; do not guess restrictions.
          return Json.obj("reply", strings.offline(), "items", Json.arr(), "mode", "unavailable", "preferences", preferences, "language", language());
        }
      }
    }
    onState.accept(ChatState.with(menuContext, memory, questions));
    if (!"budget".equals(Json.str(intent, "clarificationReason"))) intent = spread(intent, "clarification", "");
    MenuQueryResolver.Plan plan = MenuQueryResolver.resolve(intent, input, menuContext, queryMode, searchTerms, beverageRequest, null);
    intent = plan.intent();
    if (!plan.valid()) {
      onDiagnostic.accept(Json.obj("event", "chat.invalid_query", "mode", plan.mode(), "issues", plan.issues(), "structured", plan.structured()));
      return Json.obj("reply", strings.offline(), "items", Json.arr(), "mode", "unavailable", "preferences", intent, "language", language());
    }
    if ("restaurant".equals(Json.str(intent, "topic")) && restaurant != null) return restaurantReply();
    Catalog catalog = getCatalog.apply(Json.truthy(intent.get("popular")));
    plan = MenuQueryResolver.resolve(intent, input, menuContext, queryMode, searchTerms, beverageRequest, catalog);
    intent = plan.intent();
    List<ObjectNode> categories = catalog.categories();
    MenuRetriever.Result retrieved = retriever.retrieve(catalog, intent, preferences, menuContext, false);
    // A zero from references, narrowing or a cache must not be mistaken for inventory absence.
    if (retrieved.items().isEmpty() && !"previous".equals(Json.str(intent, "scope"))) retrieved = retriever.retrieve(catalog, intent, preferences, null, true);
    boolean alternatives = false;
    if (retrieved.items().isEmpty() && "inherited".equals(plan.subjectSource()) && "refine".equals(plan.mode()) && "drink".equals(Json.str(intent, "kind"))) {
      MenuRetriever.Result broadened = retriever.retrieve(catalog, spread(intent, "drinkType", "any", "scope", "catalog", "referenceIds", Json.arr()), preferences, null, true);
      if (!broadened.items().isEmpty()) {
        retrieved = broadened;
        alternatives = true;
        onDiagnostic.accept(Json.obj("event", "chat.inherited_subject_alternatives", "mode", plan.mode(), "issues", plan.issues(),
          "filters", Json.obj("kind", intent.get("kind"), "drinkType", intent.get("drinkType"), "alcohol", intent.get("alcohol")), "retrieval", retrieved.retrieval()));
      }
    }
    if (retrieved.items().isEmpty() && !Json.strings(intent, "keywords").isEmpty() && !"previous".equals(Json.str(intent, "scope")) && !"drink".equals(Json.str(intent, "kind"))) {
      // Offer real alternatives, relaxing only desired search keywords, never dietary/price constraints.
      MenuRetriever.Result fallback = retriever.retrieve(catalog, spread(intent, "scope", "catalog", "referenceIds", Json.arr(), "keywords", Json.arr()), preferences, null, false);
      if (!fallback.items().isEmpty()) { retrieved = fallback; alternatives = true; }
    }
    if (retrieved.items().isEmpty()) {
      onDiagnostic.accept(Json.obj("event", "chat.empty_query", "mode", plan.mode(), "structured", plan.structured(), "issues", plan.issues(),
        "filters", Json.obj("kind", intent.get("kind"), "drinkType", intent.get("drinkType"), "alcohol", intent.get("alcohol"), "scope", intent.get("scope"),
          "keywordCount", Json.strings(intent, "keywords").size()),
        "retrieval", retrieved.retrieval()));
    }
    Map<String, Double> views = new HashMap<>();
    for (JsonNode row : catalog.counts()) {
      JsonNode count = row.get("view_count");
      double value = count == null ? Double.NaN : count.isNumber() ? count.doubleValue() : count.isString() ? Js.toNumber(count.stringValue()) : Double.NaN;
      views.put(Json.str(row, "menu_item_id"), value);
    }
    Map<String, String> categoryNames = new HashMap<>();
    for (ObjectNode category : categories) categoryNames.put(Json.str(category, "id"), MenuFilters.normalize(MenuFilters.tpl(category.get("name")) + " " + MenuFilters.tpl(category.get("display_name"))));
    Function<JsonNode, String> categoryOf = item -> {
      String name = categoryNames.get(Json.str(item, "category_id"));
      return name == null || name.isEmpty() ? "" : name;
    };
    Function<JsonNode, Boolean> isSpecial = item -> Js.test("\\bspecials?\\b", Js.replaceAll(categoryOf.apply(item), "[_-]", " "));
    Function<JsonNode, Integer> categoryPriority = item -> {
      String category = categoryOf.apply(item);
      return Js.test("mains?|pasta|pizza|gnocchi|calzone", category) ? 0 : Js.test("starter|appetizer", category) ? 1 : 2;
    };
    List<String> keywords = Json.strings(intent, "keywords").stream().map(MenuFilters::normalize).toList();
    Function<JsonNode, Integer> score = item -> {
      String text = MenuFilters.normalize(MenuFilters.tpl(item.get("name")) + " " + MenuFilters.tpl(item.get("description")) + " " + String.join(" ", Json.strings(item, "badges")));
      int sum = 0;
      for (String word : keywords) if (text.contains(word)) sum += 10;
      return sum;
    };
    boolean popular = Json.truthy(intent.get("popular"));
    boolean ascending = "asc".equals(Json.str(intent, "priceOrder"));
    Function<JsonNode, Double> viewCount = item -> {
      Double value = views.get(Json.str(item, "id"));
      return value == null || Double.isNaN(value) || value == 0 ? 0 : value;
    };
    List<ObjectNode> candidates = new ArrayList<>(retrieved.items());
    candidates.sort((a, b) -> {
      int special = Boolean.compare(isSpecial.apply(b), isSpecial.apply(a));
      if (special != 0) return special;
      int relevance = popular ? Double.compare(viewCount.apply(b), viewCount.apply(a)) : Integer.compare(score.apply(b), score.apply(a));
      if (relevance != 0) return relevance;
      int price = ascending ? Long.compare(MenuFilters.priceWithVAT(a), MenuFilters.priceWithVAT(b)) : Long.compare(MenuFilters.priceWithVAT(b), MenuFilters.priceWithVAT(a));
      if (price != 0) return price;
      int priority = Integer.compare(categoryPriority.apply(a), categoryPriority.apply(b));
      if (priority != 0) return priority;
      return Json.str(a, "id", "").compareTo(Json.str(b, "id", ""));
    });
    int selectionLimit = beverageRequest != null && Js.test("ngon nhat|tot nhat|\\bbest\\b", MenuFilters.normalize(lastContent)) ? 2 : alternatives ? 2 : 4;
    List<JsonNode> subjects = Json.list(intent.get("subjects"));
    List<ObjectNode> selection = MenuSubjects.selectMenuSubjects(candidates, Json.get(intent, "subjects") == null ? null : subjects, selectionLimit);
    ChatState next = ChatState.copyOf(retrieved.state());
    next.intent = intent;
    next.memory = memory;
    next.questions = new ArrayList<>(questions);
    next.selectedIds = selection.stream().map(item -> Json.str(item, "id")).toList();
    ArrayNode selectedItems = Json.arr();
    for (ObjectNode item : selection) selectedItems.add(Json.obj("id", item.get("id"), "name", item.get("name")));
    next.selectedItems = selectedItems;
    onState.accept(next);
    String language = language();
    String reply;
    if (!selection.isEmpty()) {
      List<String> lines = new ArrayList<>();
      for (ObjectNode item : selection) {
        JsonNode localized = item.get("description_" + language);
        String description = Json.truthy(localized) ? Json.jsString(localized) : Json.str(item, "description");
        String tasting = MenuFilters.menuFlavourSummary(item, language);
        List<String> parts = new ArrayList<>();
        String name = MenuFilters.tpl(item.get("name"));
        parts.add(description != null && !description.isEmpty() ? name + ": " + Js.slice(description, 350) : name);
        if (!tasting.isEmpty()) parts.add(tasting);
        lines.add(String.join(". ", parts));
      }
      reply = strings.match() + "\n\n" + String.join("\n\n", lines);
    } else reply = strings.empty();
    if (popular && !catalog.popularityAvailable()) reply = strings.noPopularity() + " " + reply;
    if ("ai".equals(mode) && !selection.isEmpty()) {
      try {
        ArrayNode dishes = Json.arr();
        for (ObjectNode item : selection) {
          String category = categoryOf.apply(item);
          dishes.add(Json.obj("name", item.get("name"), "category", category, "isSpecial", isSpecial.apply(item),
            "beverage", MenuFilters.beverageProfile(item, category).toJson(), "flavourNotes", MenuFilters.menuFlavourNotes(item),
            "description", Js.slice(Json.str(item, "description"), 400), "priceWithVAT", MenuFilters.priceWithVAT(item),
            "badges", item.has("badges") ? item.get("badges") : Json.UNDEFINED));
        }
        ArrayNode conversation = Json.arr();
        for (int i = Math.max(0, promptMessages.size() - 5); i < promptMessages.size(); i++) conversation.add(promptMessages.get(i));
        ObjectNode answer = ai.json(RecommendationPrompts.REPLY_SYSTEM, Json.obj(
          "requestedGroups", subjects.stream().map(subject -> subject.get("value")).toList(),
          "exactMatch", !alternatives, "requestedKeywords", intent.get("keywords"), "language", language,
          "previousQuestions", questions, "conversationContext", memory == null ? Json.F.nullNode() : memory,
          "conversation", conversation, "request", lastContent, "preferences", intent,
          "popularityAvailable", catalog.popularityAvailable(), "dishes", dishes), RecommendationPrompts.REPLY_JSON, 1000);
        JsonNode text = answer.get("reply");
        if (text != null && text.isString() && !Js.trim(text.stringValue()).isEmpty()) reply = Js.slice(Js.trim(text.stringValue()), 2600);
      } catch (RuntimeException error) {
        onDiagnostic.accept(Json.obj("event", "chat.ai_failure", "stage", "reply", "status", error instanceof ApiException api ? api.status() : 502, "reason", "provider_failure"));
        mode = "filters";
      }
    }
    if (alternatives) reply = RecommendationPrompts.ALTERNATIVE.get(language) + "\n\n" + reply;
    String clarification = Json.str(intent, "clarification");
    if ("budget".equals(Json.str(intent, "clarificationReason")) && clarification != null && !clarification.isEmpty()) reply = clarification + "\n\n" + reply;
    if (!Json.strings(intent, "avoid").isEmpty()) reply += "\n\n" + strings.allergy();
    LinkedHashSet<String> diets = new LinkedHashSet<>();
    for (String diet : new String[] {Json.str(preferences, "diet"), Json.str(intent, "diet")}) if (!"any".equals(diet)) diets.add(diet);
    ObjectNode appliedFilters = Json.obj("diets", new ArrayList<>(diets), "maxPrice", intent.has("maxPrice") ? intent.get("maxPrice") : Json.UNDEFINED,
      "mild", Json.bool(preferences, "mild") || Json.bool(intent, "mild"),
      "avoid", union(Json.strings(preferences, "avoid"), Json.strings(intent, "avoid")),
      "alcohol", MenuQueryResolver.or(intent.get("alcohol"), Json.node("any")));
    ArrayNode items = Json.arr();
    for (ObjectNode item : selection) items.add(spread(item, "price_with_vat", MenuFilters.priceWithVAT(item)));
    ObjectNode answer = Json.obj("reply", reply, "appliedFilters", appliedFilters, "alternatives", alternatives, "retrieval", retrieved.retrieval(),
      "items", items, "mode", mode, "preferences", intent, "language", language);
    if ("both".equals(Json.str(intent, "topic")) && restaurant != null) {
      ObjectNode information = restaurantReply();
      answer.put("reply", Json.str(information, "reply") + "\n\n" + Json.str(answer, "reply"));
      answer.set("sources", information.get("sources") == null ? Json.F.nullNode() : information.get("sources"));
    }
    return answer;
  }
}
