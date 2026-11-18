package com.menumaestro.backend.supabase;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Shared JDK HTTP client for Supabase and AI providers. */
public final class SupabaseHttp {
  public static final HttpClient CLIENT = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build();

  private SupabaseHttp() {}

  public record Response(int status, byte[] body, Map<String, java.util.List<String>> headers) {
    public String header(String name) {
      for (Map.Entry<String, java.util.List<String>> entry : headers.entrySet()) {
        if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) return entry.getValue().get(0);
      }
      return null;
    }

    public boolean ok() { return status >= 200 && status < 300; }

    public String text() { return new String(body, java.nio.charset.StandardCharsets.UTF_8); }
  }

  public static Response send(String method, String url, Map<String, String> headers, byte[] body, Duration timeout) throws IOException, InterruptedException {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout);
    headers.forEach(builder::header);
    HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body);
    builder.method(method, publisher);
    HttpResponse<byte[]> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    return new Response(response.statusCode(), response.body() == null ? new byte[0] : response.body(), response.headers().map());
  }
}
