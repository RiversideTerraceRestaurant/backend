package com.menumaestro.backend.reservation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.menumaestro.backend.common.json.Json;
import com.menumaestro.backend.common.validation.ValidationException;
import com.menumaestro.backend.common.web.ApiException;
import com.menumaestro.backend.mail.BookingEmailRenderer;
import com.menumaestro.backend.supabase.PostgrestResult;

import tools.jackson.databind.node.ObjectNode;

class ReservationRulesTest {
  private static ObjectNode table(int id, String number, String area) {
    return Json.obj("id", id, "tableNumber", number, "area", area, "capacity", 2, "status", "AVAILABLE", "active", true);
  }

  private static final List<ObjectNode> TERRACE = IntStream.range(0, 12).mapToObj(i -> table(i + 1, "T" + (i + 1), "TERRACE")).toList();
  private static final List<ObjectNode> VERONA = IntStream.range(0, 16).mapToObj(i -> table(100 + i, "V" + (i + 1), "VERONA")).toList();

  private static String message(Runnable action) { return assertThrows(ApiException.class, action::run).getMessage(); }

  @Test
  void tableSelectionFollowsTheJoiningRules() {
    assertDoesNotThrow(() -> ReservationRules.validateTablesForBooking(List.of(TERRACE.get(0)), "TERRACE", 2));
    assertDoesNotThrow(() -> ReservationRules.validateTablesForBooking(List.of(TERRACE.get(1), TERRACE.get(2)), "TERRACE", 4));
    assertTrue(message(() -> ReservationRules.validateTablesForBooking(List.of(TERRACE.get(0), TERRACE.get(1)), "TERRACE", 2)).contains("only one table"));
    assertTrue(message(() -> ReservationRules.validateTablesForBooking(List.of(TERRACE.get(0), TERRACE.get(2)), "TERRACE", 4)).contains("adjacent"));
    assertTrue(message(() -> ReservationRules.validateTablesForBooking(List.of(TERRACE.get(5), TERRACE.get(6)), "TERRACE", 4)).contains("T6 and T7"));
    assertTrue(message(() -> ReservationRules.validateTablesForBooking(List.of(TERRACE.get(0)), "TERRACE", 3)).contains("not enough"));
    assertTrue(message(() -> ReservationRules.validateTablesForBooking(TERRACE.subList(0, 4), "TERRACE", 6)).contains("between 1 and 3"));
    assertTrue(message(() -> ReservationRules.validateTablesForBooking(List.of(VERONA.get(0)), "VERONA", 2)).contains("Verona"));
    assertDoesNotThrow(() -> ReservationRules.validateTablesForBooking(List.of(VERONA.get(10), VERONA.get(11)), "VERONA", 3));
    assertTrue(message(() -> ReservationRules.validateTablesForBooking(List.of(VERONA.get(11), VERONA.get(12)), "VERONA", 4)).contains("fixed pairs"));
    ObjectNode maintenance = Json.copy(TERRACE.get(0));
    maintenance.put("status", "MAINTENANCE");
    ApiException error = assertThrows(ApiException.class, () -> ReservationRules.validateTablesForBooking(List.of(maintenance), "TERRACE", 2));
    assertEquals("TABLE_NOT_AVAILABLE", error.code());
    assertEquals(409, error.status());
  }

  private static List<String> numbers(List<ObjectNode> tables) { return tables.stream().map(table -> Json.str(table, "tableNumber")).toList(); }

  @Test
  void bestTableSuggestionPrefersCentralAdjacentTablesAndVeronaPairs() {
    assertEquals(List.of("TERRACE"), ReservationRules.suggestionAreas("outside", 4));
    assertEquals(List.of("ROMA"), ReservationRules.suggestionAreas("inside", 2));
    assertEquals(List.of("VERONA", "ROMA"), ReservationRules.suggestionAreas("inside", 5));
    assertEquals(List.of("T6"), numbers(ReservationRules.bestTableGroup("TERRACE", TERRACE, 2)));
    assertEquals(List.of("T5", "T6"), numbers(ReservationRules.bestTableGroup("TERRACE", TERRACE, 4)));
    assertEquals(List.of("V11", "V12"), numbers(ReservationRules.bestTableGroup("VERONA", VERONA, 4)));
    List<ObjectNode> lowVerona = new ArrayList<>(VERONA.stream().filter(table -> table.get("id").asInt() < 110).toList());
    assertEquals(List.of("V8", "V9", "V10"), numbers(ReservationRules.bestTableGroup("VERONA", lowVerona, 6)));
    assertTrue(ReservationRules.bestTableGroup("TERRACE", List.of(TERRACE.get(0), TERRACE.get(2)), 4).isEmpty());
  }

