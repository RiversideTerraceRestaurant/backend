package com.menumaestro.backend.service;

import java.time.Duration;

import org.springframework.stereotype.Service;

import com.menumaestro.backend.client.supabase.PostgrestResult;
import com.menumaestro.backend.client.supabase.SupabaseClient.Session;
import com.menumaestro.backend.client.supabase.SupabaseClient;
import com.menumaestro.backend.config.AppConfig;
import com.menumaestro.backend.exception.ApiException;
import com.menumaestro.backend.security.SessionCookies;
import com.menumaestro.backend.util.Json;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import tools.jackson.databind.JsonNode;

/** Admin authentication against Supabase Auth with the encrypted session cookie. */
@Service
public class AuthService {
  public static final String ADMIN_ATTRIBUTE = AuthService.class.getName() + ".admin";

  /** The signed-in user. {@code client} queries Supabase with the user's JWT (RLS applies). */
  public record UserSession(SupabaseClient client, String userId, String email, boolean isAdmin, String accessToken) {
    public JsonNode user() { return Json.obj("id", userId, "email", email); }

    public void logout() { client.signOut(accessToken); }
  }

  private final SupabaseClient publicClient;
  private final SessionCookies cookies;

  public AuthService(AppConfig config, SessionCookies cookies) {
    this.publicClient = new SupabaseClient(config.supabaseUrl(), config.supabaseKey(), null, Duration.ofSeconds(20));
    this.cookies = cookies;
  }

  /** Client authenticated with the anon/publishable key. */
  public SupabaseClient publicClient() { return publicClient; }

  public SupabaseClient makeClient() { return publicClient; }

  public SessionCookies cookies() { return cookies; }

  public UserSession authenticate(HttpServletRequest request, HttpServletResponse response, boolean required) {
    JsonNode stored = cookies.read(request);
    if (stored == null || !stored.isObject()) {
      if (required) throw new ApiException(401, "Please sign in.");
      return null;
    }
    String accessToken = Json.str(stored, "access_token");
    String refreshToken = Json.str(stored, "refresh_token");
    Double expiresAt = Json.dbl(stored, "expires_at");
    if (expiresAt != null && expiresAt * 1000 < System.currentTimeMillis() + 30000) {
      SupabaseClient.AuthResult refreshed = publicClient.refreshSession(refreshToken);
      if (refreshed.error() != null || refreshed.session() == null) {
        cookies.clear(response);
        if (required) throw new ApiException(401, "Your session expired. Please sign in.");
        return null;
      }
      Session session = refreshed.session();
      accessToken = session.accessToken();
      cookies.save(response, session);
    }
    SupabaseClient client = publicClient.withToken(accessToken);
    JsonNode user = accessToken == null ? null : client.getUser(accessToken);
    if (user == null) {
      cookies.clear(response);
      if (required) throw new ApiException(401, "Please sign in again.");
      return null;
    }
    String userId = Json.str(user, "id");
    PostgrestResult role = client.rpc("has_role", Json.obj("_user_id", userId, "_role", "admin"));
    if (role.failed()) throw new ApiException(502, "Unable to verify account permissions.");
    boolean isAdmin = role.data() != null && role.data().isBoolean() && role.data().booleanValue();
    return new UserSession(client, userId, Json.str(user, "email"), isAdmin, accessToken);
  }

  public UserSession admin(HttpServletRequest request, HttpServletResponse response) {
    UserSession session = authenticate(request, response, true);
    if (!session.isAdmin()) throw new ApiException(403, "Admin access required.");
    return session;
  }

  /** The admin verified by the interceptor for the current /api/admin request. */
  public static UserSession currentAdmin(HttpServletRequest request) {
    return (UserSession) request.getAttribute(ADMIN_ATTRIBUTE);
  }
}
