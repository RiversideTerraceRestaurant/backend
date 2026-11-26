package com.menumaestro.backend.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.menumaestro.backend.client.supabase.SupabaseClient;
import com.menumaestro.backend.config.AppConfig;
import com.menumaestro.backend.util.Json;

import tools.jackson.databind.JsonNode;

class SessionCookiesTest {
  static AppConfig config(String secret) {
    return AppConfig.load(key -> switch (key) {
      case "SUPABASE_URL" -> "https://example.supabase.co";
      case "SUPABASE_ANON_KEY" -> "public-key";
      case "SESSION_SECRET" -> secret;
      default -> null;
    });
  }

  private static MockHttpServletRequest withCookie(String cookie) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("cookie", cookie);
    return request;
  }

  @Test
  void readsCookiesIssuedByTheNodeApi() {
    // Produced by the previous Node.js createAuth().save() with the same secret.
    String nodeCookie = "menu_session=GzVPq6V5lGNJPqFCc9Wf_MkshCssYrhCdc_VDRYIYwyGces_7SKlzxIRmeBcCi1V9SlCYyE1UX-JWJMp-prmyr3TQzg6p4iDURTiMicCsW6AEb0or-WVXSkBrxVY2zSqSt1Sosqf9stCjDE";
    JsonNode session = new SessionCookies(config("test-secret-test-secret-test-secret")).read(withCookie("other=1; " + nodeCookie));
    assertEquals("access-1", Json.str(session, "access_token"));
    assertEquals("refresh-1", Json.str(session, "refresh_token"));
    assertEquals(1900000000L, session.get("expires_at").asLong());
    assertNull(new SessionCookies(config("another-secret-another-secret-12345")).read(withCookie(nodeCookie)));
  }

  @Test
  void roundTripsAndClearsCookies() {
    SessionCookies cookies = new SessionCookies(config("test-secret-test-secret-test-secret"));
    MockHttpServletResponse response = new MockHttpServletResponse();
    cookies.save(response, new SupabaseClient.Session("a", "r", 123, null));
    String header = response.getHeader("Set-Cookie");
    assertTrue(header.startsWith("menu_session=") && header.contains("Max-Age=604800") && header.contains("HttpOnly") && header.contains("SameSite=Lax"));
    JsonNode session = cookies.read(withCookie(header.substring(0, header.indexOf(';'))));
    assertEquals("a", Json.str(session, "access_token"));
    cookies.clear(response);
    assertTrue(response.getHeader("Set-Cookie").contains("Max-Age=0"));
    assertNull(cookies.read(withCookie("menu_session=garbage")));
  }
}
