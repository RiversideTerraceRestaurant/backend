package com.menumaestro.backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.menumaestro.backend.client.supabase.PostgrestResult;
import com.menumaestro.backend.client.supabase.SupabaseClient;
import com.menumaestro.backend.exception.ApiException;
import com.menumaestro.backend.security.ClientIp;
import com.menumaestro.backend.security.RateLimiter;
import com.menumaestro.backend.service.AuthService;
import com.menumaestro.backend.util.Json;
import com.menumaestro.backend.util.RequestBodies;
import com.menumaestro.backend.util.Responses;
import com.menumaestro.backend.validation.Schema;
import com.menumaestro.backend.validation.Z;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import tools.jackson.databind.JsonNode;

/** /api/auth: admin sign-in with Supabase email/password and the encrypted session cookie. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private static final Schema LOGIN = Z.object()
    .field("email", Z.string().email().max(254))
    .field("password", Z.string().min(6).max(512));

  private final AuthService auth;
  private final RateLimiter limiter;
  private final ClientIp clientIp;

  public AuthController(AuthService auth, RateLimiter limiter, ClientIp clientIp) {
    this.auth = auth;
    this.limiter = limiter;
    this.clientIp = clientIp;
  }

  @GetMapping("/session")
  public ResponseEntity<String> session(HttpServletRequest request, HttpServletResponse response) {
    AuthService.UserSession session = auth.authenticate(request, response, false);
    return Responses.ok(Json.obj("user", session == null ? null : session.user(), "isAdmin", session != null && session.isAdmin()));
  }

  @PostMapping("/login")
  public ResponseEntity<String> login(HttpServletRequest request, HttpServletResponse response) {
    limiter.limit("login:" + clientIp.of(request), 8);
    JsonNode body = LOGIN.parse(RequestBodies.json(request));
    SupabaseClient.AuthResult result = auth.makeClient().signInWithPassword(Json.str(body, "email"), Json.str(body, "password"));
    if (result.error() != null || result.session() == null) throw new ApiException(401, "Invalid email or password.");
    String userId = Json.str(result.session().user(), "id");
    SupabaseClient client = auth.makeClient().withToken(result.session().accessToken());
    PostgrestResult role = client.rpc("has_role", Json.obj("_user_id", userId, "_role", "admin"));
    JsonNode isAdmin = role.unwrap();
    if (!Json.truthy(isAdmin)) throw new ApiException(403, "Admin access required.");
    auth.cookies().save(response, result.session());
    return Responses.ok(Json.obj("user", Json.obj("id", userId, "email", Json.str(result.session().user(), "email")), "isAdmin", true));
  }

  @PostMapping("/logout")
  public ResponseEntity<String> logout(HttpServletRequest request, HttpServletResponse response) {
    AuthService.UserSession session = auth.authenticate(request, response, false);
    if (session != null) session.logout();
    auth.cookies().clear(response);
    return Responses.ok(Json.obj("ok", true));
  }
}
