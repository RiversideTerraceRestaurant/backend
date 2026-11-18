package com.menumaestro.backend.chat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.menumaestro.backend.common.json.Js;
import com.menumaestro.backend.common.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Requested dish groups grounded against real categories (menu-subjects.js). */
public final class MenuSubjects {
  private MenuSubjects() {}

  private static final Map<String, String> ALIASES = Map.of("mi y", "pasta", "italian pasta", "pasta", "italian noodles", "pasta");

  private static String key(String value) { return Js.trim(Js.replaceAll(Js.replaceAll(MenuFilters.normalize(value), "[_-]+", " "), "\\s+", " ")); }

  private static String singular(String value) {
    return Js.re("\\b([a-z]{3,})s\\b").matcher(value).replaceAll("$1");
  }

  /** {value, categoryIds} as a JSON object (it is stored in intents and sent to the model). */
  public static ObjectNode menuSubject(String value, List<? extends JsonNode> categories) {
    String canonical = ALIASES.getOrDefault(key(value), key(value));
    String term = singular(canonical);
    List<String> ids = new ArrayList<>();
    if (categories != null) {
      for (JsonNode category : categories) {
        boolean matches = false;
        for (String name : new String[] {Json.str(category, "name"), Json.str(category, "display_name")}) {
          String label = singular(key(name));
          if (label.equals(term) || (" " + label + " ").contains(" " + term + " ")) { matches = true; break; }
        }
        if (matches) ids.add(Json.str(category, "id"));
      }
    }
    return Json.obj("value", canonical, "categoryIds", ids);
  }

  public static boolean matchesMenuSubject(JsonNode item, JsonNode subject, String text) {
    List<String> categoryIds = Json.strings(subject, "categoryIds");
    if (!categoryIds.isEmpty()) return categoryIds.contains(Json.str(item, "category_id"));
    String haystack = text != null ? text : MenuFilters.normalize(MenuFilters.tpl(Json.get(item, "name")) + " " + MenuFilters.tpl(Json.get(item, "description")));
    return haystack.contains(MenuFilters.normalize(Json.str(subject, "value")));
  }

  /** Multiple explicitly requested groups get coverage, in the guest's order; otherwise the first `limit` candidates. */
  public static List<ObjectNode> selectMenuSubjects(List<ObjectNode> candidates, List<JsonNode> subjects, int limit) {
    if (subjects == null || subjects.size() < 2) return new ArrayList<>(candidates.subList(0, Math.min(limit, candidates.size())));
    Set<String> assigned = new HashSet<>();
    List<List<ObjectNode>> pools = new ArrayList<>();
    for (JsonNode subject : subjects) {
      List<ObjectNode> pool = new ArrayList<>();
      for (ObjectNode item : candidates) {
        String id = Json.str(item, "id");
        if (assigned.contains(id) || !matchesMenuSubject(item, subject, null)) continue;
        assigned.add(id);
        pool.add(item);
      }
      pools.add(pool);
    }
    List<List<ObjectNode>> selected = new ArrayList<>();
    for (int i = 0; i < pools.size(); i++) selected.add(new ArrayList<>());
    int count = 0;
    for (int round = 0; count < limit; round++) {
      boolean added = false;
      for (int group = 0; group < pools.size() && count < limit; group++) {
        if (round >= pools.get(group).size()) continue;
        selected.get(group).add(pools.get(group).get(round));
        count++;
        added = true;
      }
      if (!added) break;
    }
    List<ObjectNode> result = new ArrayList<>();
    selected.forEach(result::addAll);
    return result;
  }
}
