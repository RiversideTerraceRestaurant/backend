package com.menumaestro.backend.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.menumaestro.backend.common.json.Json;
import com.menumaestro.backend.config.AppConfig;
import com.menumaestro.backend.supabase.SupabaseClient.Session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * Encrypted session cookie (AES-256-GCM, key = SHA-256(SESSION_SECRET)). The byte layout
 * nonce(12) | tag(16) | ciphertext is identical to the Node.js version, so existing admin
 * sessions remain valid after the migration.
 */
@Component
public class SessionCookies {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final byte[] key;
  private final String cookieName;
  private final boolean production;

  public SessionCookies(AppConfig config) {
    try {
      this.key = MessageDigest.getInstance("SHA-256").digest(config.sessionSecret().getBytes(StandardCharsets.UTF_8));
    } catch (Exception error) { throw new IllegalStateException(error); }
    this.production = config.production();
    this.cookieName = production ? "__Host-menu_session" : "menu_session";
  }

  private void cookie(HttpServletResponse response, String value, int maxAge) {
    response.setHeader("Set-Cookie", cookieName + "=" + value + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + maxAge + (production ? "; Secure" : ""));
  }

  public void clear(HttpServletResponse response) { cookie(response, "", 0); }

  public void save(HttpServletResponse response, Session session) {
    try {
      byte[] nonce = new byte[12];
      RANDOM.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      String json = Json.stringify(Json.obj("access_token", session.accessToken(), "refresh_token", session.refreshToken(), "expires_at", session.expiresAt()));
      byte[] sealed = cipher.doFinal(json.getBytes(StandardCharsets.UTF_8));
      byte[] ciphertext = Arrays.copyOfRange(sealed, 0, sealed.length - 16);
      byte[] tag = Arrays.copyOfRange(sealed, sealed.length - 16, sealed.length);
      byte[] value = ByteBuffer.allocate(12 + 16 + ciphertext.length).put(nonce).put(tag).put(ciphertext).array();
      cookie(response, Base64.getUrlEncoder().withoutPadding().encodeToString(value), 60 * 60 * 24 * 7);
    } catch (Exception error) { throw new IllegalStateException("Cannot seal session", error); }
  }

  /** The decrypted {access_token, refresh_token, expires_at} or null. */
  public JsonNode read(HttpServletRequest request) {
    String header = request.getHeader("cookie");
    if (header == null) return null;
    String value = null;
    for (String part : header.split(";")) {
      String trimmed = part.trim();
      if (trimmed.startsWith(cookieName + "=")) { value = trimmed.substring(cookieName.length() + 1); break; }
    }
    if (value == null || value.isEmpty() || value.length() > 10000) return null;
    try {
      byte[] bytes = Base64.getUrlDecoder().decode(value.replace('+', '-').replace('/', '_').replace("=", ""));
      if (bytes.length < 28) return null;
      byte[] nonce = Arrays.copyOfRange(bytes, 0, 12);
      byte[] tag = Arrays.copyOfRange(bytes, 12, 28);
      byte[] ciphertext = Arrays.copyOfRange(bytes, 28, bytes.length);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      byte[] sealed = ByteBuffer.allocate(ciphertext.length + 16).put(ciphertext).put(tag).array();
      return Json.parse(new String(cipher.doFinal(sealed), StandardCharsets.UTF_8));
    } catch (Exception error) {
      return null;
    }
  }
}
