package com.menumaestro.backend.service;

import static com.menumaestro.backend.service.ReservationRules.bad;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.menumaestro.backend.client.supabase.PostgrestQuery;
import com.menumaestro.backend.client.supabase.PostgrestResult;
import com.menumaestro.backend.client.supabase.SupabaseClient;
import com.menumaestro.backend.event.BookingEvents;
import com.menumaestro.backend.exception.ApiException;
import com.menumaestro.backend.service.mail.Mailer;
import com.menumaestro.backend.util.Async;
import com.menumaestro.backend.util.Js;
import com.menumaestro.backend.util.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Table reservations (ported from RTR BookingService/TableService). Uses the service-role
 * client so guests never read other guests' bookings through RLS.
 */
public class ReservationService {
  private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String BOOKING_SELECT = "*, booking_tables(table:restaurant_tables(*))";
  private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private final SupabaseClient db;
  private final Mailer mailer;
  private final String timeZone;
  private final Supplier<Instant> clock;
  private final BookingEvents events = new BookingEvents();

  public ReservationService(SupabaseClient db, Mailer mailer, String timeZone, Supplier<Instant> clock) {
    this.db = db;
    this.mailer = mailer;
    this.timeZone = timeZone == null ? "Asia/Ho_Chi_Minh" : timeZone;
    this.clock = clock;
  }

  public BookingEvents events() { return events; }

  public boolean configured() { return db != null; }

  public String timeZone() { return timeZone; }

  public Instant now() { return clock.get(); }

  private SupabaseClient requireDb() {
    if (db == null) throw new ApiException(503, "Reservations are not configured. Set SUPABASE_SERVICE_ROLE_KEY on the server.");
    return db;
  }

  private ReservationRules.Now restaurantNow() { return ReservationRules.restaurantNow(timeZone, clock.get()); }

  private static JsonNode unwrapDb(PostgrestResult result) {
    if (result.failed()) throw ReservationRules.dbError(result.error());
    return result.data();
  }

  private void changed(long bookingId, boolean newCustomerBooking) { events.emit(new BookingEvents.Changed(bookingId, newCustomerBooking)); }

  private void changed(long bookingId) { changed(bookingId, false); }

  /** Sends a status email; returns the guest-facing warning when it could not be sent. */
  private String notify(String kind, JsonNode booking) {
    // Walk-in and time-map bookings use a placeholder address; there is nobody to email.
    if (ReservationRules.PLACEHOLDER_EMAIL.equals(Json.str(booking, "customerEmail"))) return null;
    try {
      mailer.send(kind, booking, Map.of());
      return null;
    } catch (Exception error) {
      log.warn(Json.stringify(Json.obj("event", "booking.email_failed", "kind", kind, "bookingId", booking.get("id"), "error", error.getMessage())));
      return "The booking was saved, but the email could not be sent.";
    }
  }

  private static ObjectNode withWarning(ObjectNode booking, String warning) {
    ObjectNode result = Json.copy(booking);
    if (warning != null) result.put("warning", warning);
    return result;
  }

  public ObjectNode booking(long id) {
    JsonNode row = unwrapDb(requireDb().from("bookings").select(BOOKING_SELECT).eq("id", id).maybeSingle().execute());
    if (row == null || row.isNull()) throw new ApiException(404, "Booking not found.");
    return ReservationRules.toBooking(row);
  }

  private List<ObjectNode> loadTables(List<Long> ids) {
    List<Long> unique = new ArrayList<>(new LinkedHashSet<>(ids));
    JsonNode rows = unwrapDb(requireDb().from("restaurant_tables").select("*").in("id", unique).execute());
    if (rows.size() != unique.size()) throw bad("One or more selected tables do not exist.");
    List<ObjectNode> tables = new ArrayList<>();
    for (JsonNode row : rows) tables.add(ReservationRules.toTable(row));
    return tables;
  }

  private String newCode() {
    return "RTR-" + restaurantNow().date().replace("-", "") + "-" + Js.padStart(String.valueOf(RANDOM.nextInt(10000)), 4, '0');
  }

