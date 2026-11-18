package com.menumaestro.backend.common.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.menumaestro.backend.common.json.Json;
import com.menumaestro.backend.common.validation.ValidationException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.node.ObjectNode;

/** Writes the API's JSON error body: {error, code?}, with Retry-After on 429. */
public final class ErrorResponses {
  private static final Logger log = LoggerFactory.getLogger(ErrorResponses.class);

  private ErrorResponses() {}

  public static int status(Throwable error) {
    if (error instanceof ValidationException) return 400;
    if (error instanceof ApiException api) return api.status();
    return 500;
  }

  public static ObjectNode body(Throwable error) {
    int status = status(error);
    String message;
    if (error instanceof ValidationException) message = error.getMessage();
    else if (status == 500) message = "Something went wrong. Please try again.";
    else message = error.getMessage();
    ObjectNode body = Json.obj("error", message);
    if (error instanceof ApiException api && api.code() != null && status < 500) body.put("code", api.code());
    return body;
  }

  public static void write(HttpServletRequest request, HttpServletResponse response, Throwable error) throws IOException {
    int status = status(error);
    if (status >= 500) log.error("API request failed: {} {} {}", request.getMethod(), request.getRequestURI(), error.getClass().getSimpleName(), status == 500 ? error : null);
    if (response.isCommitted()) return;
    response.resetBuffer();
    if (status == 429) response.setHeader("Retry-After", "60");
    response.setStatus(status);
    response.setContentType(Responses.JSON);
    byte[] bytes = Json.stringify(body(error)).getBytes(StandardCharsets.UTF_8);
    response.setContentLength(bytes.length);
    response.getOutputStream().write(bytes);
  }
}
