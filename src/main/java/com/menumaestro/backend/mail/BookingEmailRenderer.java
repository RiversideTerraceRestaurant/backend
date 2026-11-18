package com.menumaestro.backend.mail;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.menumaestro.backend.common.json.Js;
import com.menumaestro.backend.common.json.Json;

import tools.jackson.databind.JsonNode;

/** Booking email templates, ported from the RTR Spring EmailService (mailer.js). */
public final class BookingEmailRenderer {
  private BookingEmailRenderer() {}

  public record Email(String subject, String html) {}

  private static final String NAME = "Riverside Terrace Restaurant";
  private static final String ADDRESS = "493 Tran Hung Dao Street, An Hai Ward, Da Nang City, Viet Nam";
  private static final String PHONE = "(+84) 911500440";

  static String escapeHtml(String value) {
    String text = value == null ? "" : value;
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
  }

  /** String(value ?? '') */
  private static String text(JsonNode value) { return value == null || value.isNull() ? "" : Json.jsString(value); }

  private static String orNone(JsonNode value) {
    return value != null && !value.isNull() && !Js.trim(Json.jsString(value)).isEmpty() && Json.truthy(value) ? Json.jsString(value) : "No reason provided.";
  }

  private static String statusPanel(String title, String message, String color, String background) {
    return "\n<div style=\"margin:22px 0;padding:18px 20px;border-radius:16px;background:" + background + ";border:1px solid " + color + ";\">\n"
      + "  <div style=\"font-size:18px;font-weight:800;color:" + color + ";\">" + escapeHtml(title) + "</div>\n"
      + "  <div style=\"margin-top:6px;font-size:14px;line-height:1.6;color:" + color + ";\">" + message + "</div>\n"
      + "</div>";
  }

  private static final String HOLD_NOTICE = "\n<div style=\"margin:-10px 0 22px;padding:18px 20px;border-radius:16px;background:#fff7ed;border:1px solid #f59e0b;\">\n"
    + "  <div style=\"font-size:16px;font-weight:800;color:#92400e;\">Important arrival notice</div>\n"
    + "  <div style=\"margin-top:6px;font-size:14px;line-height:1.6;color:#92400e;\">We will hold your table for only <strong>15 minutes</strong> from your reservation time. Please arrive on time.</div>\n"
    + "</div>";

  private record Template(String subject, String title, String intro, String highlight) {}

  private static Template template(String kind, JsonNode booking, Map<String, String> extra) {
    return switch (kind) {
      case "otp" -> new Template("Riverside Terrace email verification", "Email Verification",
        "Please use the verification code below to complete your table reservation.",
        "\n<div style=\"margin:24px 0;text-align:center;\">\n"
          + "  <div style=\"display:inline-block;min-width:180px;padding:18px 28px;border-radius:14px;background:#7f1d1d;color:#fff;font-size:34px;font-weight:800;letter-spacing:8px;\">" + escapeHtml(extra.get("otp") == null ? "undefined" : extra.get("otp")) + "</div>\n"
          + "  <p style=\"margin:14px 0 0;color:#6b7280;font-size:14px;\">This code is valid for <strong>2 minutes</strong>.</p>\n"
          + "</div>");
      case "pending" -> new Template("Riverside Terrace booking received", "Booking Request Received",
        "Thank you for choosing Riverside Terrace. We have received your request and our team is reviewing your table reservation.",
        statusPanel("Pending approval", "Your booking is waiting for restaurant confirmation.", "#991b1b", "#fee2e2"));
      case "approved" -> new Template("Riverside Terrace booking approved", "Booking Confirmed",
        "Thank you for choosing Riverside Terrace. Your booking has been confirmed and we look forward to welcoming you.",
        statusPanel("Confirmed", "Your table is reserved. Please arrive on time so we can prepare the best experience for your party.", "#166534", "#dcfce7") + HOLD_NOTICE);
      case "rejected" -> new Template("Riverside Terrace booking rejected", "Booking Update",
        "Thank you for your interest in Riverside Terrace. Unfortunately, we cannot confirm this booking request.",
        statusPanel("Rejected", "Reason: " + escapeHtml(orNone(Json.get(booking, "rejectionReason"))), "#991b1b", "#fee2e2"));
      case "cancelled" -> new Template("Riverside Terrace booking cancelled", "Booking Cancelled",
        "Your booking has been cancelled. Thank you for contacting Riverside Terrace, and we hope to welcome you another time.",
        statusPanel("Cancelled", "This reservation is no longer active.", "#374151", "#f3f4f6"));
      case "updated" -> new Template("Riverside Terrace booking updated", "Booking Updated",
        "Thank you for choosing Riverside Terrace. Your booking details have been updated by our team.",
        statusPanel("Updated", "Please review the latest reservation details below.", "#92400e", "#fef3c7"));
      default -> throw new IllegalArgumentException("Unknown email template: " + kind);
    };
  }