  /** tableRule: 'day' (guests: one booking per table per day), 'overlap' (admins: no overlapping times), 'none'. */
  private long save(Long id, ObjectNode data, List<Long> ids, String tableRule, boolean checkEmail) {
    for (int attempt = 0; ; attempt++) {
      ObjectNode payload = id != null ? data : Json.copy(data);
      if (id == null) payload.put("booking_code", newCode());
      PostgrestResult result = requireDb().rpc("booking_save", Json.obj("p_id", id, "p_data", payload, "p_table_ids", ids, "p_table_rule", tableRule, "p_check_email", checkEmail));
      PostgrestResult.Error error = result.error();
      // A random booking code can collide; retry with a fresh one.
      if (error != null && "23505".equals(error.code()) && id == null && attempt < 4 && String.valueOf(error.message()).contains("booking_code")) continue;
      if (error != null) throw ReservationRules.dbError(error);
      return result.data().asLong();
    }
  }

  private void transition(long id, ObjectNode data, String tableRule, boolean checkEmail) {
    unwrapDb(requireDb().rpc("booking_transition", Json.obj("p_id", id, "p_data", data, "p_table_rule", tableRule, "p_check_email", checkEmail)));
  }

  private static double parseTimestamp(String value) {
    if (value == null) return Double.NaN;
    try { return OffsetDateTime.parse(value).toInstant().toEpochMilli(); }
    catch (Exception error) {
      try { return Instant.parse(value).toEpochMilli(); } catch (Exception ignored) { return Double.NaN; }
    }
  }

  private String iso(Instant instant) { return ISO.format(instant); }

  private static List<Long> ids(JsonNode values) {
    List<Long> ids = new ArrayList<>();
    for (JsonNode value : values) ids.add(value.asLong());
    return ids;
  }

  /** Whether a pending booking still holds its tables (verified, or its OTP has not expired). */
  private boolean holding(JsonNode booking, long time) {
    return Json.bool(booking, "email_verified") || parseTimestamp(Json.str(booking, "otp_expires_at")) > time;
  }

  private static List<ReservationRules.TerraceParty> terraceParties(JsonNode bookings, Long excludeId, java.util.function.Predicate<JsonNode> holding) {
    List<ReservationRules.TerraceParty> parties = new ArrayList<>();
    for (JsonNode booking : bookings) {
      if (!"TERRACE".equals(Json.str(booking, "area")) || !holding.test(booking)) continue;
      if (excludeId != null && excludeId == booking.get("id").asLong()) continue;
      int tables = Json.list(booking.get("booking_tables")).size();
      if (tables > 0) parties.add(new ReservationRules.TerraceParty(Json.str(booking, "start_time"), Json.str(booking, "end_time"), tables));
    }
    return parties;
  }

  /** Rejects a terrace booking when the terrace cannot be split into one more table at that time. */
  private void assertTerraceSplit(Long excludeId, String area, String date, String startTime, String endTime, int tables) {
    if (!"TERRACE".equals(area)) return;
    JsonNode bookings = unwrapDb(requireDb().from("bookings").select("id, area, status, start_time, end_time, email_verified, otp_expires_at, booking_tables(table_id)")
      .eq("booking_date", date).eq("area", "TERRACE").in("status", ReservationRules.BLOCKING).execute());
    long time = clock.get().toEpochMilli();
    if (!ReservationRules.terraceSplitAllowed(terraceParties(bookings, excludeId, booking -> holding(booking, time)), startTime, endTime, tables)) {
      throw ReservationRules.terraceFull();
    }
  }

  public List<ObjectNode> availability(String area, String date, String startTime, String endTime) { return availability(area, date, startTime, endTime, 1); }

