package com.menumaestro.backend.service.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.menumaestro.backend.util.Js;
import com.menumaestro.backend.util.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Dietary/price filters, beverage identity, flavour notes and explicit beverage requests (menu-filters.js). */
public final class MenuFilters {
  private MenuFilters() {}

  /** normalize(): NFD, strip combining marks, đ→d, lower case. */
  public static String normalize(String value) {
    String text = value == null ? "" : value;
    text = Js.nfd(text).replaceAll("[\\u0300-\\u036f]", "").replace("đ", "d");
    return Js.lower(text);
  }

  public static String fingerprint(String value) { return Js.nfc(normalize(value)); }

  /** Math.round(price * (1 + vat / 100)). */
  public static long priceWithVAT(JsonNode item) {
    double price = numberOf(Json.get(item, "price"));
    JsonNode vatNode = Json.get(item, "vat");
    double vat = Json.truthy(vatNode) ? numberOf(vatNode) : 0;
    return Math.round(price * (1 + vat / 100));
  }

  private static double numberOf(JsonNode value) {
    if (value == null || value.isNull()) return value == null ? Double.NaN : 0;
    if (value.isNumber()) return value.doubleValue();
    if (value.isString()) return Js.toNumber(value.stringValue());
    if (value.isBoolean()) return value.booleanValue() ? 1 : 0;
    return Double.NaN;
  }

  /** `${value}` template-literal conversion. */
  static String tpl(JsonNode value) { return Json.jsString(value); }

  private static final Pattern MEAT = Js.re("\\b(beef|pork|chicken|duck|lamb|bacon|ham|fish|shrimps?|prawns?|seafood|salmon|tuna|anchov(?:y|ies)|sausages?)\\b");
  private static final Pattern DAIRY = Js.re("\\b(milk|cream|butter|cheese|yogurt|parmesan|mozzarella)\\b");
  private static final Map<String, List<String>> SYNONYMS = Map.ofEntries(
    Map.entry("hai san", List.of("seafood", "shrimp", "prawn", "crab", "fish", "squid", "mussel", "oyster", "clam", "lobster")),
    Map.entry("seafood", List.of("seafood", "shrimp", "prawn", "crab", "fish", "squid", "mussel", "oyster", "clam", "lobster")),
    Map.entry("shellfish", List.of("shrimp", "prawn", "crab", "mussel", "oyster", "clam", "lobster", "scallop")),
    Map.entry("nuts", List.of("nut", "peanut", "almond", "cashew", "walnut", "pistachio", "hazelnut", "pecan", "pesto")),
    Map.entry("peanut", List.of("peanut", "groundnut")),
    Map.entry("dau phong", List.of("peanut", "groundnut")), Map.entry("lac", List.of("peanut", "groundnut")),
    Map.entry("sua", List.of("milk", "cream", "butter", "cheese", "yogurt", "parmesan", "mozzarella")),
    Map.entry("dairy", List.of("milk", "cream", "butter", "cheese", "yogurt", "parmesan", "mozzarella")),
    Map.entry("bo", List.of("beef")), Map.entry("thit bo", List.of("beef")), Map.entry("heo", List.of("pork", "bacon", "ham", "sausage")),
    Map.entry("pork", List.of("pork", "bacon", "ham", "sausage")),
    Map.entry("ga", List.of("chicken")), Map.entry("tom", List.of("shrimp", "prawn")), Map.entry("egg", List.of("egg", "mayonnaise", "aioli")),
    Map.entry("gluten", List.of("wheat", "bread", "pasta", "flour", "soy sauce")));

