package com.menumaestro.backend.service.chat;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

import com.menumaestro.backend.model.ChatState;
import com.menumaestro.backend.service.CatalogService;
import com.menumaestro.backend.util.Js;
import com.menumaestro.backend.util.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * Opaque conversation IDs isolate tabs/guests. Bounded TTL storage in memory; the state is
 * only reused when the client echoes the exact preceding guest/assistant turn.
 */
public class ConversationCache {
  private record Entry(long until, ChatState state, String turn) {}

  private final long ttl;
  private final int maxEntries;
  private final LongSupplier now;
  private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

  public ConversationCache() { this(30 * 60_000L, 500, System::currentTimeMillis); }

  public ConversationCache(long ttl, int maxEntries, LongSupplier now) {
    this.ttl = ttl;
    this.maxEntries = maxEntries;
    this.now = now;
  }

  private static String turnHash(JsonNode... messages) {
    ArrayNode list = Json.arr();
    for (JsonNode message : messages) list.add(Json.obj("role", Json.str(message, "role"), "content", Js.trim(Json.str(message, "content", ""))));
    return CatalogService.sha256(Json.stringify(list));
  }

  private static String precedingTurn(JsonNode messages) {
    int size = messages.size();
    if (size < 3) return null;
    JsonNode assistant = messages.get(size - 2);
    JsonNode user = messages.get(size - 3);
    if (!"assistant".equals(Json.str(assistant, "role")) || !"user".equals(Json.str(user, "role"))) return null;
    return turnHash(user, assistant);
  }

  public synchronized ChatState read(String id, JsonNode messages) {
    if (id == null) return null;
    Entry entry = entries.get(id);
    if (entry == null) return null;
    if (entry.until() <= now.getAsLong()) { entries.remove(id); return null; }
    if (!entry.turn().equals(precedingTurn(messages))) return null;
    return entry.state();
  }

  public synchronized String save(String id, JsonNode messages, String reply, ChatState state) {
    long time = now.getAsLong();
    entries.values().removeIf(entry -> entry.until() <= time);
    String conversationId = id != null ? id : UUID.randomUUID().toString();
    entries.remove(conversationId);
    entries.put(conversationId, new Entry(time + ttl, state, turnHash(messages.get(messages.size() - 1), Json.obj("role", "assistant", "content", Js.slice(reply, 1000)))));
    if (entries.size() > maxEntries) {
      Iterator<Map.Entry<String, Entry>> oldest = entries.entrySet().iterator();
      oldest.next();
      oldest.remove();
    }
    return conversationId;
  }
}