  /** Tables of an area with their status at that time; FULL when the terrace cannot take one more party of `guests`. */
  public List<ObjectNode> availability(String area, String date, String startTime, String endTime, int guests) {
    ReservationRules.validateTimeRange(startTime, endTime);
    ReservationRules.validateNotPast(date, startTime, restaurantNow());
    CompletableFuture<PostgrestResult> tables = CompletableFuture.supplyAsync(() ->
      requireDb().from("restaurant_tables").select("*").eq("area", area).eq("active", true).order("id").execute(), Async.EXECUTOR);
    CompletableFuture<PostgrestResult> busy = CompletableFuture.supplyAsync(() ->
      requireDb().from("bookings").select("id, area, status, start_time, end_time, email_verified, otp_expires_at, booking_tables(table_id)").eq("booking_date", date).in("status", ReservationRules.BLOCKING).execute(), Async.EXECUTOR);
    PostgrestResult tableResult = Async.join(tables);
    PostgrestResult busyResult = Async.join(busy);
    Map<Long, String> statuses = new HashMap<>();
    long time = clock.get().toEpochMilli();
    JsonNode busyRows = unwrapDb(busyResult);
    for (JsonNode booking : busyRows) {
      if (!holding(booking, time)) continue;
      String derived = "APPROVED".equals(Json.str(booking, "status")) ? "RESERVED" : "HOLD";
      for (JsonNode link : Json.list(booking.get("booking_tables"))) {
        long tableId = link.get("table_id").asLong();
        if (!"RESERVED".equals(statuses.get(tableId))) statuses.put(tableId, derived);
      }
    }
    boolean terraceFull = "TERRACE".equals(area) && !ReservationRules.terraceSplitAllowed(
      terraceParties(busyRows, null, booking -> holding(booking, time)), startTime, endTime, ReservationRules.terraceTablesFor(guests));
    List<ObjectNode> result = new ArrayList<>();
    for (JsonNode row : unwrapDb(tableResult)) {
      String derived = statuses.get(row.get("id").asLong());
      if (derived == null && terraceFull && "AVAILABLE".equals(Json.str(row, "status"))) derived = "FULL";
      result.add(ReservationRules.toTable(row, derived != null ? derived : row.get("status")));
    }
    result.sort(ReservationRules.BY_NUMBER);
    return result;
  }

  public List<ObjectNode> availability(JsonNode input) {
    JsonNode guests = Json.get(input, "guests");
    return availability(Json.str(input, "area"), Json.str(input, "date"), Json.str(input, "startTime"), Json.str(input, "endTime"), guests == null ? 1 : guests.asInt());
  }

  public ObjectNode suggest(JsonNode input) {
    int guests = input.get("guests").asInt();
    for (String candidate : ReservationRules.suggestionAreas(Json.str(input, "seating"), guests)) {
      List<ObjectNode> available = availability(candidate, Json.str(input, "date"), Json.str(input, "startTime"), Json.str(input, "endTime"), guests)
        .stream().filter(table -> "AVAILABLE".equals(Json.str(table, "status"))).toList();
      List<ObjectNode> tables = ReservationRules.bestTableGroup(candidate, available, guests);
      if (!tables.isEmpty()) {
        int total = tables.stream().mapToInt(table -> table.get("capacity").asInt()).sum();
        return Json.obj("area", candidate, "tables", tables, "totalCapacity", total);
      }
    }
    throw new ApiException(404, "No suitable adjacent tables are available.", "NO_TABLE_SUGGESTION");
  }

  public List<ObjectNode> tables(String areaFilter) {
    PostgrestQuery query = requireDb().from("restaurant_tables").select("*").eq("active", true);
    if (areaFilter != null) query = query.eq("area", areaFilter);
    List<ObjectNode> result = new ArrayList<>();
    for (JsonNode row : unwrapDb(query.execute())) result.add(ReservationRules.toTable(row));
    result.sort((a, b) -> {
      int area = Integer.compare(ReservationRules.AREAS.indexOf(Json.str(a, "area")), ReservationRules.AREAS.indexOf(Json.str(b, "area")));
      return area != 0 ? area : Double.compare(ReservationRules.tableNumberValue(a), ReservationRules.tableNumberValue(b));
    });
    return result;
  }

  private String otp() { return Js.padStart(String.valueOf(RANDOM.nextInt(1_000_000)), 6, '0'); }

