package com.menumaestro.backend.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.menumaestro.backend.exception.ApiException;
import com.menumaestro.backend.util.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Pure booking rules ported from the RTR BookingService/TableService (reservations.js). */
public final class ReservationRules {
  private ReservationRules() {}

  public static final List<String> AREAS = List.of("TERRACE", "ROMA", "VERONA", "SORRENTO");
  public static final Map<String, String> AREA_LABELS = Map.of("TERRACE", "Terrace", "ROMA", "Roma", "VERONA", "Verona", "SORRENTO", "Sorrento");
  public static final List<String> BOOKING_STATUSES = List.of("PENDING", "APPROVED", "REJECTED", "CANCELLED", "COMPLETED");
  public static final List<String> BLOCKING = List.of("PENDING", "APPROVED");
  public static final List<String> EDITABLE_TABLE_STATUSES = List.of("AVAILABLE", "OCCUPIED", "MAINTENANCE");
  public static final int OTP_TTL_SECONDS = 120;
  public static final int MAX_ONLINE_GUESTS = 6;
  static final int MAX_SELECTED_TABLES = 3;
  static final int MAX_SELECTED_CAPACITY = 6;
  static final int MAX_OTP_ATTEMPTS = 5;
  static final int MAX_OTP_RESENDS = 3;
  public static final String PLACEHOLDER_EMAIL = "timemap@riversideterrace.local";
  static final String PLACEHOLDER_PHONE = "0000000000";
  private static final List<String> VERONA_PAIRS = List.of("11,12", "13,14", "15,16");
  /** Last table number of each area's first column; tables can only be joined within one column. */
  static final Map<String, Integer> FIRST_COLUMN_LAST = Map.of("TERRACE", 6, "ROMA", 6, "VERONA", 10, "SORRENTO", 4);
  /**
   * Physical terrace furniture. T1-T12 are 2-seat places; joining them is done with these tables, so the
   * terrace can be split at most into 4 four-seat and 4 two-seat tables at any one time.
   */
  public static final int TERRACE_FOUR_TOPS = 4;
  public static final int TERRACE_TWO_TOPS = 4;

  /** 0 for the first column of the area, 1 for the second. */
  static int columnOf(String area, double number) {
    Integer last = FIRST_COLUMN_LAST.get(area);
    return last == null || number <= last ? 0 : 1;
  }

  /** Walk-ins and time-map bookings share a placeholder address; it must not trip the one-booking-per-day rule. */
  static boolean realEmail(String value) { return value != null && !value.isEmpty() && !PLACEHOLDER_EMAIL.equals(value); }

  static ApiException bad(String message) { return new ApiException(400, message); }

  public static double tableNumberValue(JsonNode table) {
    JsonNode number = Json.get(table, "tableNumber");
    if (number == null || number.isNull()) number = Json.get(table, "table_number");
    String digits = Json.jsString(number).replaceAll("\\D", "");
    return digits.isEmpty() ? 9007199254740991d : Double.parseDouble(digits);
  }

  static final Comparator<JsonNode> BY_NUMBER = Comparator.comparingDouble(ReservationRules::tableNumberValue);

  private static int capacity(JsonNode table) { return Json.get(table, "capacity") == null ? 0 : table.get("capacity").asInt(); }

  private static int capacity(List<? extends JsonNode> tables) { return tables.stream().mapToInt(ReservationRules::capacity).sum(); }

