package com.menumaestro.backend.service.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;

import com.menumaestro.backend.model.ChatState;
import com.menumaestro.backend.service.CatalogService.Catalog;
import com.menumaestro.backend.util.Js;
import com.menumaestro.backend.util.Json;
import com.menumaestro.backend.validation.Schema;
import com.menumaestro.backend.validation.Z;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Compiles the model's evidenced search terms into one canonical menu query (menu-query.js).
 * Subject, mandatory attributes and ingredient/name terms are separate axes; preferences
 * never become predicates, and every required term must be quoted from a guest message.
 */
public final class MenuQueryResolver {
  private MenuQueryResolver() {}

  public static final List<String> QUERY_MODES = List.of("new_search", "refine", "detail");
  public static final List<String> TERM_TYPES = List.of("ingredient", "dish_type", "dish_name", "kind", "beverage_type", "alcohol", "diet", "mild", "avoid", "budget", "preference");

  public static Schema searchTermSchema() {
    return Z.object()
      .field("type", Z.enumOf(TERM_TYPES))
      .field("value", Z.string().trim().min(1).max(100))
      .field("source", Z.string().trim().min(1).max(240))
      .field("scope", Z.enumOf("request", "session").withDefault(() -> Json.node("request")));
  }

  private static final Set<String> KINDS = Set.of("food", "drink", "dessert", "any");
  private static final Set<String> DRINKS = Set.of("any", "juice", "mocktail", "cocktail", "beer", "wine", "coffee", "tea", "soft_drink", "spirit");
  private static final Set<String> ALCOHOLS = Set.of("any", "none", "alcoholic");

  public record Plan(ObjectNode intent, String mode, boolean valid, List<String> issues, boolean structured, String subjectSource) {}

  private static String fingerprint(String text) { return MenuFilters.fingerprint(text); }

  private static boolean truthy(String value) { return value != null && !value.isEmpty(); }

  /** Array.prototype.includes: null-safe membership. */
  private static boolean in(java.util.Collection<String> values, String value) { return value != null && values.contains(value); }

  /** Verifies VND amounts from quoted numeric evidence, rather than trusting an inferred cap. */
  static Double quotedBudget(JsonNode term) {
    String[] canonical = Js.match(fingerprint(Json.str(term, "value")), "^(\\d+(?:[.,]\\d+)*)\\s*(?:vnd|dong|₫)?$");
    double expected = Double.NaN;
    if (canonical != null) {
      String amount = canonical[1];
      expected = Js.toNumber(Js.test("^\\d{1,3}(?:[.,]\\d{3})+$", amount) ? amount.replaceAll("[.,]", "") : Js.replaceFirstLiteral(amount, ",", "."));
    }
    if (!Double.isFinite(expected) || expected < 0 || expected > 100000000) return null;
    String quote = fingerprint(Json.str(term, "source"));
    if (Js.test("[$€£]|usd|eur", quote)) return null;
    Matcher matcher = Js.re("(\\d+(?:[.,]\\d+)*)\\s*(trieu|million|nghin|ngan|k\\b|m\\b|vnd|dong|₫)?", "g").matcher(quote);
    while (matcher.find()) {
      String number = matcher.group(1);
      String unit = matcher.group(2);
      if (Js.test("^\\d{1,3}(?:[.,]\\d{3})+$", number)) number = number.replaceAll("[.,]", "");
      else number = Js.replaceFirstLiteral(number, ",", ".");
      double multiplier = unit != null && List.of("k", "nghin", "ngan").contains(unit) ? 1000 : unit != null && List.of("m", "trieu", "million").contains(unit) ? 1000000 : 1;
      double amount = Js.toNumber(number) * multiplier;
      if (amount == expected && (unit != null || amount >= 1000)) return amount;
    }
    return null;
  }

  private static ObjectNode withField(JsonNode term, Object... pairs) {
    ObjectNode copy = Json.copy(term);
    for (int i = 0; i < pairs.length; i += 2) copy.set((String) pairs[i], Json.node(pairs[i + 1]));
    return copy;
  }