  /** Badges are necessary evidence for dietary claims; descriptions may veto contradictory metadata. */
  public static List<ObjectNode> filterItems(List<ObjectNode> items, JsonNode preferences) {
    JsonNode dietNode = Json.get(preferences, "diet");
    String diet = dietNode == null ? "any" : dietNode.isString() ? dietNode.stringValue() : null;
    JsonNode maxNode = Json.get(preferences, "maxPrice");
    Double maxPrice = maxNode == null || maxNode.isNull() ? null : maxNode.isNumber() ? maxNode.doubleValue() : Double.NaN;
    JsonNode mildNode = Json.get(preferences, "mild");
    boolean mild = mildNode != null && Json.truthy(mildNode);
    List<String> avoid = Json.get(preferences, "avoid") == null ? List.of() : Json.strings(preferences, "avoid");
    List<ObjectNode> result = new ArrayList<>();
    for (ObjectNode item : items) {
      List<String> tags = Json.strings(item, "badges");
      String text = normalize(tpl(Json.get(item, "name")) + " " + tpl(Json.get(item, "description")) + " " + String.join(" ", tags));
      if (maxPrice != null && priceWithVAT(item) > maxPrice) continue;
      if (mild && (tags.contains("spicy") || Js.test("\\b(spicy|chili|chilli|sriracha|jalapeno)\\b", text))) continue;
      if ("vegetarian".equals(diet) && (!(tags.contains("vegetarian") || tags.contains("vegan")) || MEAT.matcher(text).find())) continue;
      if ("vegan".equals(diet) && (!tags.contains("vegan") || MEAT.matcher(text).find() || DAIRY.matcher(text).find() || Js.test("\\b(egg|honey)\\b", text))) continue;
      if ("gluten_free".equals(diet) && !tags.contains("gluten_free")) continue;
      // Existing menu uses the key "dairy" to mean dairy-free.
      if ("dairy_free".equals(diet) && (!tags.contains("dairy") || DAIRY.matcher(text).find())) continue;
      boolean allowed = true;
      for (String term : avoid) {
        String normalized = normalize(term);
        for (String word : SYNONYMS.getOrDefault(normalized, List.of(normalized))) if (text.contains(word)) { allowed = false; break; }
        if (!allowed) break;
      }
      if (allowed) result.add(item);
    }
    return result;
  }

  /** Beverage identity comes from its category/recipe, not a fruit-juice ingredient in a cocktail. */
  public record BeverageProfile(List<String> types, String alcohol) {
    public ObjectNode toJson() { return Json.obj("types", types, "alcohol", alcohol); }
  }

  public static BeverageProfile beverageProfile(JsonNode item, String category) {
    String group = Js.replaceAll(normalize(category), "[_-]", " ");
    String text = normalize(tpl(Json.get(item, "name")) + " " + tpl(Json.get(item, "description")));
    List<String> types = new ArrayList<>();
    boolean mocktail = Js.test("\\bmocktails?\\b", group);
    boolean cocktail = Js.test("\\bcocktails?\\b", group);
    boolean softDrink = Js.test("\\b(?:soft drinks?|juices?|smoothies?|beverages?)\\b", group);
    boolean coffee = Js.test("\\bcoffee\\b", group);
    boolean tea = Js.test("\\btea\\b", group);
    boolean beer = Js.test("\\bbeers?\\b", group);
    boolean wine = Js.test("\\b(?:wines?|prosecco)\\b", group);
    boolean spirit = Js.test("\\bspirits?\\b", group);
    boolean liquor = Js.test("\\b(?:vodka|gin|rum|tequila|whisk(?:e)?y|bourbon|brandy|cognac|liqueur|aperol|campari|cointreau|amaretto|baileys|vermouth|schnapps|sake|bacardi|kahlua)\\b", text);
    boolean alcoholFree = Js.test("\\b(?:non[ -]?alcoholic|alcohol[ -]?free|zero[ -]?proof)\\b|(?:^|\\s)0[.,]0\\s*%", text);
    // Explicitly listed liquor vetoes an otherwise non-alcoholic category.
    boolean alcoholic = liquor || cocktail || spirit || (!alcoholFree && ((beer || wine) || Js.test("\\b(?:wine|beer|prosecco|champagne|cider)\\b", text)));
    boolean nonAlcoholic = !alcoholic && (mocktail || softDrink || coffee || tea || alcoholFree);
    if (mocktail) types.add("mocktail");
    if (cocktail) types.add("cocktail");
    if (softDrink) types.add("soft_drink");
    if (coffee) types.add("coffee");
    if (tea || (softDrink && Js.test("\\btea\\b", text))) types.add("tea");
    if (spirit) types.add("spirit");
    // Some menus combine beer and wine in one category: distinguish them by the recipe/name.
    if (beer && (!wine || Js.test("\\b(?:beer|lager|ale|pilsner|stout|ipa|heineken|tiger|saigon|huda)\\b", text))) types.add("beer");
    if (wine && (!beer || Js.test("\\b(?:wine|prosecco|merlot|cabernet|chardonnay|sauvignon|shiraz|pinot|montepulciano)\\b", text))) types.add("wine");
    if (nonAlcoholic && !mocktail && !coffee && !tea && softDrink
      && Js.test("\\b(?:juice|smoothie)\\b", text)
      && !Js.test("\\b(?:tea|coffee|milk|cream|yogurt|soda|tonic|ginger ale)\\b", text)) types.add("juice");
    return new BeverageProfile(List.copyOf(types), alcoholic ? "alcoholic" : nonAlcoholic ? "none" : "unknown");
  }

