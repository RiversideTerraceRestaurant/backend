package com.menumaestro.backend.util;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import tools.jackson.databind.JsonNode;

/** JSON responses with the exact content type of the original API. */
public final class Responses {
  public static final String JSON = "application/json; charset=utf-8";
  public static final MediaType JSON_TYPE = MediaType.parseMediaType(JSON);

  private Responses() {}

  public static ResponseEntity<String> ok(Object body) { return status(200, body); }

  public static ResponseEntity<String> created(Object body) { return status(201, body); }

  public static ResponseEntity<String> status(int status, Object body) {
    JsonNode node = Json.node(body);
    return ResponseEntity.status(status).contentType(JSON_TYPE).body(Json.stringify(node));
  }
}
