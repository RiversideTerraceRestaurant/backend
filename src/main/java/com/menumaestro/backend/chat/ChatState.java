package com.menumaestro.backend.chat;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Server-side conversation memory and menu-retrieval state for one chat. It is never
 * returned to the browser. Null fields are "absent" (undefined in the original).
 */
public final class ChatState {
  /** The last exhaustive catalog search, kept across detail questions. */
  public record Search(String catalogVersion, MenuRetriever.Query query, List<String> candidateIds) {}

  public JsonNode memory;
  public List<String> questions;
  public String catalogVersion;
  public MenuRetriever.Query query;
  public List<String> candidateIds;
  public ObjectNode intent;
  public Boolean exhaustive;
  public Search search;
  public List<String> selectedIds;
  public ArrayNode selectedItems;

  public ChatState() {}

  /** {...source}: copies every menu field; source may be null. */
  public static ChatState copyOf(ChatState source) {
    ChatState state = new ChatState();
    if (source == null) return state;
    state.memory = source.memory;
    state.questions = source.questions == null ? null : new ArrayList<>(source.questions);
    state.catalogVersion = source.catalogVersion;
    state.query = source.query;
    state.candidateIds = source.candidateIds;
    state.intent = source.intent;
    state.exhaustive = source.exhaustive;
    state.search = source.search;
    state.selectedIds = source.selectedIds;
    state.selectedItems = source.selectedItems;
    return state;
  }

  /** {...source, memory, questions} */
  public static ChatState with(ChatState source, JsonNode memory, List<String> questions) {
    ChatState state = copyOf(source);
    state.memory = memory;
    state.questions = questions == null ? null : new ArrayList<>(questions);
    return state;
  }
}