  /** Mirrors RTR BookingService.validateTablesForBooking. */
  public static void validateTablesForBooking(List<ObjectNode> tables, String bookingArea, int guests) {
    if (guests < 1) throw bad("Number of guests must be at least 1.");
    if (guests > MAX_SELECTED_CAPACITY) throw bad("Tables can be joined for at most 6 guests. Please contact the restaurant for larger parties.");
    if (tables.isEmpty() || tables.size() > MAX_SELECTED_TABLES) throw bad("You may select between 1 and 3 tables.");
    if (tables.stream().anyMatch(table -> !Json.bool(table, "active") || !"AVAILABLE".equals(Json.str(table, "status")))) {
      throw new ApiException(409, "The selected table is no longer available.", "TABLE_NOT_AVAILABLE");
    }
    if (tables.stream().anyMatch(table -> !bookingArea.equals(Json.str(table, "area")))) throw bad("Selected table does not belong to the selected area.");
    int total = capacity(tables);
    if (total > MAX_SELECTED_CAPACITY) throw bad("Selected tables may have a combined capacity of at most 6.");
    if (total < guests) throw bad("Selected table capacity is not enough for this party size.");
    if ("VERONA".equals(bookingArea) && guests <= 2) throw bad("Verona is only available for parties of 3 or more.");
    List<Double> numbers = tables.stream().map(ReservationRules::tableNumberValue).sorted().toList();
    boolean consecutive = true;
    for (int i = 1; i < numbers.size(); i++) if (numbers.get(i) != numbers.get(i - 1) + 1) consecutive = false;
    if (tables.size() > 1 && !consecutive) throw bad("Only adjacent, consecutively numbered tables may be selected.");
    if (numbers.stream().map(number -> columnOf(bookingArea, number)).distinct().count() > 1) throw bad("Only tables in the same column may be joined.");
    if (guests <= 2 && tables.size() != 1) throw bad("Parties of 1-2 guests may select only one table.");
    String joined = String.join(",", numbers.stream().map(com.menumaestro.backend.util.Js::numberToString).toList());
    if ("VERONA".equals(bookingArea) && numbers.stream().anyMatch(number -> number >= 11) && !VERONA_PAIRS.contains(joined)) {
      throw bad("V11-12, V13-14 and V15-16 are fixed pairs and cannot be split or joined.");
    }
    if (hasSpareTable(tables, guests)) throw bad("Only the required number of consecutively numbered tables may be joined.");
  }

  /** Areas searched by "suggest best table", in priority order. */
  public static List<String> suggestionAreas(String seating, int guests) {
    if ("outside".equals(seating)) return List.of("TERRACE");
    return guests <= 2 ? List.of("ROMA") : List.of("VERONA", "ROMA");
  }

  /** True when the group would still seat everyone without one of its end tables. */
  static boolean hasSpareTable(List<? extends JsonNode> tables, int guests) {
    if (tables.size() < 2) return false;
    List<JsonNode> sorted = new ArrayList<>(tables);
    sorted.sort(BY_NUMBER);
    int total = capacity(sorted);
    return total - capacity(sorted.get(0)) >= guests || total - capacity(sorted.get(sorted.size() - 1)) >= guests;
  }

  /**
   * Best adjacent group for the party: fewest tables that seat everyone (up to 6 seats),
   * then closest to the view. Uses real capacities, so 4-seat tables seat a party of 4 alone.
   */
  public static List<ObjectNode> bestTableGroup(String bookingArea, List<ObjectNode> available, int guests) {
    LinkedHashMap<Double, ObjectNode> byNumber = new LinkedHashMap<>();
    for (ObjectNode table : available) byNumber.putIfAbsent(tableNumberValue(table), table);
    java.util.function.Predicate<List<ObjectNode>> fits = group -> {
      int total = capacity(group);
      return total >= guests && total <= MAX_SELECTED_CAPACITY && !hasSpareTable(group, guests);
    };
    if ("VERONA".equals(bookingArea) && guests >= 3) {
      for (int start : new int[] {11, 13, 15}) {
        ObjectNode first = byNumber.get((double) start);
        ObjectNode second = byNumber.get((double) start + 1);
        if (first != null && second != null && fits.test(List.of(first, second))) return List.of(first, second);
      }
    }
    // Fewest empty seats first (keep 4-seat tables for bigger parties), then closest to the view.
    java.util.function.ToDoubleFunction<List<ObjectNode>> viewScore = group -> {
      double center = group.stream().mapToDouble(ReservationRules::tableNumberValue).sum() / group.size();
      int spare = capacity(group) - guests;
      return spare * 100 + ("VERONA".equals(bookingArea) ? Math.min(Math.abs(center - 10), Math.abs(center - 11)) : Math.abs(center - 6.5));
    };
    for (int count = 1; count <= MAX_SELECTED_TABLES; count++) {
      if (guests <= 2 && count > 1) break;
      List<ObjectNode> best = List.of();
      double bestScore = Double.POSITIVE_INFINITY;
      for (double start : byNumber.keySet()) {
        if (columnOf(bookingArea, start) != columnOf(bookingArea, start + count - 1)) continue;
        List<ObjectNode> group = new ArrayList<>();
        for (int offset = 0; offset < count; offset++) {
          ObjectNode table = byNumber.get(start + offset);
          // Verona 11-16 are only bookable as the fixed pairs above.
          if (table == null || ("VERONA".equals(bookingArea) && start + offset >= 11)) break;
          group.add(table);
        }
        if (group.size() == count && fits.test(group) && viewScore.applyAsDouble(group) < bestScore) {
          best = group;
          bestScore = viewScore.applyAsDouble(group);
        }
      }
      if (!best.isEmpty()) return best;
    }
    return List.of();
  }

