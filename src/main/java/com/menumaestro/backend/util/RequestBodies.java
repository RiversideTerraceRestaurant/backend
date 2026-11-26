package com.menumaestro.backend.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.menumaestro.backend.exception.ApiException;

import jakarta.servlet.http.HttpServletRequest;

import tools.jackson.databind.JsonNode;

/** Reads request bodies with the original size limits, content-type check and JSON errors. */
public final class RequestBodies {
  public static final int DEFAULT_LIMIT = 65536;

  private RequestBodies() {}

  public static byte[] raw(HttpServletRequest request, int limit) {
    try (InputStream input = request.getInputStream()) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      byte[] chunk = new byte[16384];
      int total = 0;
      int read;
      while ((read = input.read(chunk)) != -1) {
        total += read;
        if (total > limit) throw new ApiException(413, "Request is too large.");
        buffer.write(chunk, 0, read);
      }
      return buffer.toByteArray();
    } catch (IOException error) {
      throw new ApiException(400, "Invalid JSON.");
    }
  }

  public static JsonNode json(HttpServletRequest request) { return json(request, DEFAULT_LIMIT); }

  public static JsonNode json(HttpServletRequest request, int limit) {
    byte[] bytes = raw(request, limit);
    String type = request.getContentType() == null ? "" : request.getContentType();
    if (!type.startsWith("application/json")) throw new ApiException(415, "Send application/json.");
    try {
      return Json.parse(new String(bytes, StandardCharsets.UTF_8));
    } catch (RuntimeException error) {
      throw new ApiException(400, "Invalid JSON.");
    }
  }
}
