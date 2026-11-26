package com.menumaestro.backend.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.menumaestro.backend.client.WebPushSender;
import com.menumaestro.backend.client.supabase.PostgrestResult;
import com.menumaestro.backend.client.supabase.SupabaseClient;
import com.menumaestro.backend.exception.ApiException;
import com.menumaestro.backend.util.Async;
import com.menumaestro.backend.util.Js;
import com.menumaestro.backend.util.Json;
import com.menumaestro.backend.validation.Schema;
import com.menumaestro.backend.validation.Z;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Web push alerts for admins, ported from the RTR PushNotificationService (push.js). */
public class PushService {
  private static final Logger log = LoggerFactory.getLogger(PushService.class);
  private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  public static final Schema SUBSCRIPTION = Z.object()
    .field("endpoint", Z.string().url().max(2000))
    .field("keys", Z.object().field("p256dh", Z.string().min(1).max(200)).field("auth", Z.string().min(1).max(100)));
  public static final Schema ENDPOINT = Z.object().field("endpoint", Z.string().url().max(2000));

  /** Sends one notification; returns normally on success or throws a {@link WebPushSender.PushException}. */
  public interface Sender { void send(String endpoint, String p256dh, String auth, String payload) throws Exception; }

  private final SupabaseClient db;
  private final String publicKey;
  private final boolean configured;
  private final Sender sender;

  public PushService(SupabaseClient db, String publicKey, String privateKey, String subject, Sender sender) {
    this.db = db;
    this.publicKey = publicKey;
    this.configured = db != null && publicKey != null && !publicKey.isEmpty() && privateKey != null && !privateKey.isEmpty();
    Sender resolved = sender;
    if (resolved == null && configured) {
      WebPushSender webPush = new WebPushSender(subject == null || subject.isEmpty() ? "mailto:admin@riversideterrace.vn" : subject, publicKey, privateKey, com.menumaestro.backend.client.supabase.SupabaseHttp::send);
      resolved = webPush::send;
    }
    this.sender = resolved;
  }

  public boolean configured() { return configured; }

  public static ObjectNode bookingNotification(JsonNode booking) {
    List<String> numbers = new ArrayList<>();
    for (JsonNode table : Json.list(booking.get("tables"))) numbers.add(Json.jsString(table.get("tableNumber")));
    String tables = numbers.isEmpty() || String.join(", ", numbers).isEmpty() ? "—" : String.join(", ", numbers);
    List<String> parts = new ArrayList<>(List.of(Json.str(booking, "bookingDate").split("-", -1)));
    java.util.Collections.reverse(parts);
    String date = String.join("/", parts.subList(0, Math.min(2, parts.size())));
    String id = Json.jsString(booking.get("id"));
    return Json.obj("title", "New booking " + Json.jsString(booking.get("bookingCode")),
      "body", Json.jsString(booking.get("customerName")) + " · " + Json.jsString(booking.get("numberOfGuests")) + " guests · " + date + " " + Json.jsString(booking.get("startTime"))
        + " · " + Json.jsString(booking.get("area")) + " " + tables,
      "url", "/admin/booking/list?bookingId=" + id, "tag", "booking-" + id);
  }

  private void requireReady() {
    if (!configured) throw new ApiException(503, "Push notifications are not configured. Set VAPID_PUBLIC_KEY and VAPID_PRIVATE_KEY.");
  }

  private static JsonNode unwrap(PostgrestResult result) {
    if (result.failed()) throw new ApiException(502, "Database request failed. Please try again.");
    return result.data();
  }

  private boolean sendTo(JsonNode subscription, ObjectNode payload) {
    try {
      sender.send(Json.str(subscription, "endpoint"), Json.str(subscription, "p256dh"), Json.str(subscription, "auth"), Json.stringify(payload));
      return true;
    } catch (Exception error) {
      int status = error instanceof WebPushSender.PushException push ? push.statusCode() : 0;
      // The browser revoked the subscription: forget it.
      if (status == 404 || status == 410) db.from("push_subscriptions").delete().eq("id", subscription.get("id")).execute();
      else log.warn(Json.stringify(Json.obj("event", "push.failed", "status", status == 0 ? null : status)));
      return false;
    }
  }

  private ObjectNode broadcast(ObjectNode payload) {
    if (!configured) return Json.obj("registered", 0, "accepted", 0);
    List<JsonNode> subscriptions = Json.list(unwrap(db.from("push_subscriptions").select("id, endpoint, p256dh, auth").execute()));
    List<CompletableFuture<Boolean>> results = subscriptions.stream()
      .map(subscription -> CompletableFuture.supplyAsync(() -> sendTo(subscription, payload), Async.EXECUTOR)).toList();
    long accepted = results.stream().filter(Async::join).count();
    return Json.obj("registered", subscriptions.size(), "accepted", accepted);
  }

  public ObjectNode config() {
    if (!configured) return Json.obj("enabled", false, "publicKey", "", "subscriptionCount", 0);
    PostgrestResult result = db.from("push_subscriptions").select("id", true, true).execute();
    return Json.obj("enabled", true, "publicKey", publicKey, "subscriptionCount", result.count() == null ? 0 : result.count());
  }

  public ObjectNode subscribe(JsonNode input, String userId, String userAgent) {
    requireReady();
    JsonNode keys = input.get("keys");
    unwrap(db.from("push_subscriptions").upsert(Json.obj("endpoint", input.get("endpoint"), "p256dh", keys.get("p256dh"), "auth", keys.get("auth"),
      "user_id", userId == null || userId.isEmpty() ? null : userId, "user_agent", Js.slice(userAgent == null ? "" : userAgent, 500),
      "updated_at", ISO.format(Instant.now())), "endpoint").execute());
    return Json.obj("ok", true);
  }

  public ObjectNode unsubscribe(JsonNode input) {
    requireReady();
    unwrap(db.from("push_subscriptions").delete().eq("endpoint", Json.str(input, "endpoint")).execute());
    return Json.obj("ok", true);
  }

  public ObjectNode test() {
    requireReady();
    return broadcast(Json.obj("title", "Riverside Terrace", "body", "Push notifications are working on this device.", "url", "/admin/booking", "tag", "push-test"));
  }

  public ObjectNode notifyNewBooking(JsonNode booking) { return broadcast(bookingNotification(booking)); }
}
