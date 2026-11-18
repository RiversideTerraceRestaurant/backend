package com.menumaestro.backend.supabase;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.menumaestro.backend.common.json.Json;

import tools.jackson.databind.JsonNode;

/** Fluent PostgREST query with the same URL/headers as the supabase-js query builder. */
public final class PostgrestQuery {
  private enum Single { NONE, SINGLE, MAYBE }

  private final SupabaseClient client;
  private final String table;
  private final List<String[]> params = new ArrayList<>();
  private final List<String> orders = new ArrayList<>();
  private String method = "GET";
  private String select;
  private boolean countExact;
  private boolean head;
  private JsonNode body;
  private boolean returning;
  private String onConflict;
  private Single single = Single.NONE;

  PostgrestQuery(SupabaseClient client, String table) {
    this.client = client;
    this.table = table;
  }

  // ---------- operations ----------
  public PostgrestQuery select(String columns) { return select(columns, false, false); }

  public PostgrestQuery select(String columns, boolean countExact, boolean head) {
    this.select = cleanColumns(columns);
    if (!"GET".equals(method) && !"HEAD".equals(method)) returning = true;
    this.countExact = countExact;
    this.head = head;
    if (head) method = "HEAD";
    return this;
  }

  /** select() after a mutation: return the written rows. */
  public PostgrestQuery select() { return select("*"); }

  public PostgrestQuery insert(JsonNode values) { method = "POST"; body = values; return this; }

  public PostgrestQuery update(JsonNode values) { method = "PATCH"; body = values; return this; }

  public PostgrestQuery upsert(JsonNode values, String onConflict) { method = "POST"; body = values; this.onConflict = onConflict; return this; }

  public PostgrestQuery delete() { method = "DELETE"; return this; }

  // ---------- filters ----------
  private PostgrestQuery filter(String column, String operator, String value) {
    params.add(new String[] {column, operator + "." + value});
    return this;
  }

  public PostgrestQuery eq(String column, Object value) { return filter(column, "eq", Json.jsString(Json.node(value))); }

  public PostgrestQuery gt(String column, Object value) { return filter(column, "gt", Json.jsString(Json.node(value))); }

  public PostgrestQuery gte(String column, Object value) { return filter(column, "gte", Json.jsString(Json.node(value))); }

  public PostgrestQuery lt(String column, Object value) { return filter(column, "lt", Json.jsString(Json.node(value))); }

  public PostgrestQuery ilike(String column, String pattern) { return filter(column, "ilike", pattern); }

  public PostgrestQuery in(String column, Collection<?> values) {
    String list = values.stream().map(value -> {
      String text = Json.jsString(Json.node(value));
      return text.matches(".*[,()].*") ? "\"" + text + "\"" : text;
    }).collect(Collectors.joining(","));
    params.add(new String[] {column, "in.(" + list + ")"});
    return this;
  }

  public PostgrestQuery or(String filters) {
    params.add(new String[] {"or", "(" + filters + ")"});
    return this;
  }

  public PostgrestQuery order(String column) { return order(column, true); }

  public PostgrestQuery order(String column, boolean ascending) {
    orders.add(column + "." + (ascending ? "asc" : "desc"));
    return this;
  }

  public PostgrestQuery range(int from, int to) {
    params.add(new String[] {"offset", String.valueOf(from)});
    params.add(new String[] {"limit", String.valueOf(to - from + 1)});
    return this;
  }

  public PostgrestQuery limit(int count) {
    params.add(new String[] {"limit", String.valueOf(count)});
    return this;
  }

  public PostgrestQuery single() { single = Single.SINGLE; return this; }

  public PostgrestQuery maybeSingle() { single = Single.MAYBE; return this; }

  // ---------- execution ----------
  public PostgrestResult execute() {
    StringBuilder url = new StringBuilder(client.url()).append("/rest/v1/").append(table);
    List<String[]> query = new ArrayList<>();
    if (select != null) query.add(new String[] {"select", select});
    query.addAll(params);
    if (!orders.isEmpty()) query.add(new String[] {"order", String.join(",", orders)});
    if (onConflict != null) query.add(new String[] {"on_conflict", onConflict});
    if (!query.isEmpty()) {
      url.append('?').append(query.stream().map(pair -> encode(pair[0]) + "=" + encode(pair[1])).collect(Collectors.joining("&")));
    }
    Map<String, String> headers = new LinkedHashMap<>(client.headers());
    List<String> prefer = new ArrayList<>();
    if (onConflict != null) prefer.add("resolution=merge-duplicates");
    if ("POST".equals(method) || "PATCH".equals(method) || "DELETE".equals(method)) prefer.add(returning ? "return=representation" : "return=minimal");
    if (countExact) prefer.add("count=exact");
    if (!prefer.isEmpty()) headers.put("Prefer", String.join(",", prefer));
    byte[] payload = null;
    if (body != null) {
      headers.put("Content-Type", "application/json");
      payload = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
    }
    PostgrestResult raw = client.request(method, url.toString(), headers, payload);
    if (raw.failed() || single == Single.NONE) return raw;
    JsonNode data = raw.data();
    int size = data != null && data.isArray() ? data.size() : 0;
    if (single == Single.MAYBE && size == 0) return new PostgrestResult(Json.F.nullNode(), raw.count(), null);
    if (size != 1) return new PostgrestResult(null, raw.count(), new PostgrestResult.Error("PGRST116", "JSON object requested, multiple (or no) rows returned"));
    return new PostgrestResult(data.get(0), raw.count(), null);
  }

  private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

  /** supabase-js removes whitespace outside double quotes from select strings. */
  private static String cleanColumns(String columns) {
    StringBuilder out = new StringBuilder();
    boolean quoted = false;
    for (char c : columns.toCharArray()) {
      if (Character.isWhitespace(c) && !quoted) continue;
      if (c == '"') quoted = !quoted;
      out.append(c);
    }
    return out.toString();
  }
}
