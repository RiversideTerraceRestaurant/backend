package com.menumaestro.backend.config;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.menumaestro.backend.client.AiClient;
import com.menumaestro.backend.client.supabase.SupabaseClient;
import com.menumaestro.backend.repository.KnowledgeStore;
import com.menumaestro.backend.service.PushService;
import com.menumaestro.backend.service.ReservationService;
import com.menumaestro.backend.service.chat.ConversationCache;
import com.menumaestro.backend.service.chat.MenuRetriever;
import com.menumaestro.backend.service.mail.Mailer;
import com.menumaestro.backend.util.Async;
import com.menumaestro.backend.util.Json;

/** Wires the framework-independent services of the monolith. */
@Configuration
public class BeansConfig {
  private static final Logger log = LoggerFactory.getLogger(BeansConfig.class);

  @Bean
  AiClient aiClient(AppConfig config) { return new AiClient(config); }

  @Bean
  KnowledgeStore knowledgeStore(AppConfig config) {
    return new KnowledgeStore(config.knowledgePath() == null ? null : Path.of(config.knowledgePath()));
  }

  /** Service-role client for reservations and push subscriptions (server only), or null when not configured. */
  @Bean
  ServiceDatabase serviceDatabase(AppConfig config) {
    return new ServiceDatabase(config.supabaseServiceKey().isEmpty() ? null
      : new SupabaseClient(config.supabaseUrl(), config.supabaseServiceKey(), null, Duration.ofSeconds(30)));
  }

  public record ServiceDatabase(SupabaseClient client) {}

  @Bean
  Mailer mailer(AppConfig config) { return Mailer.create(config); }

  @Bean
  ReservationService reservationService(AppConfig config, ServiceDatabase database, Mailer mailer) {
    return new ReservationService(database.client(), mailer, config.timeZone(), Instant::now);
  }

  @Bean
  PushService pushService(AppConfig config, ServiceDatabase database, ReservationService reservations) {
    AppConfig.Push push = config.push();
    PushService service = new PushService(database.client(), push.publicKey(), push.privateKey(), push.subject(), null);
    // Alert admins' devices when a guest confirms a booking request.
    reservations.events().on(event -> {
      if (!event.newCustomerBooking() || !service.configured()) return;
      Async.EXECUTOR.submit(() -> {
        try { service.notifyNewBooking(reservations.booking(event.bookingId())); }
        catch (RuntimeException error) { log.warn(Json.stringify(Json.obj("event", "push.notify_failed", "error", error.getMessage()))); }
      });
    });
    return service;
  }

  @Bean
  MenuRetriever menuRetriever() { return new MenuRetriever(); }

  @Bean
  ConversationCache conversationCache() { return new ConversationCache(); }
}