  public ObjectNode requestOtp(JsonNode input) {
    ReservationRules.validateTimeRange(Json.str(input, "startTime"), Json.str(input, "endTime"));
    ReservationRules.validateNotPast(Json.str(input, "bookingDate"), Json.str(input, "startTime"), restaurantNow());
    int guests = input.get("numberOfGuests").asInt();
    if (guests > ReservationRules.MAX_ONLINE_GUESTS) throw bad("Online booking supports up to 6 guests. Please contact the restaurant for larger parties.");
    if (!mailer.configured()) throw new ApiException(503, "Online booking is temporarily unavailable. Please contact the restaurant.");
    List<Long> tableIds = ids(input.get("tableIds"));
    ReservationRules.validateTablesForBooking(loadTables(tableIds), Json.str(input, "area"), guests);
    assertTerraceSplit(null, Json.str(input, "area"), Json.str(input, "bookingDate"), Json.str(input, "startTime"), Json.str(input, "endTime"), new LinkedHashSet<>(tableIds).size());
    String otp = otp();
    String expiresAt = iso(clock.get().plusSeconds(ReservationRules.OTP_TTL_SECONDS));
    String verificationId = UUID.randomUUID().toString();
    long id = save(null, Json.obj(
      "customer_name", input.get("customerName"), "customer_phone", input.get("customerPhone"), "customer_email", input.get("customerEmail"),
      "booking_date", input.get("bookingDate"), "start_time", input.get("startTime"), "end_time", input.get("endTime"), "number_of_guests", input.get("numberOfGuests"),
      "area", input.get("area"), "note", input.get("note"), "status", "PENDING", "email_verified", false, "verification_id", verificationId,
      "otp_hash", ReservationRules.hashOtp(otp), "otp_expires_at", expiresAt), tableIds, "day", true);
    ObjectNode booking = booking(id);
    try {
      mailer.send("otp", booking, Map.of("otp", otp));
    } catch (Exception error) {
      requireDb().from("bookings").update(Json.obj("status", "CANCELLED")).eq("id", id).execute();
      log.warn(Json.stringify(Json.obj("event", "booking.otp_email_failed", "bookingId", id, "error", error.getMessage())));
      throw new ApiException(502, "We could not send the verification email. Please check the address and try again.");
    }
    return Json.obj("bookingId", id, "verificationId", verificationId, "email", booking.get("customerEmail"), "expiresAt", expiresAt);
  }

  public ObjectNode resendOtp(JsonNode input) {
    JsonNode row = unwrapDb(requireDb().from("bookings").select("id, status, otp_resend_count").eq("verification_id", Json.str(input, "verificationId"))
      .eq("email_verified", false).maybeSingle().execute());
    if (row == null || row.isNull() || !"PENDING".equals(Json.str(row, "status"))) throw bad("Verification session not found.");
    int resends = row.get("otp_resend_count").asInt();
    if (resends >= ReservationRules.MAX_OTP_RESENDS) throw bad("OTP resend limit reached.");
    String otp = otp();
    String expiresAt = iso(clock.get().plusSeconds(ReservationRules.OTP_TTL_SECONDS));
    String nextId = UUID.randomUUID().toString();
    long id = row.get("id").asLong();
    // Re-check the hold: the previous OTP may have expired and released the tables.
    transition(id, Json.obj(), "day", false);
    unwrapDb(requireDb().from("bookings").update(Json.obj("verification_id", nextId, "otp_hash", ReservationRules.hashOtp(otp), "otp_expires_at", expiresAt,
      "otp_attempt_count", 0, "otp_resend_count", resends + 1)).eq("id", id).eq("email_verified", false).execute());
    ObjectNode booking = booking(id);
    try { mailer.send("otp", booking, Map.of("otp", otp)); }
    catch (Exception error) { throw new ApiException(502, "We could not send the verification email. Please try again."); }
    return Json.obj("bookingId", booking.get("id"), "verificationId", nextId, "email", booking.get("customerEmail"), "expiresAt", expiresAt);
  }

  public ObjectNode verifyOtp(JsonNode input) {
    JsonNode row = unwrapDb(requireDb().from("bookings").select("id, status, otp_hash, otp_expires_at, otp_attempt_count")
      .eq("verification_id", Json.str(input, "verificationId")).eq("email_verified", false).maybeSingle().execute());
    if (row == null || row.isNull() || !"PENDING".equals(Json.str(row, "status"))) throw bad("Invalid verification session.");
    long id = row.get("id").asLong();
    if (!(parseTimestamp(Json.str(row, "otp_expires_at")) > clock.get().toEpochMilli())) {
      requireDb().from("bookings").update(Json.obj("status", "CANCELLED")).eq("id", id).execute();
      throw bad("OTP expired.");
    }
    int attempts = row.get("otp_attempt_count").asInt();
    if (attempts >= ReservationRules.MAX_OTP_ATTEMPTS) {
      requireDb().from("bookings").update(Json.obj("status", "CANCELLED")).eq("id", id).execute();
      throw bad("OTP attempt limit reached.");
    }
    if (!ReservationRules.hashOtp(Json.str(input, "otp")).equals(Json.str(row, "otp_hash"))) {
      requireDb().from("bookings").update(Json.obj("otp_attempt_count", attempts + 1)).eq("id", id).execute();
      throw bad("Invalid OTP.");
    }
    transition(id, Json.obj("email_verified", true, "status", "PENDING", "otp_hash", null), "day", true);
    ObjectNode booking = booking(id);
    String warning = notify("pending", booking);
    changed(booking.get("id").asLong(), true);
    return withWarning(booking, warning);
  }

