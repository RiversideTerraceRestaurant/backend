package com.menumaestro.backend.client.supabase;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.menumaestro.backend.util.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Minimal Supabase client (PostgREST, GoTrue auth, Storage) speaking the same REST
 * protocol as supabase-js, authenticated either with the API key or a user JWT so
 * row-level security behaves exactly as before.
 */
public class SupabaseClient {
  private final String url;
  private final String apiKey;
  private final String accessToken;
  private final Duration timeout;

  public SupabaseClient(String url, String apiKey, String accessToken, Duration timeout) {
    this.url = url.replaceAll("/+$", "");
    this.apiKey = apiKey;
    this.accessToken = accessToken;
    this.timeout = timeout;
  }

  public String url() { return url; }

  /** A client bound to a signed-in user's access token. */
  public SupabaseClient withToken(String token) { return new SupabaseClient(url, apiKey, token, timeout); }

  Map<String, String> headers() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("apikey", apiKey);
    headers.put("Authorization", "Bearer " + (accessToken != null ? accessToken : apiKey));
    return headers;
  }

  public PostgrestQuery from(String table) { return new PostgrestQuery(this, table); }

  public PostgrestResult rpc(String function, JsonNode params) {
    Map<String, String> headers = headers();
    headers.put("Content-Type", "application/json");
    return request("POST", url + "/rest/v1/rpc/" + function, headers, Json.stringify(params == null ? Json.obj() : params).getBytes(StandardCharsets.UTF_8));
  }

  /** Executes a REST call and maps it to {data, count, error} without throwing. */
  public PostgrestResult request(String method, String target, Map<String, String> headers, byte[] body) {
    try {
      SupabaseHttp.Response response = SupabaseHttp.send(method, target, headers, body, timeout);
      Long count = null;
      String range = response.header("content-range");
      if (range != null && range.contains("/")) {
        String total = range.substring(range.indexOf('/') + 1);
        if (!"*".equals(total)) try { count = Long.parseLong(total); } catch (NumberFormatException ignored) { count = null; }
      }
      if (!response.ok()) {
        String code = "";
        String message = response.text();
        try {
          JsonNode error = Json.parse(response.text());
          code = Json.str(error, "code", "");
          message = Json.str(error, "message", message);
        } catch (RuntimeException ignored) { /* Non-JSON error body. */ }
        return new PostgrestResult(null, count, new PostgrestResult.Error(code, message));
      }
      JsonNode data = null;
      if (!"HEAD".equals(method) && response.body().length > 0) data = Json.parse(response.text());
      return new PostgrestResult(data, count, null);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return new PostgrestResult(null, null, new PostgrestResult.Error("", "Request interrupted"));
    } catch (Exception error) {
      return new PostgrestResult(null, null, new PostgrestResult.Error("", "TypeError: fetch failed"));
    }
  }

  // ---------- auth (GoTrue) ----------
  /** A session as stored in the encrypted cookie. */
  public record Session(String accessToken, String refreshToken, long expiresAt, JsonNode user) {}

  public record AuthResult(Session session, String error) {}

  private AuthResult token(String grant, JsonNode body) {
    Map<String, String> headers = headers();
    headers.put("Content-Type", "application/json");
    PostgrestResult result = request("POST", url + "/auth/v1/token?grant_type=" + grant, headers, Json.stringify(body).getBytes(StandardCharsets.UTF_8));
    if (result.failed() || result.data() == null || !Json.has(result.data(), "access_token")) {
      return new AuthResult(null, result.failed() ? result.error().message() : "Invalid session");
    }
    JsonNode data = result.data();
    long expiresAt = Json.has(data, "expires_at") ? data.get("expires_at").asLong()
      : Instant.now().getEpochSecond() + (Json.has(data, "expires_in") ? data.get("expires_in").asLong() : 3600);
    return new AuthResult(new Session(Json.str(data, "access_token"), Json.str(data, "refresh_token"), expiresAt, data.get("user")), null);
  }

  public AuthResult signInWithPassword(String email, String password) {
    ObjectNode body = Json.obj("email", email, "password", password);
    body.set("gotrue_meta_security", Json.obj());
    return token("password", body);
  }

  public AuthResult refreshSession(String refreshToken) { return token("refresh_token", Json.obj("refresh_token", refreshToken)); }

  /** GET /auth/v1/user for the given JWT; null when the token is invalid. */
  public JsonNode getUser(String jwt) {
    Map<String, String> headers = headers();
    headers.put("Authorization", "Bearer " + jwt);
    PostgrestResult result = request("GET", url + "/auth/v1/user", headers, null);
    if (result.failed() || result.data() == null || Json.str(result.data(), "id") == null) return null;
    return result.data();
  }

  /** auth.admin.signOut(jwt, 'local'): errors are reported, never thrown. */
  public void signOut(String jwt) {
    Map<String, String> headers = headers();
    headers.put("Authorization", "Bearer " + jwt);
    request("POST", url + "/auth/v1/logout?scope=local", headers, null);
  }

  // ---------- storage ----------
  public PostgrestResult upload(String bucket, String path, byte[] bytes, String contentType, String cacheControl) {
    Map<String, String> headers = headers();
    headers.put("Content-Type", contentType);
    headers.put("cache-control", "max-age=" + cacheControl);
    headers.put("x-upsert", "false");
    return request("POST", url + "/storage/v1/object/" + bucket + "/" + encodePath(path), headers, bytes);
  }

  public PostgrestResult remove(String bucket, List<String> paths) {
    Map<String, String> headers = headers();
    headers.put("Content-Type", "application/json");
    return request("DELETE", url + "/storage/v1/object/" + bucket, headers, Json.stringify(Json.obj("prefixes", paths)).getBytes(StandardCharsets.UTF_8));
  }

  /** Downloads an object; returns the bytes or null on error. */
  public byte[] download(String bucket, String path) {
    try {
      SupabaseHttp.Response response = SupabaseHttp.send("GET", url + "/storage/v1/object/" + bucket + "/" + encodePath(path), headers(), null, timeout);
      return response.ok() ? response.body() : null;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return null;
    } catch (Exception error) {
      return null;
    }
  }

  public String publicUrl(String bucket, String path) { return url + "/storage/v1/object/public/" + bucket + "/" + encodePath(path); }

  private static String encodePath(String path) {
    StringBuilder out = new StringBuilder();
    for (String segment : path.split("/", -1)) {
      if (!out.isEmpty()) out.append('/');
      out.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
    }
    return out.toString();
  }
}