  /** Grounds partial names against the live catalog: "steak" is not the previously shown "Steak Salad". */
  static JsonNode groundTerm(JsonNode term, Catalog catalog, ChatState previous, List<String> issues) {
    String type = Json.str(term, "type");
    if (catalog == null || !in(List.of("ingredient", "dish_type", "dish_name"), type)) return term;
    String sourceText = Json.str(term, "source");
    String valueText = Json.str(term, "value");
    ObjectNode quotedCategory = MenuSubjects.menuSubject(sourceText, catalog.categories());
    if (!quotedCategory.get("categoryIds").isEmpty() && catalog.items().stream().noneMatch(item -> fingerprint(Json.str(item, "name")).equals(fingerprint(sourceText)))) {
      return withField(term, "type", "dish_type", "value", Json.str(quotedCategory, "value"));
    }
    if (!MenuSubjects.menuSubject(valueText, catalog.categories()).get("categoryIds").isEmpty()
      && catalog.items().stream().noneMatch(item -> fingerprint(Json.str(item, "name")).equals(fingerprint(valueText)))) {
      return withField(term, "type", "dish_type");
    }
    String source = fingerprint(sourceText);
    String value = fingerprint(valueText);
    JsonNode named = catalog.items().stream().filter(item -> fingerprint(Json.str(item, "name")).equals(value)).findFirst().orElse(null);
    if (named != null && source.contains(value)) return term;
    JsonNode name = named;
    if (name == null && previous != null && previous.selectedItems != null) {
      for (JsonNode item : previous.selectedItems) if (fingerprint(Json.str(item, "name")).contains(value)) { name = item; break; }
    }
    if (name == null) return term;
    List<String> tokens = MenuFilters.unique(Js.matchAll(fingerprint(Json.str(name, "name")), "[\\p{L}\\p{N}]+"));
    Set<String> sourceTokens = new LinkedHashSet<>(Js.matchAll(source, "[\\p{L}\\p{N}]+"));
    List<String> anchors = tokens.stream().filter(token -> token.length() >= 3 && sourceTokens.contains(token)).toList();
    if (anchors.size() != 1) return term;
    if ("ingredient".equals(type) && previous != null && previous.intent != null
      && Json.strings(previous.intent, "keywords").stream().anyMatch(word -> fingerprint(word).equals(value))) return term;
    issues.add("partial_name_resolved_as_catalog_search");
    return withField(term, "type", "dish_type", "value", anchors.get(0));
  }

  private static String lastFact(List<JsonNode> current, String type, Set<String> allowed) {
    String result = null;
    for (JsonNode term : current) if (type.equals(Json.str(term, "type")) && in(allowed, Json.str(term, "value"))) result = Json.str(term, "value");
    return result;
  }

  private static JsonNode last(List<JsonNode> terms, java.util.function.Predicate<JsonNode> filter) {
    JsonNode result = null;
    for (JsonNode term : terms) if (filter.test(term)) result = term;
    return result;
  }

  /** JavaScript `a || b` on JSON values. */
  static JsonNode or(JsonNode a, JsonNode b) { return Json.truthy(a) ? a : b; }

  private static double numberOr(JsonNode value, double fallback) { return value == null || value.isNull() ? fallback : value.doubleValue(); }