  public List<ObjectNode> list(String date, String area) {
    PostgrestQuery query = requireDb().from("bookings").select(BOOKING_SELECT).eq("email_verified", true);
    if (date != null) query = query.eq("booking_date", date);
    if (area != null) query = query.eq("area", area);
    JsonNode rows = unwrapDb(query.order("booking_date", false).order("start_time", false).limit(2000).execute());
    List<ObjectNode> result = new ArrayList<>();
    for (JsonNode row : rows) result.add(ReservationRules.toBooking(row));
    return result;
  }

  /** Pending and confirmed (email-verified) bookings on or after `from`, in seating order, for backups. */
  public List<ObjectNode> upcoming(String from) {
    JsonNode rows = unwrapDb(requireDb().from("bookings").select(BOOKING_SELECT).eq("email_verified", true).gte("booking_date", from)
      .in("status", ReservationRules.BLOCKING).order("booking_date").order("start_time").limit(5000).execute());
    List<ObjectNode> result = new ArrayList<>();
    for (JsonNode row : rows) result.add(ReservationRules.toBooking(row));
    return result;
  }

  public ObjectNode page(JsonNode filters) {
    PostgrestQuery query = requireDb().from("bookings").select(BOOKING_SELECT, true, false).eq("email_verified", true);
    if (Json.has(filters, "date")) query = query.eq("booking_date", Json.str(filters, "date"));
    if (Json.has(filters, "area")) query = query.eq("area", Json.str(filters, "area"));
    if (Json.has(filters, "status")) query = query.eq("status", Json.str(filters, "status"));
    if (Json.truthy(Json.get(filters, "startTime"))) query = query.gt("end_time", Json.str(filters, "startTime"));
    if (Json.truthy(Json.get(filters, "endTime"))) query = query.lt("start_time", Json.str(filters, "endTime"));
    String tableNumber = Json.str(filters, "tableNumber");
    if (tableNumber != null && !tableNumber.isEmpty()) {
      JsonNode tables = unwrapDb(requireDb().from("restaurant_tables").select("id").ilike("table_number", "%" + tableNumber.replaceAll("[%_\\\\]", "") + "%").execute());
      List<Long> tableIds = new ArrayList<>();
      for (JsonNode table : tables) tableIds.add(table.get("id").asLong());
      JsonNode links = tableIds.isEmpty() ? Json.arr() : unwrapDb(requireDb().from("booking_tables").select("booking_id").in("table_id", tableIds).execute());
      LinkedHashSet<Long> bookingIds = new LinkedHashSet<>();
      for (JsonNode link : links) bookingIds.add(link.get("booking_id").asLong());
      query = query.in("id", bookingIds.isEmpty() ? List.of(-1L) : new ArrayList<>(bookingIds));
    }
    // PostgREST `or` filters break on commas/parentheses, so strip them from free text.
    String search = Json.str(filters, "search");
    String term = search == null ? null : Js.trim(search.replaceAll("[,()*%\\\\:\"']", " "));
    if (term != null && !term.isEmpty()) {
      final String value = term;
      query = query.or(String.join(",", List.of("customer_name", "customer_email", "customer_phone").stream().map(column -> column + ".ilike.*" + value + "*").toList()));
    }
    int page = filters.get("page").asInt();
    int size = filters.get("size").asInt();
    int from = page * size;
    PostgrestResult result = query.order("pending_rank").order("created_at", false).order("id", false).range(from, from + size - 1).execute();
    if (result.failed()) throw ReservationRules.dbError(result.error());
    long count = result.count() == null ? 0 : result.count();
    List<ObjectNode> content = new ArrayList<>();
    for (JsonNode row : result.data()) content.add(ReservationRules.toBooking(row));
    return Json.obj("content", content, "page", page, "size", size, "totalElements", count, "totalPages", (long) Math.ceil((double) count / size));
  }