  /** Terrace tables (2 seats each) needed to seat a party. */
  public static int terraceTablesFor(int guests) { return Math.max(1, Math.min(MAX_SELECTED_TABLES, (guests + 1) / 2)); }

  /** A terrace booking at a time: `tables` joined 2-seat places. */
  public record TerraceParty(String start, String end, int tables) {}

  /**
   * Whether every party can get physical tables from the terrace furniture. A party of n places needs
   * 2n seats from one or more tables (2 places: one 4-top or two 2-tops; 3 places: 4+2, 2+2+2 or 4+4).
   */
  public static boolean terraceFurnitureFits(List<Integer> parties) {
    boolean[][] reach = new boolean[TERRACE_FOUR_TOPS + 1][TERRACE_TWO_TOPS + 1];
    reach[0][0] = true;
    for (int places : parties) {
      boolean[][] next = new boolean[TERRACE_FOUR_TOPS + 1][TERRACE_TWO_TOPS + 1];
      boolean any = false;
      for (int fours = 0; fours <= TERRACE_FOUR_TOPS; fours++) {
        for (int twos = 0; twos <= TERRACE_TWO_TOPS; twos++) {
          if (!reach[fours][twos]) continue;
          for (int useFours = 0; fours + useFours <= TERRACE_FOUR_TOPS; useFours++) {
            for (int useTwos = 0; twos + useTwos <= TERRACE_TWO_TOPS; useTwos++) {
              int seats = useFours * 4 + useTwos * 2;
              // Only minimal combinations: dropping any one table would leave too few seats.
              boolean minimal = seats >= places * 2 && (useFours == 0 || seats - 4 < places * 2) && (useTwos == 0 || seats - 2 < places * 2);
              if (minimal) { next[fours + useFours][twos + useTwos] = true; any = true; }
            }
          }
        }
      }
      if (!any) return false;
      reach = next;
    }
    return true;
  }

  private static String hm(String time) { return time == null || time.length() <= 5 ? time : time.substring(0, 5); }

  /** Whether a new terrace party of `tables` places fits the furniture throughout [start, end). */
  public static boolean terraceSplitAllowed(List<TerraceParty> others, String start, String end, int tables) {
    String from = hm(start);
    String to = hm(end);
    // The number of parties only grows when one starts, so checking each start point is enough.
    List<String> points = new ArrayList<>();
    points.add(from);
    for (TerraceParty other : others) {
      String otherStart = hm(other.start());
      if (otherStart.compareTo(from) > 0 && otherStart.compareTo(to) < 0) points.add(otherStart);
    }
    for (String point : points) {
      List<Integer> parties = new ArrayList<>();
      parties.add(tables);
      for (TerraceParty other : others) if (hm(other.start()).compareTo(point) <= 0 && hm(other.end()).compareTo(point) > 0) parties.add(other.tables());
      if (!terraceFurnitureFits(parties)) return false;
    }
    return true;
  }

  public static ApiException terraceFull() {
    return new ApiException(409, "The terrace is already split into the most tables it can hold at this time (4 four-seat and 4 two-seat). Please join tables, choose another time or another area.", "TERRACE_SPLIT_LIMIT");
  }