  @Test
  void restaurantTimeUsesTheConfiguredZoneAndQueryValidationTrimsSeconds() {
    assertEquals(new ReservationRules.Now("2026-11-16", "00:30"), ReservationRules.restaurantNow("Asia/Ho_Chi_Minh", Instant.parse("2026-11-15T17:30:00Z")));
    var parsed = ReservationSchemas.AVAILABILITY.parse(Json.obj("area", "ROMA", "date", "2026-11-20", "startTime", "18:00:00", "endTime", "20:00"));
    assertEquals("18:00", Json.str(parsed, "startTime"));
    assertEquals(1, parsed.get("guests").asInt());
    assertThrows(ValidationException.class, () -> ReservationSchemas.REQUEST_OTP.parse(Json.obj("customerName", "A", "customerPhone", "x", "customerEmail", "bad",
      "bookingDate", "2026-11-20", "startTime", "18:00", "endTime", "20:00", "numberOfGuests", 2, "area", "ROMA", "tableIds", List.of(1))));
  }

  @Test
  void validationMessagesMatchTheOriginalFormat() {
    ValidationException error = assertThrows(ValidationException.class, () -> ReservationSchemas.AVAILABILITY.parse(Json.obj("area", "BAD", "date", "2026-13-01", "startTime", "25:00", "endTime", "20:00", "guests", "x")));
    assertEquals("area: Invalid enum value. Expected 'TERRACE' | 'ROMA' | 'VERONA' | 'SORRENTO', received 'BAD'; date: Invalid date.; startTime: Use HH:mm.; guests: Expected number, received nan",
      error.getMessage());
    ValidationException missing = assertThrows(ValidationException.class, () -> ReservationSchemas.RESEND_OTP.parse(Json.obj()));
    assertEquals("verificationId: Required", missing.getMessage());
  }

  @Test
  void databaseExceptionsKeepTheirReservationErrorCodes() {
    ApiException error = ReservationRules.dbError(new PostgrestResult.Error("P0001", "TABLE_NOT_AVAILABLE: The selected table is no longer available."));
    assertEquals(409, error.status());
    assertEquals("TABLE_NOT_AVAILABLE", error.code());
    assertEquals("The selected table is no longer available.", error.getMessage());
    assertEquals("EMAIL_ALREADY_BOOKED_TODAY", ReservationRules.dbError(new PostgrestResult.Error("", "EMAIL_ALREADY_BOOKED_TODAY: dup")).code());
    assertEquals(409, ReservationRules.dbError(new PostgrestResult.Error("23505", "duplicate key")).status());
    assertEquals(502, ReservationRules.dbError(new PostgrestResult.Error("", "connection reset")).status());
  }

  @Test
  void bookingEmailsEscapeGuestInput() {
    ObjectNode booking = Json.obj("bookingCode", "RTR-1", "status", "PENDING", "area", "ROMA", "tables", List.of(Json.obj("tableNumber", "R1")), "bookingDate", "2026-11-20",
      "startTime", "18:00", "endTime", "20:00", "numberOfGuests", 2, "customerPhone", "0900", "customerEmail", "a@b.co", "customerName", "<script>x</script>", "note", null);
    BookingEmailRenderer.Email email = BookingEmailRenderer.render("otp", booking, Map.of("otp", "123456"));
    assertTrue(email.subject().contains("verification"));
    assertTrue(email.html().contains("123456"));
    assertFalse(email.html().contains("<script>"));
    assertTrue(email.html().contains("No reason provided."));
  }

