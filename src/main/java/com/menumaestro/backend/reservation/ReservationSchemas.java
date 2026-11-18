package com.menumaestro.backend.reservation;

import java.util.Map;

import com.menumaestro.backend.common.json.Js;
import com.menumaestro.backend.common.json.Json;
import com.menumaestro.backend.common.validation.Schema;
import com.menumaestro.backend.common.validation.Z;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Request schemas for reservations (reservations.js `schemas`). */
public final class ReservationSchemas {
  private ReservationSchemas() {}

  static Schema date() {
    return Z.string().regex("^\\d{4}-\\d{2}-\\d{2}$", "Use YYYY-MM-DD.").refine(value -> {
      // Same acceptance as Date.parse(`${value}T00:00:00Z`): month 01-12, day 01-31.
      String text = value.stringValue();
      if (!text.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
      int month = Integer.parseInt(text.substring(5, 7));
      int day = Integer.parseInt(text.substring(8, 10));
      return month >= 1 && month <= 12 && day >= 1 && day <= 31;
    }, "Invalid date.");
  }

  static Schema time() {
    return Z.string().regex("^([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d)?$", "Use HH:mm.").transform(value -> Json.node(Js.slice(value.stringValue(), 5)));
  }

  static Schema area() { return Z.enumOf(ReservationRules.AREAS); }

  static Schema status() { return Z.enumOf(ReservationRules.BOOKING_STATUSES); }

  static Schema tableIds() { return Z.array(Z.coerceNumber().integer().positive()).min(1).max(20); }

  static Schema optionalText(int max) {
    return Z.string().trim().max(max).nullish().transform(value -> Json.truthy(value) ? value : Json.F.nullNode());
  }

  static Schema email() { return Z.string().trim().toLowerCase().email().max(160); }

  static Schema phone() { return Z.string().trim().regex("^[0-9+\\-\\s().]{6,32}$", "Phone number is invalid."); }

  public static final Z.ObjectSchema AVAILABILITY = Z.object()
    .field("area", area()).field("date", date()).field("startTime", time()).field("endTime", time())
    .field("guests", Z.coerceNumber().integer().min(1).max(200).withDefault(() -> Json.num(1)));

  public static final Schema SUGGEST = Z.object()
    .field("seating", Z.enumOf("inside", "outside")).field("date", date()).field("startTime", time()).field("endTime", time())
    .field("guests", Z.coerceNumber().integer().min(1).max(ReservationRules.MAX_ONLINE_GUESTS));

  public static final Schema REQUEST_OTP = Z.object()
    .field("customerName", Z.string().trim().min(1).max(120)).field("customerPhone", phone()).field("customerEmail", email())
    .field("bookingDate", date()).field("startTime", time()).field("endTime", time())
    .field("numberOfGuests", Z.number().integer().min(1).max(200))
    .field("area", area()).field("note", optionalText(1000)).field("tableIds", tableIds());

  public static final Schema VERIFY_OTP = Z.object()
    .field("verificationId", Z.string().uuid())
    .field("otp", Z.string().trim().regex("^\\d{6}$", "OTP must be 6 digits."));

  public static final Schema RESEND_OTP = Z.object().field("verificationId", Z.string().uuid());

  public static final Schema ADMIN_BOOKING = Z.object()
    .field("customerName", Z.string().trim().min(1).max(120))
    .field("customerEmail", Z.union(email(), Z.literal("")).nullish())
    .field("customerPhone", Z.union(phone(), Z.literal("")).nullish())
    .field("numberOfGuests", Z.number().integer().min(1).max(200))
    .field("bookingDate", date()).field("startTime", time()).field("endTime", time())
    .field("area", area()).field("tableIds", tableIds()).field("status", status().nullish()).field("note", optionalText(1000));

  public static final Schema REJECT = Z.object().field("reason", Z.string().trim().min(1).max(1000));

  public static final Z.ObjectSchema SEARCH = Z.object()
    .field("date", date().optional()).field("startTime", time().optional()).field("endTime", time().optional())
    .field("area", area().optional()).field("status", status().optional())
    .field("tableNumber", Z.string().trim().max(32).optional()).field("search", Z.string().trim().max(160).optional())
    .field("page", Z.coerceNumber().integer().min(0).max(100000).withDefault(() -> Json.num(0)))
    .field("size", Z.coerceNumber().integer().min(1).max(100).withDefault(() -> Json.num(10)));

  public static final Z.ObjectSchema TABLE = Z.object()
    .field("tableNumber", Z.string().trim().min(1).max(32)).field("area", area())
    .field("capacity", Z.number().integer().min(1).max(50))
    .field("status", Z.enumOf("AVAILABLE", "HOLD", "RESERVED", "OCCUPIED", "MAINTENANCE").withDefault(() -> Json.node("AVAILABLE")))
    .field("x", Z.number().finite()).field("y", Z.number().finite()).field("width", Z.number().finite()).field("height", Z.number().finite())
    .field("rotation", Z.number().finite().withDefault(() -> Json.num(0)))
    .field("shape", Z.enumOf("RECTANGLE", "CIRCLE").withDefault(() -> Json.node("RECTANGLE")))
    .field("imageUrl", optionalText(2000)).field("areaImageUrl", optionalText(2000)).field("description", optionalText(500))
    .field("active", Z.bool().withDefault(() -> Json.node(true)));

  public static final Schema POSITION = Z.object()
    .field("x", Z.number().finite()).field("y", Z.number().finite()).field("width", Z.number().finite()).field("height", Z.number().finite())
    .field("rotation", Z.number().finite().withDefault(() -> Json.num(0)));

  /** Query-string params, dropping empty values so optional filters stay optional. */
  public static ObjectNode fromQuery(HttpServletRequest request) {
    ObjectNode result = Json.obj();
    for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
      String value = null;
      for (String candidate : entry.getValue()) if (!candidate.isEmpty()) value = candidate;
      if (value != null) result.put(entry.getKey(), value);
    }
    return result;
  }

  static JsonNode node(String value) { return value == null ? null : Json.node(value); }
}