  private static final Pattern ALCOHOL_FREE = Js.re("\\b(?:khong\\s+(?:co\\s+)?con|non[ -]?alcoholic|alcohol[ -]?free|no alcohol|without alcohol|sans alcool|sin alcohol|senza alcol)\\b|без\\s+алкоголя|безалкоголь|алкогольсіз|спиртсіз|무알(?:코올|콜)|无酒精|無酒精|ノンアルコール|アルコールなし");
  private static final Set<String> ALCOHOL_ONLY = Set.of(
    "khong con", "khong co con", "do uong khong con", "non-alcoholic", "non alcoholic",
    "alcohol-free", "alcohol free", "no alcohol", "without alcohol", "sans alcool", "sin alcohol",
    "senza alcol", "без алкоголя", "безалкогольное", "алкогольсіз", "спиртсіз",
    "무알코올", "무알콜", "无酒精", "無酒精", "ノンアルコール", "アルコールなし");

  public static boolean requestsAlcoholFree(String text) { return ALCOHOL_FREE.matcher(fingerprint(text)).find(); }

  public static boolean isAlcoholOnlyFollowup(String text) {
    return ALCOHOL_ONLY.contains(Js.trim(Js.replaceAll(fingerprint(text), "[?？!！.,。]", "")));
  }

  private static final List<Object[]> FLAVOUR_NOTES = List.of(
    new Object[] {"\\bsmoked salmon\\b", "Smoked salmon: rich, smoky flavour."},
    new Object[] {"\\bsalmon\\b", "Salmon: richness; no doneness or texture is confirmed."},
    new Object[] {"\\b(?:cream|cream cheese|mozzarella)\\b", "Cream or cheese: creamy richness; do not call it sweet or crisp."},
    new Object[] {"\\bparmesan\\b", "Parmesan: savoury, salty depth; no crispness is stated."},
    new Object[] {"\\btomato(?:es)?\\b", "Tomato: mild acidity balancing rich ingredients."},
    new Object[] {"\\bcapers?\\b", "Capers: salty, tangy contrast."},
    new Object[] {"\\bgarlic\\b", "Garlic: aromatic, savoury character."},
    new Object[] {"\\borange\\b", "Orange: citrus aroma and sweet-tart character."},
    new Object[] {"\\bbutter\\b", "Butter: richness."},
    new Object[] {"\\bsweet potatoes\\b", "Sweet potatoes: mild sweetness."},
    new Object[] {"\\bpesto\\b", "Pesto: herbal aroma; do not assume extra ingredients."},
    new Object[] {"\\b(?:rocket|rockets|arugula|mint|dill)|thi la", "Listed herbs: herbal aroma; do not infer chili heat."},
    new Object[] {"\\blychee(?:s)?\\b", "Lychee: sweetness and floral aroma."},
    new Object[] {"\\bpineapple\\b", "Pineapple: sweet-tart tropical flavour."},
    new Object[] {"\\bmelon\\b", "Melon: mild sweetness; its variety is unspecified, so do not say watermelon."},
    new Object[] {"\\b(?:crispy|crunchy|toasted bread)\\b", "The stated crispy/crunchy/toasted-bread component may add a crisp contrast."});

  /** Grounds flavour prose in known ingredient characteristics, without inventing recipe details. */
  public static List<String> menuFlavourNotes(JsonNode item) {
    String text = normalize(Json.str(item, "description"));
    List<String> notes = new ArrayList<>();
    for (Object[] entry : FLAVOUR_NOTES) if (Js.test((String) entry[0], text)) notes.add((String) entry[1]);
    return notes;
  }

