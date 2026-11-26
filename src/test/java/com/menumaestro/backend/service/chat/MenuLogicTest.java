package com.menumaestro.backend.service.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.menumaestro.backend.model.ChatState;
import com.menumaestro.backend.service.CatalogService.Catalog;
import com.menumaestro.backend.util.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class MenuLogicTest {
  private static final String PASTA = "10000000-0000-4000-8000-000000000001";
  private static final String PIZZA = "10000000-0000-4000-8000-000000000002";
  private static final String COCKTAILS = "10000000-0000-4000-8000-000000000003";
  private static final String MOCKTAILS = "10000000-0000-4000-8000-000000000004";

  private static ObjectNode item(String id, String name, String description, String category, double price, String... badges) {
    return Json.obj("id", id, "name", name, "description", description, "price", price, "vat", 10, "category_id", category, "badges", List.of(badges));
  }

  private static final List<ObjectNode> CATEGORIES = List.of(
    Json.obj("id", PASTA, "name", "pasta", "display_name", "Pasta"), Json.obj("id", PIZZA, "name", "pizza", "display_name", "Pizza"),
    Json.obj("id", COCKTAILS, "name", "cocktails", "display_name", "Cocktails"), Json.obj("id", MOCKTAILS, "name", "mocktails", "display_name", "Mocktails"));
  private static final List<ObjectNode> ITEMS = List.of(
    item("a1", "Spaghetti Carbonara", "Spaghetti, bacon, egg, parmesan", PASTA, 180000),
    item("a2", "Penne Arrabbiata", "Penne, tomato, chili, garlic", PASTA, 150000, "vegetarian", "spicy"),
    item("a3", "Margherita", "Tomato, mozzarella, basil", PIZZA, 160000, "vegetarian"),
    item("a4", "Mojito", "Rum, lime juice, mint, soda", COCKTAILS, 120000),
    item("a5", "Virgin Mojito", "Lime juice, mint, soda", MOCKTAILS, 90000));
  private static final Catalog CATALOG = new Catalog(CATEGORIES, ITEMS, "v1", List.of(), false);

  @Test
  void normalizesLikeTheOriginal() {
    assertEquals("hai san", MenuFilters.normalize("Hải sản"));
    assertEquals("dang", MenuFilters.normalize("đăng"));
    assertEquals(198000, MenuFilters.priceWithVAT(ITEMS.get(0)));
  }

  @Test
  void filtersDietSpiceBudgetAndAllergens() {
    List<ObjectNode> vegetarian = MenuFilters.filterItems(ITEMS, Json.obj("diet", "vegetarian"));
    assertEquals(List.of("a2", "a3"), vegetarian.stream().map(item -> Json.str(item, "id")).toList());
    assertEquals(List.of("a3"), MenuFilters.filterItems(vegetarian, Json.obj("mild", true)).stream().map(item -> Json.str(item, "id")).toList());
    assertEquals(3, MenuFilters.filterItems(ITEMS, Json.obj("maxPrice", 170000)).size());
    assertFalse(MenuFilters.filterItems(ITEMS, Json.obj("avoid", List.of("dairy"))).stream().anyMatch(item -> "a3".equals(Json.str(item, "id"))));
  }

  @Test
  void identifiesBeveragesByCategoryAndRecipe() {
    MenuFilters.BeverageProfile mojito = MenuFilters.beverageProfile(ITEMS.get(3), "cocktails cocktails");
    assertEquals(List.of("cocktail"), mojito.types());
    assertEquals("alcoholic", mojito.alcohol());
    MenuFilters.BeverageProfile virgin = MenuFilters.beverageProfile(ITEMS.get(4), "mocktails mocktails");
    assertEquals("none", virgin.alcohol());
    assertTrue(MenuFilters.requestsAlcoholFree("Đồ uống không cồn"));
    assertTrue(MenuFilters.isAlcoholOnlyFollowup("không cồn?"));
    assertEquals(new MenuFilters.BeverageRequest("cocktail", true), MenuFilters.explicitBeverageRequest("cocktail nào ngon nhất"));
    assertNull(MenuFilters.explicitBeverageRequest("cocktail hay bia"));
    assertFalse(MenuFilters.explicitBeverageRequest("cocktail dưới 100k").simple());
  }

  @Test
  void writesDeterministicTastingNotes() {
    assertEquals("It combines richness, gentle acidity, and aromatic notes.", MenuFilters.menuFlavourSummary(Json.obj("description", "tomato, cream, garlic"), "en"));
    assertEquals("Hương vị kết hợp vị béo, chút chua thanh và hương thơm.", MenuFilters.menuFlavourSummary(Json.obj("description", "tomato, cream, garlic"), "vi"));
    assertEquals("Дәмінде қанық дәм, жеңіл қышқылдық, хош иіс үйлеседі.", MenuFilters.menuFlavourSummary(Json.obj("description", "tomato, cream, garlic"), "kz"));
    assertEquals("", MenuFilters.menuFlavourSummary(Json.obj("description", "water"), "en"));
  }

  @Test
  void groundsSubjectsInRealCategoriesAndKeepsRequestedOrder() {
    ObjectNode pasta = MenuSubjects.menuSubject("mì Ý", CATEGORIES);
    assertEquals("pasta", Json.str(pasta, "value"));
    assertEquals(List.of(PASTA), Json.strings(pasta, "categoryIds"));
    List<JsonNode> subjects = List.of(MenuSubjects.menuSubject("pizza", CATEGORIES), MenuSubjects.menuSubject("pasta", CATEGORIES));
    List<ObjectNode> selected = MenuSubjects.selectMenuSubjects(ITEMS, subjects, 4);
    assertEquals(List.of("a3", "a1", "a2"), selected.stream().map(item -> Json.str(item, "id")).toList());
  }

  private static ObjectNode input(String text) {
    return Json.obj("action", "chat", "language", "en", "messages", List.of(Json.obj("role", "user", "content", text)),
      "preferences", Json.obj("diet", "any", "maxPrice", null, "mild", false, "avoid", List.of()));
  }

  private static ObjectNode intent() {
    return Json.obj("diet", "any", "maxPrice", null, "mild", false, "avoid", List.of(), "topic", "menu", "scope", "catalog", "referenceIds", List.of(),
      "keywords", List.of(), "popular", false, "kind", "food", "drinkType", "any", "alcohol", "any", "includeKids", false, "clarification", "", "clarificationReason", "none");
  }

  private static ArrayNode terms(ObjectNode... values) { return Json.arr(List.of(values)); }

  @Test
  void requiresQuotedEvidenceAndVerifiesSpokenBudgets() {
    MenuQueryResolver.Plan invalid = MenuQueryResolver.resolve(intent(), input("I want pasta"), null, "new_search",
      terms(Json.obj("type", "ingredient", "value", "shrimp", "source", "tôm", "scope", "request")), null, CATALOG);
    assertFalse(invalid.valid());

    MenuQueryResolver.Plan budget = MenuQueryResolver.resolve(intent(), input("pasta under 200k"), null, "new_search",
      terms(Json.obj("type", "dish_type", "value", "pasta", "source", "pasta", "scope", "request"),
        Json.obj("type", "budget", "value", "200000", "source", "200k", "scope", "request")), null, CATALOG);
    assertTrue(budget.valid());
    assertEquals(200000, budget.intent().get("maxPrice").asInt());
    assertEquals("spoken", Json.str(budget.intent(), "budgetOrigin"));
    assertEquals(List.of(PASTA), Json.strings(budget.intent().get("subjects").get(0), "categoryIds"));

    assertEquals(150000.0, MenuQueryResolver.quotedBudget(Json.obj("value", "150.000 VND", "source", "150.000đ")));
    assertNull(MenuQueryResolver.quotedBudget(Json.obj("value", "10", "source", "$10")));
  }

  @Test
  void retrievesWithAlcoholFreeRefinementAndCache() {
    MenuRetriever retriever = new MenuRetriever();
    ObjectNode drinks = intent();
    drinks.put("kind", "drink");
    drinks.put("alcohol", "none");
    ObjectNode preferences = Json.obj("diet", "any", "maxPrice", null, "mild", false, "avoid", List.of());
    MenuRetriever.Result first = retriever.retrieve(CATALOG, drinks, preferences, null, false);
    assertEquals(List.of("a5"), first.items().stream().map(item -> Json.str(item, "id")).toList());
    assertEquals("catalog", Json.str(first.retrieval(), "strategy"));
    MenuRetriever.Result second = retriever.retrieve(CATALOG, drinks, preferences, null, false);
    assertEquals("cache", Json.str(second.retrieval(), "strategy"));
    assertEquals(1, first.retrieval().get("rejected").get("alcohol").asInt());
  }

  @Test
  void conversationMemoryRequiresTheMatchingPrecedingTurn() {
    ConversationCache cache = new ConversationCache();
    ChatState state = new ChatState();
    ObjectNode request = input("hello");
    String id = cache.save(null, request.get("messages"), "Hi there", state);
    ArrayNode next = Json.arr(List.of(Json.obj("role", "user", "content", "hello"), Json.obj("role", "assistant", "content", "Hi there"), Json.obj("role", "user", "content", "more")));
    assertEquals(state, cache.read(id, next));
    next.set(1, Json.obj("role", "assistant", "content", "changed"));
    assertNull(cache.read(id, next));
  }
}
