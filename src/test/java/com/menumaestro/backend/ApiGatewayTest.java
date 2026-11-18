package com.menumaestro.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP contract of the gateway: headers, CORS, auth guard, routing and error bodies. */
@SpringBootTest(properties = {
  "SUPABASE_URL=http://127.0.0.1:9", "SUPABASE_ANON_KEY=test-key", "FRONTEND_ORIGIN=http://localhost:8080",
  "SESSION_SECRET=test-secret-test-secret-test-secret", "KNOWLEDGE_BASE_PATH=knowledge/restaurant.md"})
@AutoConfigureMockMvc
class ApiGatewayTest {
  @Autowired MockMvc mvc;
  @Autowired com.menumaestro.backend.reservation.ReservationService reservations;

  private static com.menumaestro.backend.auth.AuthService.UserSession admin() {
    return new com.menumaestro.backend.auth.AuthService.UserSession(
      new com.menumaestro.backend.supabase.SupabaseClient("http://127.0.0.1:9", "k", "t", java.time.Duration.ofSeconds(1)), "admin-id", "a@b.co", true, "t");
  }

  @Test
  void streamsBookingEventsToAdmins() throws Exception {
    var result = mvc.perform(get("/api/admin/bookings/events").requestAttr(com.menumaestro.backend.auth.AuthService.ADMIN_ATTRIBUTE, admin()))
      .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn();
    reservations.events().emit(new com.menumaestro.backend.reservation.BookingEvents.Changed(42, true));
    String body = result.getResponse().getContentAsString();
    org.junit.jupiter.api.Assertions.assertTrue(body.startsWith("event: connected\ndata: ok\n\n"), body);
    org.junit.jupiter.api.Assertions.assertTrue(body.contains("event: booking.changed\ndata: {\"type\":\"booking.changed\",\"bookingId\":42,\"newCustomerBooking\":true}\n\n"), body);
    org.junit.jupiter.api.Assertions.assertTrue(result.getResponse().getContentType().startsWith("text/event-stream"));
    org.junit.jupiter.api.Assertions.assertEquals("no", result.getResponse().getHeader("X-Accel-Buffering"));
  }

  @Test
  void servesAdminKnowledgeAndValidatesAdminBodies() throws Exception {
    mvc.perform(get("/api/admin/knowledge").requestAttr(com.menumaestro.backend.auth.AuthService.ADMIN_ATTRIBUTE, admin()))
      .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("\"version\":\"")));
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/admin/knowledge")
        .requestAttr(com.menumaestro.backend.auth.AuthService.ADMIN_ATTRIBUTE, admin()).contentType("application/json").content("{\"content\":\"x\",\"version\":\"0\"}"))
      .andExpect(status().isBadRequest()).andExpect(content().string("{\"error\":\"version: Invalid\"}"));
    mvc.perform(post("/api/admin/bookings").requestAttr(com.menumaestro.backend.auth.AuthService.ADMIN_ATTRIBUTE, admin()).contentType("application/json")
        .content("{\"customerName\":\"A\",\"numberOfGuests\":2,\"bookingDate\":\"2026-12-01\",\"startTime\":\"19:00\",\"endTime\":\"21:00\",\"area\":\"ROMA\",\"tableIds\":[1]}"))
      .andExpect(status().isServiceUnavailable())
      .andExpect(content().string("{\"error\":\"Reservations are not configured. Set SUPABASE_SERVICE_ROLE_KEY on the server.\"}"));
    mvc.perform(get("/api/admin/push/config").requestAttr(com.menumaestro.backend.auth.AuthService.ADMIN_ATTRIBUTE, admin()))
      .andExpect(content().string("{\"enabled\":false,\"publicKey\":\"\",\"subscriptionCount\":0}"));
    mvc.perform(get("/api/admin/bookings/abc").requestAttr(com.menumaestro.backend.auth.AuthService.ADMIN_ATTRIBUTE, admin()))
      .andExpect(status().isBadRequest()).andExpect(content().string("{\"error\":\": Expected number, received nan\"}"));
  }

  @Test
  void healthHasSecurityHeaders() throws Exception {
    mvc.perform(get("/api/health")).andExpect(status().isOk())
      .andExpect(content().string("{\"ok\":true,\"aiConfigured\":false}"))
      .andExpect(header().string("Cache-Control", "no-store"))
      .andExpect(header().string("X-Content-Type-Options", "nosniff"))
      .andExpect(header().string("Referrer-Policy", "same-origin"));
  }

  @Test
  void enforcesTheOriginAllowListAndPreflight() throws Exception {
    mvc.perform(get("/api/health").header("Origin", "https://attacker.example")).andExpect(status().isForbidden())
      .andExpect(content().string("{\"error\":\"Origin not allowed.\"}"));
    mvc.perform(get("/api/health").header("Origin", "http://localhost:8080")).andExpect(status().isOk())
      .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8080"))
      .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    mvc.perform(options("/api/chat").header("Origin", "http://localhost:8080")).andExpect(status().isNoContent())
      .andExpect(header().string("Access-Control-Allow-Methods", "GET,POST,PATCH,DELETE,OPTIONS"));
    mvc.perform(get("/api/health").header("Sec-Fetch-Site", "cross-site")).andExpect(status().isForbidden())
      .andExpect(content().string("{\"error\":\"Cross-site request rejected.\"}"));
  }

  @Test
  void guardsAdminRoutesBeforeReadingBodies() throws Exception {
    for (String path : new String[] {"/api/admin/menu", "/api/admin/images", "/api/admin/jobs", "/api/admin/translate", "/api/admin/unknown"}) {
      mvc.perform(post(path).contentType("application/json").content("{}")).andExpect(status().isUnauthorized())
        .andExpect(content().string("{\"error\":\"Please sign in.\"}"));
    }
    mvc.perform(get("/api/admin/bookings/events")).andExpect(status().isUnauthorized());
  }

  @Test
  void returnsJsonErrorsForRoutingAndBodies() throws Exception {
    mvc.perform(get("/api/nope")).andExpect(status().isNotFound()).andExpect(content().string("{\"error\":\"API route not found.\"}"));
    mvc.perform(post("/api/health")).andExpect(status().isNotFound());
    mvc.perform(post("/api/chat").contentType("text/plain").content("{}")).andExpect(status().isUnsupportedMediaType())
      .andExpect(content().string("{\"error\":\"Send application/json.\"}"));
    mvc.perform(post("/api/chat").contentType("application/json").content("{bad")).andExpect(status().isBadRequest())
      .andExpect(content().string("{\"error\":\"Invalid JSON.\"}"));
    mvc.perform(post("/api/chat").contentType("application/json").content("{\"messages\":[]}")).andExpect(status().isBadRequest())
      .andExpect(content().string("{\"error\":\"messages: Array must contain at least 1 element(s); : The last message must be from the guest.\"}"));
    mvc.perform(post("/api/menu/abc/views").contentType("application/json").content("{}")).andExpect(status().isBadRequest())
      .andExpect(content().string("{\"error\":\": Invalid uuid\"}"));
  }

  @Test
  void rateLimitsLoginAttempts() throws Exception {
    for (int i = 0; i < 8; i++) mvc.perform(post("/api/auth/login").contentType("application/json").content("{}")).andExpect(status().isBadRequest());
    mvc.perform(post("/api/auth/login").contentType("application/json").content("{}")).andExpect(status().isTooManyRequests())
      .andExpect(header().string("Retry-After", "60"));
  }
}