  private static String row(String label, String value) {
    return "\n<tr>\n"
      + "  <td style=\"width:38%;padding:13px 18px;border-top:1px solid #eadfd8;color:#6b7280;font-weight:700;\">" + escapeHtml(label) + "</td>\n"
      + "  <td style=\"padding:13px 18px;border-top:1px solid #eadfd8;color:#2f241f;font-weight:700;\">" + escapeHtml(value) + "</td>\n"
      + "</tr>";
  }

  public static Email render(String kind, JsonNode booking, Map<String, String> extra) {
    Template template = template(kind, booking, extra);
    List<String> tables = Json.list(Json.get(booking, "tables")).stream().map(table -> Json.jsString(Json.get(table, "tableNumber"))).collect(Collectors.toList());
    String details = row("Booking code", text(booking.get("bookingCode"))) + row("Status", text(booking.get("status"))) + row("Area", text(booking.get("area")))
      + row("Table", String.join(", ", tables)) + row("Date", text(booking.get("bookingDate")))
      + row("Time", Json.jsString(booking.get("startTime")) + " - " + Json.jsString(booking.get("endTime"))) + row("Guests", Json.jsString(booking.get("numberOfGuests")))
      + row("Phone", text(booking.get("customerPhone"))) + row("Email", text(booking.get("customerEmail"))) + row("Note", orNone(booking.get("note")));
    String html = "<!doctype html>\n<html>\n"
      + "  <body style=\"margin:0;background:#f7f2ee;font-family:Arial,Helvetica,sans-serif;color:#2f241f;\">\n"
      + "    <div style=\"max-width:680px;margin:0 auto;padding:28px 14px;\">\n"
      + "      <div style=\"overflow:hidden;border-radius:20px;background:#ffffff;border:1px solid #eadfd8;box-shadow:0 14px 40px rgba(47,36,31,0.12);\">\n"
      + "        <div style=\"background:#7f1d1d;padding:30px 28px;color:#ffffff;\">\n"
      + "          <div style=\"font-size:12px;font-weight:700;letter-spacing:2px;text-transform:uppercase;opacity:.86;\">" + NAME + "</div>\n"
      + "          <h1 style=\"margin:10px 0 0;font-size:28px;line-height:1.2;font-weight:800;\">" + escapeHtml(template.title()) + "</h1>\n"
      + "          <p style=\"margin:10px 0 0;font-size:15px;line-height:1.6;opacity:.92;\">Italian-inspired riverside dining in Da Nang</p>\n"
      + "        </div>\n"
      + "        <div style=\"padding:30px 28px;\">\n"
      + "          <p style=\"margin:0 0 14px;font-size:16px;line-height:1.7;\">Dear <strong>" + escapeHtml(text(booking.get("customerName"))) + "</strong>,</p>\n"
      + "          <p style=\"margin:0 0 22px;font-size:16px;line-height:1.7;\">" + escapeHtml(template.intro()) + "</p>\n"
      + "          " + template.highlight() + "\n"
      + "          <div style=\"margin-top:26px;border:1px solid #eadfd8;border-radius:16px;overflow:hidden;\">\n"
      + "            <div style=\"padding:14px 18px;background:#faf7f4;font-weight:800;text-transform:uppercase;letter-spacing:.8px;font-size:12px;color:#7f1d1d;\">Reservation Details</div>\n"
      + "            <table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;border-collapse:collapse;font-size:15px;\">" + details + "</table>\n"
      + "          </div>\n"
      + "          <div style=\"margin-top:26px;padding:18px;border-radius:16px;background:#fff7ed;border:1px solid #fed7aa;\">\n"
      + "            <p style=\"margin:0;font-size:15px;line-height:1.7;\"><strong>Thank you for choosing Riverside Terrace.</strong><br>Our team is preparing a warm, comfortable dining experience for you and your guests.</p>\n"
      + "          </div>\n"
      + "          <p style=\"margin:24px 0 0;font-size:14px;line-height:1.7;color:#6b7280;\">If you need to adjust your reservation, please contact the restaurant team before your arrival time.</p>\n"
      + "        </div>\n"
      + "        <div style=\"padding:20px 28px;background:#2f241f;color:#f8efe8;\">\n"
      + "          <div style=\"font-weight:800;\">" + NAME + "</div>\n"
      + "          <div style=\"margin-top:6px;font-size:13px;line-height:1.6;color:#e8d7cd;\">" + ADDRESS + "<br>" + PHONE + "</div>\n"
      + "        </div>\n"
      + "      </div>\n"
      + "    </div>\n"
      + "  </body>\n"
      + "</html>";
    return new Email(template.subject(), html);
  }
}
