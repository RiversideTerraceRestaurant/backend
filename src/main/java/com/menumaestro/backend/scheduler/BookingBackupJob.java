package com.menumaestro.backend.scheduler;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.menumaestro.backend.config.AppConfig;
import com.menumaestro.backend.exception.ApiException;
import com.menumaestro.backend.service.BookingBackup;
import com.menumaestro.backend.service.ReservationService;
import com.menumaestro.backend.service.mail.Mailer;
import com.menumaestro.backend.util.Json;

import jakarta.annotation.PreDestroy;

import tools.jackson.databind.node.ObjectNode;

/**
 * Emails staff a backup of upcoming reservations every morning (BACKUP_HOUR, restaurant time), so
 * the day's seating plan is in their inbox even if this server or the database is unreachable.
 */
@Component
public class BookingBackupJob {
  private static final Logger log = LoggerFactory.getLogger(BookingBackupJob.class);
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  private final ReservationService reservations;
  private final Mailer mailer;
  private final AppConfig.Backup settings;
  private final ZoneId zone;
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("booking-backup").factory());

  public BookingBackupJob(ReservationService reservations, Mailer mailer, AppConfig config) {
    this.reservations = reservations;
    this.mailer = mailer;
    this.settings = config.backup();
    this.zone = ZoneId.of(config.timeZone());
  }

  public record Result(String from, int count, List<String> sentTo) {}

  @EventListener(ApplicationReadyEvent.class)
  void start() {
    if (settings.emails().isEmpty() || !reservations.configured() || !mailer.configured()) return;
    long delay = Duration.between(ZonedDateTime.now(zone), nextRun(ZonedDateTime.now(zone), settings.hour())).toMinutes();
    scheduler.scheduleAtFixedRate(() -> {
      try {
        Result result = send(null);
        log.info(Json.stringify(Json.obj("event", "booking.backup_sent", "from", result.from(), "count", result.count())));
      } catch (Exception error) {
        log.warn(Json.stringify(Json.obj("event", "booking.backup_failed", "error", String.valueOf(error.getMessage()))));
      }
    }, delay, TimeUnit.DAYS.toMinutes(1), TimeUnit.MINUTES);
  }

  /** Next occurrence of hour:00 strictly after now. */
  static ZonedDateTime nextRun(ZonedDateTime now, int hour) {
    ZonedDateTime run = now.withHour(hour).withMinute(0).withSecond(0).withNano(0);
    return run.isAfter(now) ? run : run.plusDays(1);
  }

  /** Emails the backup of bookings from `from` (default today) to the configured staff addresses. */
  public Result send(String from) throws Exception {
    if (settings.emails().isEmpty()) throw new ApiException(503, "Backup email is turned off (BACKUP_EMAIL=off).");
    if (!mailer.configured()) throw new ApiException(503, "Email is not configured on the server.");
    ZonedDateTime now = ZonedDateTime.now(zone);
    String day = from != null ? from : now.toLocalDate().toString();
    List<ObjectNode> bookings = reservations.upcoming(day);
    mailer.sendBackup(settings.emails(), "Riverside Terrace reservation backup – from " + day + " (" + bookings.size() + ")",
      BookingBackup.html(day, bookings, STAMP.format(now)), fileName(day), BookingBackup.csv(bookings));
    return new Result(day, bookings.size(), settings.emails());
  }

  public static String fileName(String from) { return "rtr-reservations-from-" + from + ".csv"; }

  @PreDestroy
  void stop() { scheduler.shutdownNow(); }
}