  private static final List<Object[]> PROFILES = List.of(
    new Object[] {"rich", "\\b(?:salmon|cream|cheese|butter|parmesan|mozzarella)\\b"},
    new Object[] {"tart", "\\b(?:tomato(?:es)?|orange|pineapple|lime|lemon|capers?)\\b"},
    new Object[] {"salty", "\\b(?:parmesan|capers?|olives?)\\b"},
    new Object[] {"aromatic", "\\b(?:garlic|pesto|rocket|rockets|arugula|mint|dill)\\b|thi la"},
    new Object[] {"sweet", "\\b(?:lychee(?:s)?|melon|orange|pineapple|honey|sweet potatoes)\\b"},
    new Object[] {"smoky", "\\bsmoked\\b"},
    new Object[] {"spicy", "\\b(?:spicy|chili|chilli|jalapeno|sriracha)\\b"});
  private static final List<String> PROFILE_KEYS = List.of("rich", "tart", "salty", "aromatic", "sweet", "smoky", "spicy");
  private static final Map<String, List<String>> WORDING = Map.of(
    "en", List.of("It combines {notes}.", "richness", "gentle acidity", "salty depth", "aromatic notes", "sweetness", "smoky notes", "chili heat"),
    "vi", List.of("Hương vị kết hợp {notes}.", "vị béo", "chút chua thanh", "vị mặn đậm đà", "hương thơm", "vị ngọt", "hương khói", "vị cay"),
    "ko", List.of("{notes}이 어우러집니다.", "풍부한 맛", "은은한 산미", "짭짤한 풍미", "향긋한 풍미", "단맛", "훈연 향", "매운맛"),
    "ja", List.of("{notes}が調和します。", "コク", "穏やかな酸味", "塩味のうまみ", "香り", "甘み", "燻製の香り", "辛み"),
    "cn", List.of("风味融合了{notes}。", "醇厚感", "清爽酸味", "咸鲜味", "清香", "甜味", "烟熏香气", "辣味"),
    "ru", List.of("Во вкусе сочетаются {notes}.", "насыщенность", "лёгкая кислинка", "солоноватые ноты", "ароматические ноты", "сладость", "дымные ноты", "острота"),
    "kz", List.of("Дәмінде {notes} үйлеседі.", "қанық дәм", "жеңіл қышқылдық", "тұзды реңк", "хош иіс", "тәттілік", "ысталған хош иіс", "ащылық"),
    "es", List.of("Combina {notes}.", "untuosidad", "acidez suave", "notas saladas", "notas aromáticas", "dulzor", "notas ahumadas", "picante"),
    "fr", List.of("Les saveurs associent {notes}.", "rondeur", "acidité douce", "notes salées", "notes aromatiques", "douceur", "notes fumées", "piquant"),
    "it", List.of("Il gusto unisce {notes}.", "ricchezza", "delicata acidità", "note sapide", "note aromatiche", "dolcezza", "note affumicate", "piccantezza"));

  /** Deterministic tasting notes that also work during provider rate limits; no extra AI call. */
  public static String menuFlavourSummary(JsonNode item, String language) {
    String text = normalize(Json.str(item, "description"));
    List<String> profiles = new ArrayList<>();
    for (Object[] entry : PROFILES) if (Js.test((String) entry[1], text)) profiles.add((String) entry[0]);
    if (profiles.isEmpty()) return "";
    List<String> words = WORDING.getOrDefault(language, WORDING.get("en"));
    List<String> labels = profiles.stream().map(key -> words.get(PROFILE_KEYS.indexOf(key) + 1)).toList();
    return words.get(0).replace("{notes}", listFormat(language, labels));
  }

  /** Intl.ListFormat(locale, {style: 'long', type: 'conjunction'}) for the supported languages. */
  static String listFormat(String language, List<String> values) {
    int size = values.size();
    if (size == 0) return "";
    if (size == 1) return values.get(0);
    String head = String.join(switch (language) { case "ja" -> "、"; case "cn" -> "、"; default -> ", "; }, values.subList(0, size - 1));
    String last = values.get(size - 1);
    return switch (language) {
      case "vi" -> head + " và " + last;
      case "ko" -> head + " 및 " + last;
      case "ja" -> head + "、" + last;
      case "cn" -> head + "和" + last;
      case "ru" -> head + " и " + last;
      case "kz" -> size == 2 ? head + " және " + last : head + ", " + last;
      case "es" -> head + " y " + last;
      case "fr" -> head + " et " + last;
      case "it" -> head + " e " + last;
      default -> size == 2 ? head + " and " + last : head + ", and " + last;
    };
  }