  public ObjectNode pendingCount() {
    PostgrestResult result = requireDb().from("bookings").select("id", true, true).eq("email_verified", true).eq("status", "PENDING").execute();
    if (result.failed()) throw ReservationRules.dbError(result.error());
    return Json.obj("count", result.count() == null ? 0 : result.count());
  }

  private static Object orElse(JsonNode value, Object fallback) { return Json.truthy(value) ? value : fallback; }

  public ObjectNode create(JsonNode input) {
    ReservationRules.validateTimeRange(Json.str(input, "startTime"), Json.str(input, "endTime"));
    List<Long> tableIds = ids(input.get("tableIds"));
    ReservationRules.validateTablesForBooking(loadTables(tableIds), Json.str(input, "area"), input.get("numberOfGuests").asInt());
    assertTerraceSplit(null, Json.str(input, "area"), Json.str(input, "bookingDate"), Json.str(input, "startTime"), Json.str(input, "endTime"), new LinkedHashSet<>(tableIds).size());
    long id = save(null, Json.obj(
      "customer_name", input.get("customerName"), "customer_phone", orElse(input.get("customerPhone"), ReservationRules.PLACEHOLDER_PHONE),
      "customer_email", orElse(input.get("customerEmail"), ReservationRules.PLACEHOLDER_EMAIL), "booking_date", input.get("bookingDate"),
      "start_time", input.get("startTime"), "end_time", input.get("endTime"), "number_of_guests", input.get("numberOfGuests"), "area", input.get("area"),
      "note", input.get("note"), "status", orElse(input.get("status"), "APPROVED"), "email_verified", true),
      tableIds, "overlap", ReservationRules.realEmail(Json.str(input, "customerEmail")));
    changed(id);
    return booking(id);
  }

  public ObjectNode update(long id, JsonNode input) {
    ReservationRules.validateTimeRange(Json.str(input, "startTime"), Json.str(input, "endTime"));
    ObjectNode current = booking(id);
    List<Long> tableIds = ids(input.get("tableIds"));
    ReservationRules.validateTablesForBooking(loadTables(tableIds), Json.str(input, "area"), input.get("numberOfGuests").asInt());
    // Cancelled and rejected bookings hold no tables; they are checked again when restored.
    if (ReservationRules.BLOCKING.contains(Json.str(input, "status") != null ? Json.str(input, "status") : Json.str(current, "status"))) {
      assertTerraceSplit(id, Json.str(input, "area"), Json.str(input, "bookingDate"), Json.str(input, "startTime"), Json.str(input, "endTime"), new LinkedHashSet<>(tableIds).size());
    }
    save(id, Json.obj(
      "customer_name", input.get("customerName"), "customer_phone", orElse(input.get("customerPhone"), current.get("customerPhone")),
      "customer_email", orElse(input.get("customerEmail"), current.get("customerEmail")), "booking_date", input.get("bookingDate"),
      "start_time", input.get("startTime"), "end_time", input.get("endTime"), "number_of_guests", input.get("numberOfGuests"), "area", input.get("area"),
      "note", input.get("note"), "status", orElse(input.get("status"), null)),
      tableIds, "overlap", ReservationRules.realEmail(Json.str(input, "customerEmail")));
    ObjectNode booking = booking(id);
    List<String> before = new ArrayList<>();
    for (JsonNode table : Json.list(current.get("tables"))) before.add(Json.jsString(table.get("id")));
    before.sort(null);
    List<String> after = new ArrayList<>();
    for (Long tableId : new LinkedHashSet<>(tableIds)) after.add(String.valueOf(tableId));
    after.sort(null);
    boolean sameTables = String.join(",", before).equals(String.join(",", after));
    boolean moved = !sameTables || !Json.jsString(current.get("bookingDate")).equals(Json.jsString(booking.get("bookingDate")))
      || !Json.jsString(current.get("startTime")).equals(Json.jsString(booking.get("startTime")))
      || !Json.jsString(current.get("endTime")).equals(Json.jsString(booking.get("endTime")));
    String warning = moved ? notify("updated", booking) : null;
    changed(id);
    return withWarning(booking, warning);
  }

