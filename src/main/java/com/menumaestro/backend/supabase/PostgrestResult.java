package com.menumaestro.backend.supabase;

import com.menumaestro.backend.common.web.ApiException;

import tools.jackson.databind.JsonNode;

/** Result of a PostgREST call: {data, count, error}, as returned by supabase-js. */
public record PostgrestResult(JsonNode data, Long count, Error error) {
  /** PostgREST/RPC error. {@code code} is the Postgres SQLSTATE or PostgREST code. */
  public record Error(String code, String message) {}

  public boolean failed() { return error != null; }

  /** The original unwrap(): any database error becomes a generic 502. */
  public JsonNode unwrap() {
    if (error != null) throw ApiException.database();
    return data;
  }
}