  @Test
  void activeBookingEmailsLinkToTheTablePhotosAndNoShowRule() {
    ObjectNode booking = Json.obj("bookingCode", "RTR-1", "status", "APPROVED", "area", "TERRACE", "tables", List.of(Json.obj("id", 3, "tableNumber", "T3"), Json.obj("id", 4, "tableNumber", "T4")),
      "bookingDate", "2026-11-20", "startTime", "18:00", "endTime", "20:00", "numberOfGuests", 4, "customerPhone", "0900", "customerEmail", "a@b.co", "customerName", "A");
    String approved = BookingEmailRenderer.render("approved", booking, Map.of(), "https://mingsensei.site/").html();
    assertTrue(approved.contains("href=\"https://mingsensei.site/booking?tablePhotos=3,4\""));
    assertTrue(approved.contains("cancelled automatically"));
    assertTrue(BookingEmailRenderer.render("pending", booking, Map.of(), "https://mingsensei.site").html().contains("cancelled automatically"));
    assertFalse(BookingEmailRenderer.render("rejected", booking, Map.of(), "https://mingsensei.site").html().contains("tablePhotos"));
    assertFalse(BookingEmailRenderer.render("approved", booking, Map.of(), "javascript:alert(1)").html().contains("tablePhotos"));
  }

  @Test
  void mapsDatabaseRowsLikeTheOriginalApi() {
    ObjectNode row = Json.obj("id", 7, "booking_code", "RTR-1", "start_time", "18:00:00", "end_time", "20:00:00", "booking_tables", List.of(
      Json.obj("table", Json.obj("id", 2, "table_number", "T10", "area", "ROMA")), Json.obj("table", Json.obj("id", 1, "table_number", "T9", "area", "ROMA")), Json.obj("table", null)));
    ObjectNode booking = ReservationRules.toBooking(row);
    assertEquals("18:00", Json.str(booking, "startTime"));
    assertEquals(List.of("T9", "T10"), numbers(Json.list(booking.get("tables")).stream().map(node -> (ObjectNode) node).toList()));
    assertEquals("Roma", Json.str(booking.get("tables").get(0), "locationName"));
    assertFalse(booking.has("note"));
  }

  @Test
  void backupCsvIsExcelFriendlyAndNeutralisesFormulas() {
    ObjectNode booking = Json.obj("bookingCode", "RTR-1", "status", "APPROVED", "area", "TERRACE", "tables", List.of(Json.obj("tableNumber", "T3"), Json.obj("tableNumber", "T4")),
      "bookingDate", "2026-11-22", "startTime", "19:00", "endTime", "21:00", "numberOfGuests", 4, "customerPhone", "+84 912 345 678",
      "customerEmail", "timemap@riversideterrace.local", "customerName", "=HYPERLINK(\"x\")", "note", "Sinh nhật, \"bánh\"");
    String csv = new String(BookingBackup.csv(List.of(booking)), java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(csv.startsWith("\uFEFF\"Date\""));
    String line = csv.split("\r\n")[1];
    assertTrue(line.startsWith("\"2026-11-22\",\"19:00\",\"21:00\",\"RTR-1\",\"APPROVED\",\"'=HYPERLINK(\"\"x\"\")\",\"'+84 912 345 678\",\"\",\"4\",\"Terrace\",\"T3 + T4\""), line);
    assertTrue(line.contains("\"Sinh nhật, \"\"bánh\"\"\""));
    String html = BookingBackup.html("2026-11-22", List.of(booking), "2026-11-21 07:00");
    assertTrue(html.contains("2026-11-22 · 1"));
    assertFalse(html.contains("<script"));
  }

  @Test
  void backupRunsAtTheNextConfiguredHour() {
    java.time.ZonedDateTime morning = java.time.ZonedDateTime.parse("2026-11-21T06:30:00+07:00[Asia/Ho_Chi_Minh]");
    assertEquals("2026-11-21T07:00", BookingBackupJob.nextRun(morning, 7).toLocalDateTime().toString());
    assertEquals("2026-11-22T07:00", BookingBackupJob.nextRun(morning.withHour(7), 7).toLocalDateTime().toString());
  }
}