  /** Result of {@link #explicitBeverageRequest}: the single requested category and whether the request is unqualified. */
  public record BeverageRequest(String type, boolean simple) {}

  private static final List<Object[]> BEVERAGE_PATTERNS = List.of(
    new Object[] {"mocktail", "\\bmocktails?\\b|моктеил[а-я]*|モクテル"},
    new Object[] {"cocktail", "\\b(?:cocktails?|cocteles?|coctel)\\b|коктеил[а-я]*|칵테일|カクテル|鸡尾酒|雞尾酒"},
    new Object[] {"juice", "\\b(?:fruit juice|juices?|nuoc ep|jugos?|zumos?|succo|jus)\\b|果汁|주스|ジュース|шырын[а-я]*|\\bсок(?:и|а|ов)?\\b"},
    new Object[] {"coffee", "\\b(?:coffee|ca phe|cafe|caffe)\\b|커피|コーヒー|咖啡|кофе"},
    new Object[] {"beer", "\\b(?:beers?|bia|cerveza|birra|biere)\\b|맥주|ビール|啤酒|пиво"},
    new Object[] {"wine", "\\b(?:wines?|ruou vang|vino|vin|prosecco)\\b|와인|ワイン|葡萄酒|вино"},
    new Object[] {"tea", "\\b(?:tea|tra)\\b|紅茶|緑茶|奶茶|чай"},
    new Object[] {"soft_drink", "\\b(?:soft drinks?|nuoc ngot|soda)\\b"},
    new Object[] {"spirit", "\\b(?:spirits?|ruou manh)\\b"});

  /** Explicit category names constrain retrieval even when the intent model omits the subtype. */
  public static BeverageRequest explicitBeverageRequest(String message) {
    String text = fingerprint(message);
    // A recipe/detail question mentioning juice or coffee is not a request to switch categories.
    if (Js.test("thanh phan|nguyen lieu|co chua|ingredients?|contains?|made (?:of|with)|thanh toan|opening hours|dia chi", text)) return null;
    record Found(String type, int start, int end) {}
    List<Found> found = new ArrayList<>();
    for (Object[] entry : BEVERAGE_PATTERNS) {
      Matcher matcher = Js.re((String) entry[1], "gu").matcher(text);
      while (matcher.find()) {
        String before = text.substring(Math.max(0, matcher.start() - 45), matcher.start());
        if (Js.test("(?:khong (?:muon|thich|lay|chon)|avoid|exclude|without|instead of|no|not|pas de|sin|senza)\\s*$", before)) continue;
        found.add(new Found((String) entry[0], matcher.start(), matcher.end()));
      }
    }
    Set<String> types = new HashSet<>();
    for (Found match : found) types.add(match.type());
    if (types.size() != 1) return null;
    String type = found.get(0).type();
    String remainder = text;
    List<Found> ordered = new ArrayList<>(found);
    ordered.sort((a, b) -> b.start() - a.start());
    for (Found match : ordered) remainder = remainder.substring(0, match.start()) + " " + remainder.substring(match.end());
    // Only an unqualified category recommendation can bypass a failed intent provider.
    String rest = Js.replaceAll(remainder, "\\b(?:t|toi|minh|tao|ban|cho|xin|nha|hang|co|the|vay|thi|nao|gi|ngon|nhat|hay|tot|goi|y|muon|uong|thu|loai|mon|mot|nhung|cac|khong|please|what|which|is|are|the|best|good|recommend|suggest|me|a|an|some|any|do|you|have)\\b", "");
    rest = Js.replaceAll(rest, "[\\s?!？!.,。]+", "");
    return new BeverageRequest(type, rest.isEmpty());
  }

  static <T> List<T> unique(Collection<T> values) { return new ArrayList<>(new java.util.LinkedHashSet<>(values)); }
}