  public static Plan resolve(ObjectNode intent, JsonNode input, ChatState previous, String queryMode, ArrayNode searchTerms,
                             MenuFilters.BeverageRequest explicitBeverage, Catalog catalog) {
    JsonNode messages = input.get("messages");
    String lastContent = Json.str(messages.get(messages.size() - 1), "content");
    String latest = fingerprint(lastContent);
    List<String> history = new ArrayList<>();
    for (JsonNode message : messages) if ("user".equals(Json.str(message, "role"))) history.add(fingerprint(Json.str(message, "content")));
    if (previous != null && previous.questions != null) for (String question : previous.questions) history.add(fingerprint(question));
    List<String> issues = new ArrayList<>();
    boolean structured = searchTerms != null;
    List<JsonNode> terms = new ArrayList<>();
    if (searchTerms != null) {
      for (JsonNode term : searchTerms) {
        String source = fingerprint(Json.str(term, "source"));
        boolean evidenced = history.stream().anyMatch(message -> message.contains(source));
        if (!evidenced) issues.add("preference".equals(Json.str(term, "type")) ? "preference_without_evidence" : "required_term_without_evidence");
        if (evidenced) terms.add(term);
      }
      List<JsonNode> grounded = new ArrayList<>();
      for (JsonNode term : terms) grounded.add(groundTerm(term, catalog, previous, issues));
      terms = grounded;
    }
    List<JsonNode> current = terms.stream().filter(term -> latest.contains(fingerprint(Json.str(term, "source")))).toList();
    String currentKind = lastFact(current, "kind", KINDS);
    String currentDrink = lastFact(current, "beverage_type", DRINKS);
    if (!truthy(currentDrink)) currentDrink = explicitBeverage == null ? null : explicitBeverage.type();
    String currentAlcohol = lastFact(current, "alcohol", ALCOHOLS);
    boolean currentDishType = current.stream().anyMatch(term -> "dish_type".equals(Json.str(term, "type")));
    boolean hasCurrentDish = current.stream().anyMatch(term -> "dish_name".equals(Json.str(term, "type")));
    boolean referencedDish = current.stream().anyMatch(term -> "dish_name".equals(Json.str(term, "type")) && previous != null && previous.selectedItems != null
      && Json.list(previous.selectedItems).stream().anyMatch(item -> fingerprint(Json.str(item, "name")).equals(fingerprint(Json.str(term, "value")))
        || Json.str(term, "value").equals(Json.str(item, "id"))));
    boolean alcoholOnly = MenuFilters.isAlcoholOnlyFollowup(lastContent);
    ObjectNode priorIntent = previous == null ? null : previous.intent;
    boolean kindOrDrink = truthy(currentKind) || truthy(currentDrink);
    String mode = truthy(queryMode) ? queryMode : kindOrDrink ? "new_search" : "previous".equals(Json.str(intent, "scope")) ? "detail" : "new_search";
    // A newly named broad subject expands the search; it is not a reference to last turn's cards.
    if ((kindOrDrink && !hasCurrentDish) || currentDishType) mode = "new_search";
    if (alcoholOnly && priorIntent != null && "drink".equals(Json.str(priorIntent, "kind"))) mode = "refine";
    if (structured && "detail".equals(mode) && !referencedDish) {
      mode = priorIntent != null ? "refine" : "new_search";
      issues.add("ungrounded_detail_reference_removed");
    }

    ObjectNode next = Json.copy(intent);
    next.set("drinkType", or(intent.get("drinkType"), Json.node("any")));
    next.set("alcohol", or(intent.get("alcohol"), Json.node("any")));
    if (truthy(currentKind)) next.put("kind", currentKind);
    if (truthy(currentDrink)) next.put("drinkType", currentDrink);
    else if (structured) next.set("drinkType", !"new_search".equals(mode) ? or(Json.get(priorIntent, "drinkType"), Json.node("any")) : Json.node("any"));
    if (truthy(currentAlcohol)) next.put("alcohol", currentAlcohol);
    else if (structured) next.set("alcohol", !"new_search".equals(mode) ? or(Json.get(priorIntent, "alcohol"), Json.node("any")) : Json.node("any"));
    if (MenuFilters.requestsAlcoholFree(lastContent)) next.put("alcohol", "none");
    if (structured) {
      List<JsonNode> active = "new_search".equals(mode) ? current : terms;
      List<JsonNode> ingredientTerms = active.stream().filter(term -> "ingredient".equals(Json.str(term, "type"))).toList();
      List<JsonNode> subjectTerms = new ArrayList<>(active.stream().filter(term -> in(List.of("dish_type", "dish_name"), Json.str(term, "type"))).toList());
      java.util.function.ToIntFunction<JsonNode> position = term -> {
        int index = latest.indexOf(fingerprint(Json.str(term, "value")));
        return index >= 0 ? index : latest.indexOf(fingerprint(Json.str(term, "source")));
      };
      subjectTerms.sort((a, b) -> Integer.compare(position.applyAsInt(a), position.applyAsInt(b)));
      next.set("keywords", Json.strArray(MenuFilters.unique(ingredientTerms.stream().map(term -> Json.str(term, "value")).toList())));
      LinkedHashMap<String, ObjectNode> subjects = new LinkedHashMap<>();
      for (JsonNode term : subjectTerms) {
        ObjectNode subject = "dish_name".equals(Json.str(term, "type"))
          ? Json.obj("value", Json.str(term, "value"), "categoryIds", Json.arr())
          : MenuSubjects.menuSubject(Json.str(term, "value"), catalog == null ? null : catalog.categories());
        subjects.put(Json.str(subject, "value"), subject);
      }
      next.set("subjects", Json.arr(subjects.values()));
      if (terms.stream().anyMatch(term -> !in(List.of("ingredient", "dish_type", "dish_name"), Json.str(term, "type")))) issues.add("non_search_terms_separated");
      if ("refine".equals(mode) && !truthy(currentKind) && !truthy(currentDrink)) {
        if (ingredientTerms.isEmpty()) next.set("keywords", Json.has(priorIntent, "keywords") && Json.truthy(priorIntent.get("keywords")) ? priorIntent.get("keywords").deepCopy() : Json.arr());
        if (subjectTerms.isEmpty()) next.set("subjects", Json.has(priorIntent, "subjects") && Json.truthy(priorIntent.get("subjects")) ? priorIntent.get("subjects").deepCopy() : Json.arr());
      }
    } else if (explicitBeverage != null && explicitBeverage.simple()) {
      next.set("keywords", Json.arr());
      next.set("subjects", Json.arr());
      next.put("clarification", "");
      next.put("popular", false);
    }

    if (alcoholOnly && priorIntent != null && "drink".equals(Json.str(priorIntent, "kind"))) {
      double limit = Math.min(numberOr(priorIntent.get("maxPrice"), Double.POSITIVE_INFINITY), numberOr(next.get("maxPrice"), Double.POSITIVE_INFINITY));
      ObjectNode merged = Json.copy(next);
      merged.put("kind", "drink");
      merged.set("drinkType", or(priorIntent.get("drinkType"), Json.node("any")));
      merged.set("keywords", or(priorIntent.get("keywords"), Json.arr()).deepCopy());
      merged.set("subjects", or(priorIntent.get("subjects"), Json.arr()).deepCopy());
      merged.set("diet", or(priorIntent.get("diet"), next.get("diet")));
      merged.set("maxPrice", Double.isFinite(limit) ? Json.num(limit) : Json.F.nullNode());
      JsonNode mild = or(priorIntent.get("mild"), next.get("mild"));
      if (mild == null) merged.remove("mild"); else merged.set("mild", mild);
      LinkedHashSet<String> avoid = new LinkedHashSet<>(Json.strings(priorIntent, "avoid"));
      avoid.addAll(Json.strings(next, "avoid"));
      merged.set("avoid", Json.strArray(avoid));
      JsonNode popular = or(priorIntent.get("popular"), next.get("popular"));
      if (popular == null) merged.remove("popular"); else merged.set("popular", popular);
      merged.put("alcohol", "none");
      next = merged;
    } else if ("drink".equals(Json.str(next, "kind")) && "any".equals(Json.str(next, "alcohol")) && priorIntent != null
      && "none".equals(Json.str(priorIntent, "alcohol")) && !truthy(currentAlcohol)) {
      next.put("alcohol", "none");
    }

    // Natural-language restrictions need evidence too. Request-scoped taste choices do not
    // accumulate across topics; explicitly stated session restrictions do.
    JsonNode saved = priorIntent != null && Json.truthy(priorIntent.get("verifiedPreferences")) ? priorIntent.get("verifiedPreferences") : Json.obj();
    final String finalMode = mode;
    java.util.function.Predicate<JsonNode> reusable = preference -> Json.truthy(preference)
      && ("session".equals(Json.str(preference, "scope")) || !"new_search".equals(finalMode));
    JsonNode dietTerm = last(current, term -> "diet".equals(Json.str(term, "type")) && in(List.of("any", "vegetarian", "vegan", "gluten_free", "dairy_free"), Json.str(term, "value")));
    JsonNode mildTerm = last(current, term -> "mild".equals(Json.str(term, "type")) && in(List.of("true", "false"), Json.str(term, "value")));
    List<JsonNode> avoidTerms = current.stream().filter(term -> "avoid".equals(Json.str(term, "type"))).toList();
    JsonNode diet = dietTerm != null ? dietTerm : reusable.test(Json.get(saved, "diet")) ? saved.get("diet") : null;
    JsonNode mild = mildTerm != null ? mildTerm : reusable.test(Json.get(saved, "mild")) ? saved.get("mild") : null;
    List<JsonNode> savedAvoid = Json.list(Json.get(saved, "avoid")).stream().filter(reusable).toList();
    List<JsonNode> avoiding = new ArrayList<>();
    if (avoidTerms.stream().noneMatch(term -> "none".equals(Json.str(term, "value")))) { avoiding.addAll(savedAvoid); avoiding.addAll(avoidTerms); }
    String action = Json.str(input, "action");
    String dietValue = diet == null ? null : Json.str(diet, "value");
    next.put("diet", "vegetarian".equals(action) ? "vegetarian" : truthy(dietValue) ? dietValue : "any");
    next.put("mild", "mild".equals(action) || (mild != null && "true".equals(Json.str(mild, "value"))));
    next.set("avoid", Json.strArray(MenuFilters.unique(avoiding.stream().map(term -> Json.str(term, "value")).toList())));
    ArrayNode verifiedAvoid = Json.arr();
    for (JsonNode term : avoiding) verifiedAvoid.add(Json.obj("value", Json.str(term, "value"), "scope", truthy(Json.str(term, "scope")) ? Json.str(term, "scope") : "request"));
    next.set("verifiedPreferences", Json.obj(
      "diet", diet == null ? null : Json.obj("value", Json.get(diet, "value"), "scope", truthy(Json.str(diet, "scope")) ? Json.str(diet, "scope") : "request"),
      "mild", mild == null ? null : Json.obj("value", Json.get(mild, "value"), "scope", truthy(Json.str(mild, "scope")) ? Json.str(mild, "scope") : "request"),
      "avoid", verifiedAvoid));
    JsonNode intentDiet = intent.get("diet");
    if (intentDiet != null && !(intentDiet.isString() && "any".equals(intentDiet.stringValue())) && "any".equals(Json.str(next, "diet"))) issues.add("unrequested_diet_removed");
    if (Json.truthy(intent.get("mild")) && !Json.bool(next, "mild")) issues.add("unrequested_mild_removed");
    JsonNode budgetTerm = last(current, term -> "budget".equals(Json.str(term, "type")));
    JsonNode manualNode = Json.get(input.get("preferences"), "maxPrice");
    Double manualBudget = manualNode == null || manualNode.isNull() ? null : manualNode.doubleValue();
    Double spokenBudget = null;
    if (budgetTerm != null && !"budget".equals(Json.str(intent, "clarificationReason"))) {
      spokenBudget = quotedBudget(budgetTerm);
      if (spokenBudget == null && !"none".equals(Json.str(budgetTerm, "value"))) issues.add("unverified_budget_ignored");
    } else if (!"new_search".equals(mode) && priorIntent != null && Json.truthy(priorIntent.get("budgetExplicit"))
      && "spoken".equals(Json.str(priorIntent, "budgetOrigin")) && budgetTerm == null) {
      spokenBudget = Json.dbl(priorIntent, "maxPrice");
    }
    double cap = Math.min(manualBudget == null ? Double.POSITIVE_INFINITY : manualBudget, spokenBudget == null ? Double.POSITIVE_INFINITY : spokenBudget);
    next.set("maxPrice", Double.isFinite(cap) ? Json.num(cap) : Json.F.nullNode());
    boolean hasCap = Double.isFinite(cap);
    next.put("budgetExplicit", hasCap);
    String origin = manualBudget != null && (spokenBudget == null || manualBudget <= spokenBudget) ? "manual" : spokenBudget != null ? "spoken" : null;
    if (origin == null) next.set("budgetOrigin", Json.F.nullNode()); else next.put("budgetOrigin", origin);
    JsonNode intentMax = intent.get("maxPrice");
    if (intentMax != null && !intentMax.isNull() && !hasCap) issues.add("unrequested_budget_removed");
    JsonNode priceTerm = last(current, term -> "preference".equals(Json.str(term, "type")) && in(List.of("price_low", "price_high"), Json.str(term, "value")));
    String pricePreference = priceTerm == null ? null : Json.str(priceTerm, "value");
    String priorOrder = Json.str(priorIntent, "priceOrder");
    next.put("priceOrder", "price_low".equals(pricePreference) ? "asc" : "price_high".equals(pricePreference) ? "desc"
      : !"new_search".equals(mode) && truthy(priorOrder) ? priorOrder : "desc");
    // These are domain invariants, independent of the wording of any particular question.
    if (!"any".equals(Json.str(next, "drinkType")) || !"any".equals(Json.str(next, "alcohol"))) {
      if ("food".equals(Json.str(next, "kind"))) issues.add("beverage_constraint_on_food_subject");
      if (!"any".equals(Json.str(next, "kind")) || "drink".equals(currentKind) || truthy(currentDrink) || "food".equals(Json.str(next, "kind"))) next.put("kind", "drink");
    }
    List<String> juiceWords = List.of("juice", "fruit juice", "nuoc ep");
    if ("drink".equals(Json.str(next, "kind")) && "any".equals(Json.str(next, "drinkType"))
      && Json.strings(next, "keywords").stream().anyMatch(word -> in(juiceWords, fingerprint(word)))) {
      next.put("drinkType", "juice");
      next.set("keywords", Json.strArray(Json.strings(next, "keywords").stream().filter(word -> !in(juiceWords, fingerprint(word))).toList()));
    }
    if ("cocktail".equals(Json.str(next, "drinkType")) && "none".equals(Json.str(next, "alcohol"))) next.put("drinkType", "mocktail");
    if ("juice".equals(Json.str(next, "drinkType"))) { next.put("kind", "drink"); next.put("alcohol", "none"); }
    if ("drink".equals(Json.str(next, "kind")) && "restaurant".equals(Json.str(next, "topic"))) next.put("topic", "menu");
    if (structured) {
      List<JsonNode> scope = "new_search".equals(mode) ? current : terms;
      next.put("popular", "popular".equals(action) || scope.stream().anyMatch(term -> "preference".equals(Json.str(term, "type")) && "popular".equals(Json.str(term, "value"))));
    }
    // Only an actual detail query is restricted to previously displayed cards.
    boolean detail = "detail".equals(mode) && !truthy(currentKind) && !truthy(currentDrink);
    next.put("scope", detail ? "previous" : "catalog");
    if (!detail) next.set("referenceIds", Json.arr());
    if ("previous".equals(Json.str(intent, "scope")) && !detail) issues.add("stale_previous_scope_removed");
    List<String> keywords = new ArrayList<>();
    for (String word : Json.strings(next, "keywords")) { String trimmed = Js.trim(word); if (!trimmed.isEmpty()) keywords.add(trimmed); }
    next.set("keywords", Json.strArray(MenuFilters.unique(keywords)));
    String priorDrink = Json.str(priorIntent, "drinkType");
    String subjectSource = truthy(currentDrink) ? "explicit" : "refine".equals(mode) && truthy(priorDrink) && !"any".equals(priorDrink) ? "inherited" : "general";
    boolean valid = !issues.contains("required_term_without_evidence");
    return new Plan(next, mode, valid, MenuFilters.unique(issues), structured, subjectSource);
  }
}
