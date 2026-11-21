package com.menumaestro.backend.media;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.menumaestro.backend.common.json.Json;
import com.menumaestro.backend.common.web.ApiException;
import com.menumaestro.backend.config.AppConfig;
import com.menumaestro.backend.supabase.SupabaseHttp;

import tools.jackson.databind.JsonNode;

/**
 * Minimal signed Cloudinary upload/destroy client for menu and table photos. Images are
 * already encoded as WebP by {@code ImageService}, so they are stored and delivered as-is
 * (no on-the-fly transformations, which would spend transformation credits).
 */
@Component
public class CloudinaryClient {
  private static final Logger log = LoggerFactory.getLogger(CloudinaryClient.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(60);

  private final AppConfig.Cloudinary account;

  @Autowired
  public CloudinaryClient(AppConfig config) { this.account = config.cloudinary(); }

  public CloudinaryClient(AppConfig.Cloudinary account) { this.account = account; }

  public boolean configured() { return account != null && account.configured(); }

  private void requireConfigured() {
    if (!configured()) throw new ApiException(503, "Image storage is not configured. Set CLOUDINARY_URL on the server.");
  }

  /** Uploads an image under {@code folder} with a random name and returns its HTTPS delivery URL. */
  public String upload(byte[] bytes, String folder, String contentType) {
    requireConfigured();
    String publicId = folder.replaceAll("^/+|/+$", "") + "/" + UUID.randomUUID();
    Map<String, String> params = new TreeMap<>();
    params.put("public_id", publicId);
    params.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
    params.put("overwrite", "false");
    String signature = sign(params, account.apiSecret());
    String boundary = "----rtr" + UUID.randomUUID().toString().replace("-", "");
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    Map<String, String> fields = new LinkedHashMap<>(params);
    fields.put("api_key", account.apiKey());
    fields.put("signature", signature);
    for (Map.Entry<String, String> field : fields.entrySet()) {
      body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n" + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
    String extension = contentType.endsWith("webp") ? "webp" : "img";
    body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"upload." + extension + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
    body.writeBytes(bytes);
    body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    JsonNode result = call("upload", "multipart/form-data; boundary=" + boundary, body.toByteArray());
    String url = Json.str(result, "secure_url");
    if (url == null || url.isEmpty()) throw new ApiException(502, "Image storage returned an invalid response.");
    return url;
  }

  /** Deletes an image this account stored; ignores URLs from elsewhere. Never throws. */
  public void destroy(String url) {
    String publicId = configured() ? publicIdFromUrl(url, account.cloudName()) : null;
    if (publicId == null) return;
    try {
      Map<String, String> params = new TreeMap<>();
      params.put("invalidate", "true");
      params.put("public_id", publicId);
      params.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
      Map<String, String> fields = new LinkedHashMap<>(params);
      fields.put("api_key", account.apiKey());
      fields.put("signature", sign(params, account.apiSecret()));
      String form = fields.entrySet().stream()
        .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&"));
      call("destroy", "application/x-www-form-urlencoded", form.getBytes(StandardCharsets.UTF_8));
    } catch (RuntimeException error) {
      log.warn(Json.stringify(Json.obj("event", "cloudinary.destroy_failed", "publicId", publicId, "error", error.getMessage())));
    }
  }

  public boolean owns(String url) { return configured() && publicIdFromUrl(url, account.cloudName()) != null; }

  private JsonNode call(String action, String contentType, byte[] body) {
    String endpoint = "https://api.cloudinary.com/v1_1/" + URLEncoder.encode(account.cloudName(), StandardCharsets.UTF_8) + "/image/" + action;
    try {
      SupabaseHttp.Response response = SupabaseHttp.send("POST", endpoint, Map.of("Content-Type", contentType), body, TIMEOUT);
      JsonNode json = parse(response.text());
      if (!response.ok()) {
        String message = json == null ? null : Json.str(json.get("error"), "message");
        log.warn(Json.stringify(Json.obj("event", "cloudinary." + action + "_failed", "status", response.status(), "error", message == null ? "" : message)));
        throw new ApiException(502, "Could not save the image. Please try again.");
      }
      if (json == null) throw new ApiException(502, "Image storage returned an invalid response.");
      return json;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new ApiException(503, "Image upload was interrupted.");
    } catch (java.io.IOException error) {
      throw new ApiException(502, "Cannot reach the image storage. Please try again.");
    }
  }

  private static JsonNode parse(String text) {
    try { return Json.parse(text); } catch (RuntimeException error) { return null; }
  }

  /** Cloudinary API signature: SHA-1 of the sorted "key=value" pairs joined by '&' plus the secret. */
  static String sign(Map<String, String> params, String secret) {
    String payload = new TreeMap<>(params).entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining("&"));
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest((payload + secret).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private static final Pattern DELIVERY = Pattern.compile("^https://res\\.cloudinary\\.com/([^/]+)/image/upload/(?:v\\d+/)?(.+?)(?:\\.[a-z0-9]+)?$", Pattern.CASE_INSENSITIVE);

  /** "https://res.cloudinary.com/cloud/image/upload/v1/rtr/menu/id.webp" → "rtr/menu/id" for this cloud's rtr/ images only. */
  static String publicIdFromUrl(String url, String cloudName) {
    if (url == null) return null;
    Matcher match = DELIVERY.matcher(url.split("\\?", 2)[0]);
    if (!match.matches() || !match.group(1).equals(cloudName)) return null;
    String publicId = match.group(2);
    return publicId.startsWith("rtr/") && !publicId.contains("..") ? publicId : null;
  }
}