  public static String hashOtp(String otp) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(otp.getBytes(StandardCharsets.UTF_8))); }
    catch (Exception error) { throw new IllegalStateException(error); }
  }

  /** Wall-clock date (yyyy-MM-dd) and time (HH:mm) at the restaurant. */
  public record Now(String date, String time) {}

  public static Now restaurantNow(String timeZone, Instant now) {
    ZonedDateTime local = now.atZone(ZoneId.of(timeZone));
    return new Now(String.format("%04d-%02d-%02d", local.getYear(), local.getMonthValue(), local.getDayOfMonth()),
      String.format("%02d:%02d", local.getHour(), local.getMinute()));
  }

  public static void validateTimeRange(String startTime, String endTime) {
    if (endTime.compareTo(startTime) <= 0) throw bad("End time must be after start time.");
  }

  public static void validateNotPast(String bookingDate, String startTime, Now now) {
    if (bookingDate.compareTo(now.date()) < 0 || (bookingDate.equals(now.date()) && startTime.compareTo(now.time()) < 0)) throw bad("Booking time cannot be in the past.");
  }

  static void validateBounds(JsonNode input) {
    double x = input.get("x").asDouble();
    double y = input.get("y").asDouble();
    double width = input.get("width").asDouble();
    double height = input.get("height").asDouble();
    if (x < 0 || y < 0 || width <= 0 || height <= 0 || x + width > 1200 || y + height > 800) throw bad("Table coordinates are outside the allowed floor plan.");
  }

  // ---------- mapping ----------
  private static Object field(JsonNode row, String key) {
    JsonNode value = Json.get(row, key);
    return value == null ? Json.UNDEFINED : value;
  }

  private static Object hhmm(JsonNode row, String key) {
    JsonNode value = Json.get(row, key);
    if (value == null) return Json.UNDEFINED;
    if (!Json.truthy(value)) return value;
    String text = Json.jsString(value);
    return text.length() <= 5 ? text : text.substring(0, 5);
  }

  public static ObjectNode toTable(JsonNode row) { return toTable(row, Json.get(row, "status")); }

  public static ObjectNode toTable(JsonNode row, Object derivedStatus) {
    String area = Json.str(row, "area");
    return Json.obj("id", Json.num(Json.get(row, "id") == null ? Double.NaN : row.get("id").asDouble()), "tableNumber", field(row, "table_number"), "area", field(row, "area"),
      "capacity", field(row, "capacity"), "floor", field(row, "area"), "locationName", area != null && AREA_LABELS.containsKey(area) ? AREA_LABELS.get(area) : field(row, "area"),
      "status", derivedStatus == null ? Json.UNDEFINED : derivedStatus,
      "x", field(row, "x"), "y", field(row, "y"), "width", field(row, "width"), "height", field(row, "height"), "rotation", field(row, "rotation"), "shape", field(row, "shape"),
      "imageUrl", field(row, "image_url"), "areaImageUrl", field(row, "area_image_url"), "description", field(row, "description"), "active", field(row, "active"));
  }

  public static ObjectNode toBooking(JsonNode row) {
    List<ObjectNode> tables = new ArrayList<>();
    for (JsonNode link : Json.list(Json.get(row, "booking_tables"))) {
      JsonNode table = Json.get(link, "table");
      if (Json.truthy(table)) tables.add(toTable(table));
    }
    tables.sort(BY_NUMBER);
    return Json.obj("id", Json.num(row.get("id").asDouble()), "bookingCode", field(row, "booking_code"), "customerName", field(row, "customer_name"),
      "customerPhone", field(row, "customer_phone"), "customerEmail", field(row, "customer_email"), "bookingDate", field(row, "booking_date"),
      "startTime", hhmm(row, "start_time"), "endTime", hhmm(row, "end_time"), "numberOfGuests", field(row, "number_of_guests"), "area", field(row, "area"),
      "note", field(row, "note"), "rejectionReason", field(row, "rejection_reason"), "status", field(row, "status"), "emailVerified", field(row, "email_verified"),
      "tables", tables, "createdAt", field(row, "created_at"), "updatedAt", field(row, "updated_at"), "approvedAt", field(row, "approved_at"), "rejectedAt", field(row, "rejected_at"));
  }

  static ObjectNode tableRow(JsonNode input) {
    return Json.obj("table_number", field(input, "tableNumber"), "area", field(input, "area"), "capacity", field(input, "capacity"), "status", field(input, "status"),
      "x", field(input, "x"), "y", field(input, "y"), "width", field(input, "width"), "height", field(input, "height"), "rotation", field(input, "rotation"),
      "shape", field(input, "shape"), "image_url", field(input, "imageUrl"), "area_image_url", field(input, "areaImageUrl"), "description", field(input, "description"),
      "active", field(input, "active"));
  }

  /** Converts PostgREST/RPC errors raised by the reservation functions into API errors. */
  public static ApiException dbError(com.menumaestro.backend.client.supabase.PostgrestResult.Error error) {
    String message = error == null || error.message() == null ? "" : error.message();
    for (Object[] entry : new Object[][] {{"TABLE_NOT_AVAILABLE", 409}, {"EMAIL_ALREADY_BOOKED_TODAY", 409}, {"BOOKING_NOT_FOUND", 404}}) {
      String code = (String) entry[0];
      if (message.startsWith(code + ":")) return new ApiException((Integer) entry[1], message.substring(code.length() + 1).trim(), code);
    }
    if (error != null && "23505".equals(error.code())) return new ApiException(409, "A record with the same value already exists.", "DUPLICATE");
    return ApiException.database();
  }
}