  public ObjectNode approve(long id) {
    ObjectNode current = booking(id);
    if (!ReservationRules.BLOCKING.contains(Json.str(current, "status"))) {
      assertTerraceSplit(id, Json.str(current, "area"), Json.str(current, "bookingDate"), Json.str(current, "startTime"), Json.str(current, "endTime"), Json.list(current.get("tables")).size());
    }
    transition(id, Json.obj("status", "APPROVED", "approved_at", iso(clock.get())), "overlap", false);
    ObjectNode booking = booking(id);
    String warning = notify("approved", booking);
    changed(id);
    return withWarning(booking, warning);
  }

  public ObjectNode reject(long id, JsonNode input) {
    JsonNode row = unwrapDb(requireDb().from("bookings").update(Json.obj("status", "REJECTED", "rejected_at", iso(clock.get()), "rejection_reason", input.get("reason")))
      .eq("id", id).select("id").maybeSingle().execute());
    if (row == null || row.isNull()) throw new ApiException(404, "Booking not found.");
    ObjectNode booking = booking(id);
    String warning = notify("rejected", booking);
    changed(id);
    return withWarning(booking, warning);
  }

  public ObjectNode cancel(long id) {
    ObjectNode current = booking(id);
    unwrapDb(requireDb().from("bookings").update(Json.obj("status", "CANCELLED")).eq("id", id).execute());
    ObjectNode booking = Json.copy(current);
    booking.put("status", "CANCELLED");
    String warning = notify("cancelled", booking);
    changed(id);
    return withWarning(booking, warning);
  }

  private static void checkEditableStatus(JsonNode input) {
    if (!ReservationRules.EDITABLE_TABLE_STATUSES.contains(Json.str(input, "status"))) throw bad("HOLD and RESERVED are booking-derived statuses and cannot be set manually.");
  }

  public ObjectNode createTable(JsonNode input) {
    ReservationRules.validateBounds(input);
    checkEditableStatus(input);
    return ReservationRules.toTable(unwrapDb(requireDb().from("restaurant_tables").insert(ReservationRules.tableRow(input)).select().single().execute()));
  }

  public ObjectNode updateTable(long id, JsonNode input) {
    ReservationRules.validateBounds(input);
    checkEditableStatus(input);
    JsonNode row = unwrapDb(requireDb().from("restaurant_tables").update(ReservationRules.tableRow(input)).eq("id", id).select().maybeSingle().execute());
    if (row == null || row.isNull()) throw new ApiException(404, "Table not found.");
    return ReservationRules.toTable(row);
  }

  public ObjectNode updateTablePosition(long id, JsonNode input) {
    ReservationRules.validateBounds(input);
    JsonNode row = unwrapDb(requireDb().from("restaurant_tables").update(Json.obj("x", input.get("x"), "y", input.get("y"), "width", input.get("width"),
      "height", input.get("height"), "rotation", input.get("rotation"))).eq("id", id).select().maybeSingle().execute());
    if (row == null || row.isNull()) throw new ApiException(404, "Table not found.");
    return ReservationRules.toTable(row);
  }

  /** The table after a photo change, plus the photo URL it replaced (for cleanup). */
  public record TablePhotoChange(ObjectNode table, String previousUrl) {}

  public TablePhotoChange setTablePhoto(long id, String imageUrl) {
    JsonNode current = unwrapDb(requireDb().from("restaurant_tables").select("id, image_url").eq("id", id).maybeSingle().execute());
    if (current == null || current.isNull()) throw new ApiException(404, "Table not found.");
    JsonNode row = unwrapDb(requireDb().from("restaurant_tables").update(Json.obj("image_url", imageUrl)).eq("id", id).select().maybeSingle().execute());
    if (row == null || row.isNull()) throw new ApiException(404, "Table not found.");
    return new TablePhotoChange(ReservationRules.toTable(row), Json.str(current, "image_url"));
  }

  public void deleteTable(long id) {
    // Tables are deactivated, never removed, so past bookings keep their table numbers.
    JsonNode row = unwrapDb(requireDb().from("restaurant_tables").update(Json.obj("active", false)).eq("id", id).select("id").maybeSingle().execute());
    if (row == null || row.isNull()) throw new ApiException(404, "Table not found.");
  }
}
