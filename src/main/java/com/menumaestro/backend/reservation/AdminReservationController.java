package com.menumaestro.backend.reservation;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import com.menumaestro.backend.auth.AuthService;
import com.menumaestro.backend.common.json.Json;
import com.menumaestro.backend.common.validation.Schema;
import com.menumaestro.backend.common.validation.Z;
import com.menumaestro.backend.common.web.ApiException;
import com.menumaestro.backend.common.web.RateLimiter;
import com.menumaestro.backend.common.web.RequestBodies;
import com.menumaestro.backend.common.web.Responses;
import com.menumaestro.backend.media.CloudinaryClient;
import com.menumaestro.backend.menu.Async;
import com.menumaestro.backend.menu.ImageService;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;

/** Admin booking and table management, plus the live booking event stream (SSE). */
@RestController
@RequestMapping("/api/admin")
public class AdminReservationController {
  private static final Schema ID = Z.coerceNumber().integer().positive();
  private static final MediaType EVENT_STREAM = MediaType.parseMediaType("text/event-stream; charset=utf-8");

  private final ReservationService service;
  private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> {
    Thread thread = new Thread(runnable, "sse-heartbeat");
    thread.setDaemon(true);
    return thread;
  });

  private final BookingBackupJob backup;
  private final ImageService images;
  private final CloudinaryClient cloudinary;
  private final RateLimiter limiter;

  public AdminReservationController(ReservationService service, BookingBackupJob backup, ImageService images, CloudinaryClient cloudinary, RateLimiter limiter) {
    this.service = service;
    this.backup = backup;
    this.images = images;
    this.cloudinary = cloudinary;
    this.limiter = limiter;
  }

  private static long id(String value) { return ID.parse(Json.node(value)).asLong(); }

  @GetMapping("/bookings/events")
  public ResponseEntity<ResponseBodyEmitter> events() {
    ResponseBodyEmitter emitter = new ResponseBodyEmitter(0L);
    Object lock = new Object();
    Consumer<String> write = text -> {
      synchronized (lock) {
        try { emitter.send(text, EVENT_STREAM); }
        catch (IOException | IllegalStateException error) { emitter.completeWithError(error); }
      }
    };
    Consumer<BookingEvents.Changed> listener = event -> write.accept("event: booking.changed\ndata: "
      + Json.stringify(Json.obj("type", "booking.changed", "bookingId", event.bookingId(), "newCustomerBooking", event.newCustomerBooking())) + "\n\n");
    ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(() -> write.accept(": ping\n\n"), 25, 25, TimeUnit.SECONDS);
    Runnable close = () -> { heartbeat.cancel(false); service.events().off(listener); };
    emitter.onCompletion(close);
    emitter.onTimeout(close);
    emitter.onError(error -> close.run());
    write.accept("event: connected\ndata: ok\n\n");
    service.events().on(listener);
    return ResponseEntity.ok().contentType(EVENT_STREAM).header("Connection", "keep-alive").header("X-Accel-Buffering", "no").body(emitter);
  }

  @GetMapping("/bookings")
  public ResponseEntity<String> list(HttpServletRequest request) {
    JsonNode query = ReservationSchemas.SEARCH.pick("date", "area").parse(ReservationSchemas.fromQuery(request));
    return Responses.ok(service.list(Json.str(query, "date"), Json.str(query, "area")));
  }

  @GetMapping("/bookings/page")
  public ResponseEntity<String> page(HttpServletRequest request) {
    return Responses.ok(service.page(ReservationSchemas.SEARCH.parse(ReservationSchemas.fromQuery(request))));
  }

  /** "from" query value (yyyy-MM-dd), defaulting to today in the restaurant's time zone. */
  private String fromDate(String value) {
    if (value == null || value.isEmpty()) return ReservationRules.restaurantNow(service.timeZone(), service.now()).date();
    try {
      if (value.matches("\\d{4}-\\d{2}-\\d{2}")) return java.time.LocalDate.parse(value).toString();
    } catch (java.time.DateTimeException ignored) { /* Reported below. */ }
    throw new ApiException(400, "from must be a date (yyyy-MM-dd).");
  }

  /** CSV backup of pending and confirmed bookings from a date onwards (opens in Excel / Google Sheets). */
  @GetMapping("/bookings/export")
  public ResponseEntity<byte[]> export(HttpServletRequest request) {
    String from = fromDate(request.getParameter("from"));
    return ResponseEntity.ok()
      .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
      .header("Content-Disposition", "attachment; filename=\"" + BookingBackupJob.fileName(from) + "\"")
      .body(BookingBackup.csv(service.upcoming(from)));
  }

  /** Emails the backup now (the same email that is sent automatically every morning). */
  @PostMapping("/bookings/backup")
  public ResponseEntity<String> emailBackup(HttpServletRequest request) throws Exception {
    limiter.limit("booking-backup:" + AuthService.currentAdmin(request).userId(), 5);
    BookingBackupJob.Result result = backup.send(fromDate(request.getParameter("from")));
    return Responses.ok(Json.obj("from", result.from(), "count", result.count(), "sentTo", result.sentTo()));
  }

  @GetMapping("/bookings/pending-count")
  public ResponseEntity<String> pendingCount() { return Responses.ok(service.pendingCount()); }

  @PostMapping("/bookings")
  public ResponseEntity<String> create(HttpServletRequest request) {
    return Responses.created(service.create(ReservationSchemas.ADMIN_BOOKING.parse(RequestBodies.json(request))));
  }

  @PatchMapping("/bookings/{id}/approve")
  public ResponseEntity<String> approve(@PathVariable String id) { return Responses.ok(service.approve(id(id))); }

  @PatchMapping("/bookings/{id}/reject")
  public ResponseEntity<String> reject(@PathVariable String id, HttpServletRequest request) {
    long bookingId = id(id);
    return Responses.ok(service.reject(bookingId, ReservationSchemas.REJECT.parse(RequestBodies.json(request))));
  }

  @PatchMapping("/bookings/{id}/cancel")
  public ResponseEntity<String> cancel(@PathVariable String id) { return Responses.ok(service.cancel(id(id))); }

  @GetMapping("/bookings/{id}")
  public ResponseEntity<String> booking(@PathVariable String id) { return Responses.ok(service.booking(id(id))); }

  @PatchMapping("/bookings/{id}")
  public ResponseEntity<String> update(@PathVariable String id, HttpServletRequest request) {
    long bookingId = id(id);
    return Responses.ok(service.update(bookingId, ReservationSchemas.ADMIN_BOOKING.parse(RequestBodies.json(request))));
  }

  /** RTR "delete" cancels the booking so it remains in history. */
  @DeleteMapping("/bookings/{id}")
  public ResponseEntity<String> delete(@PathVariable String id) { return Responses.ok(service.cancel(id(id))); }

  @GetMapping("/tables")
  public ResponseEntity<String> tables(HttpServletRequest request) {
    String area = request.getParameter("area");
    return Responses.ok(service.tables(area == null || area.isEmpty() ? null : ReservationSchemas.TABLE.shape("area").parse(Json.node(area)).stringValue()));
  }

  @PostMapping("/tables")
  public ResponseEntity<String> createTable(HttpServletRequest request) {
    return Responses.created(service.createTable(ReservationSchemas.TABLE.parse(RequestBodies.json(request))));
  }

  @PatchMapping("/tables/{id}/position")
  public ResponseEntity<String> position(@PathVariable String id, HttpServletRequest request) {
    long tableId = id(id);
    return Responses.ok(service.updateTablePosition(tableId, ReservationSchemas.POSITION.parse(RequestBodies.json(request))));
  }

  @PatchMapping("/tables/{id}")
  public ResponseEntity<String> updateTable(@PathVariable String id, HttpServletRequest request) {
    long tableId = id(id);
    return Responses.ok(service.updateTable(tableId, ReservationSchemas.TABLE.parse(RequestBodies.json(request))));
  }

  /** Raw image body (like /api/admin/images): one photo per table, shown to guests as the view from that table. */
  @PostMapping("/tables/{id}/photo")
  public ResponseEntity<String> uploadTablePhoto(@PathVariable String id, HttpServletRequest request) {
    long tableId = id(id);
    limiter.limit("upload:" + AuthService.currentAdmin(request).userId(), 30);
    String url = images.uploadTablePhoto(RequestBodies.raw(request, 15 * 1024 * 1024));
    ReservationService.TablePhotoChange change;
    try {
      change = service.setTablePhoto(tableId, url);
    } catch (RuntimeException error) {
      cloudinary.destroy(url);
      throw error;
    }
    removeLater(change.previousUrl());
    return Responses.ok(change.table());
  }

  @DeleteMapping("/tables/{id}/photo")
  public ResponseEntity<String> deleteTablePhoto(@PathVariable String id) {
    ReservationService.TablePhotoChange change = service.setTablePhoto(id(id), null);
    removeLater(change.previousUrl());
    return Responses.ok(change.table());
  }

  private void removeLater(String url) {
    if (url != null && cloudinary.owns(url)) Async.EXECUTOR.submit(() -> cloudinary.destroy(url));
  }

  @DeleteMapping("/tables/{id}")
  public ResponseEntity<String> deleteTable(@PathVariable String id) {
    service.deleteTable(id(id));
    return Responses.ok(Json.obj("ok", true));
  }

  @PreDestroy
  void stop() { heartbeats.shutdownNow(); }
}
