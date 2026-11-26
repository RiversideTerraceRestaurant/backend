package com.menumaestro.backend.reservation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.menumaestro.backend.common.json.Json;

import tools.jackson.databind.JsonNode;

/**
 * Off-server copy of upcoming reservations, so staff can keep seating guests if the server is
 * down: a CSV file (opens in Excel/Google Sheets, UTF-8 with BOM for Vietnamese names) and a
 * readable HTML table for the daily backup email.
 */
public final class BookingBackup {
  private BookingBackup() {}

  static final List<String> HEADERS = List.of("Date", "Start", "End", "Booking code", "Status", "Customer", "Phone", "Email", "Guests", "Area", "Tables", "Note", "Created at");

  private static String text(JsonNode booking, String key) {
    JsonNode value = Json.get(booking, key);
    return value == null || value.isNull() ? "" : Json.jsString(value);
  }

  private static String tables(JsonNode booking) {
    return Json.list(Json.get(booking, "tables")).stream().map(table -> text(table, "tableNumber")).collect(Collectors.joining(" + "));
  }

  /** Walk-in and time-map bookings store placeholder contacts; show them as empty. */
  private static String phone(JsonNode booking) {
    String value = text(booking, "customerPhone");
    return ReservationRules.PLACEHOLDER_PHONE.equals(value) ? "" : value;
  }

  private static String email(JsonNode booking) {
    String value = text(booking, "customerEmail");
    return ReservationRules.PLACEHOLDER_EMAIL.equals(value) ? "" : value;
  }

  static List<String> row(JsonNode booking) {
    String area = text(booking, "area");
    return List.of(text(booking, "bookingDate"), text(booking, "startTime"), text(booking, "endTime"), text(booking, "bookingCode"), text(booking, "status"),
      text(booking, "customerName"), phone(booking), email(booking), text(booking, "numberOfGuests"), ReservationRules.AREA_LABELS.getOrDefault(area, area),
      tables(booking), text(booking, "note"), text(booking, "createdAt"));
  }

  /** One CSV cell. Guest-typed text starting with = + - @ is prefixed with ' so spreadsheets never run it as a formula. */
  static String cell(String value) {
    String safe = value == null ? "" : value;
    if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
    return "\"" + safe.replace("\"", "\"\"") + "\"";
  }

  public static byte[] csv(List<? extends JsonNode> bookings) {
    StringBuilder out = new StringBuilder("﻿");
    out.append(HEADERS.stream().map(BookingBackup::cell).collect(Collectors.joining(","))).append("\r\n");
    for (JsonNode booking : bookings) out.append(row(booking).stream().map(BookingBackup::cell).collect(Collectors.joining(","))).append("\r\n");
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static String escape(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }

  /** Email body: bookings grouped by day, with time, tables, guest, phone and status. */
  public static String html(String from, List<? extends JsonNode> bookings, String generatedAt) {
    Map<String, List<JsonNode>> byDate = new LinkedHashMap<>();
    for (JsonNode booking : bookings) byDate.computeIfAbsent(text(booking, "bookingDate"), day -> new ArrayList<>()).add(booking);
    StringBuilder body = new StringBuilder();
    body.append("<!doctype html><html><body style=\"margin:0;background:#f7f2ee;font-family:Arial,Helvetica,sans-serif;color:#2f241f;\">")
      .append("<div style=\"max-width:760px;margin:0 auto;padding:24px 12px;\">")
      .append("<h1 style=\"margin:0;font-size:22px;color:#7f1d1d;\">Reservation backup</h1>")
      .append("<p style=\"margin:6px 0 18px;font-size:14px;color:#6b7280;\">Pending and confirmed bookings from <strong>").append(escape(from))
      .append("</strong> onwards · ").append(bookings.size()).append(" booking(s) · generated ").append(escape(generatedAt))
      .append(". The attached CSV opens in Excel or Google Sheets.</p>");
    if (bookings.isEmpty()) body.append("<p style=\"padding:16px;border-radius:12px;background:#fff;border:1px solid #eadfd8;\">No upcoming bookings.</p>");
    for (Map.Entry<String, List<JsonNode>> day : byDate.entrySet()) {
      body.append("<h2 style=\"margin:22px 0 8px;font-size:16px;color:#2f241f;\">").append(escape(day.getKey())).append(" · ").append(day.getValue().size()).append("</h2>")
        .append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;border-collapse:collapse;background:#fff;border:1px solid #eadfd8;font-size:13px;\">")
        .append("<tr style=\"background:#faf7f4;text-align:left;\">");
      for (String header : List.of("Time", "Tables", "Guest", "Phone", "Pax", "Status", "Note")) {
        body.append("<th style=\"padding:8px 10px;border-bottom:1px solid #eadfd8;\">").append(header).append("</th>");
      }
      body.append("</tr>");
      for (JsonNode booking : day.getValue()) {
        String area = text(booking, "area");
        body.append("<tr>")
          .append(td(text(booking, "startTime") + "–" + text(booking, "endTime")))
          .append(td(ReservationRules.AREA_LABELS.getOrDefault(area, area) + " " + tables(booking)))
          .append(td(text(booking, "customerName") + "\n" + text(booking, "bookingCode")))
          .append(td(phone(booking)))
          .append(td(text(booking, "numberOfGuests")))
          .append(td(text(booking, "status")))
          .append(td(text(booking, "note")))
          .append("</tr>");
      }
      body.append("</table>");
    }
    return body.append("</div></body></html>").toString();
  }

  private static String td(String value) {
    return "<td style=\"padding:8px 10px;border-bottom:1px solid #f1e8e1;vertical-align:top;\">" + escape(value).replace("\n", "<br><span style=\"color:#9ca3af;font-size:11px;\">") + (value.contains("\n") ? "</span>" : "") + "</td>";
  }
}
