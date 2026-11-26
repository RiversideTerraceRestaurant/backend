package com.menumaestro.backend.controller;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.menumaestro.backend.client.AiClient;
import com.menumaestro.backend.config.AppConfig;
import com.menumaestro.backend.dto.MenuSchemas;
import com.menumaestro.backend.model.ChatState;
import com.menumaestro.backend.repository.KnowledgeStore;
import com.menumaestro.backend.security.ClientIp;
import com.menumaestro.backend.security.RateLimiter;
import com.menumaestro.backend.service.AiQuota;
import com.menumaestro.backend.service.CatalogService;
import com.menumaestro.backend.service.ReservationRules;
import com.menumaestro.backend.service.chat.BookingAssistant;
import com.menumaestro.backend.service.chat.ConversationCache;
import com.menumaestro.backend.service.chat.MenuRetriever;
import com.menumaestro.backend.service.chat.RecommendationService;
import com.menumaestro.backend.service.chat.RestaurantAssistant;
import com.menumaestro.backend.util.Json;
import com.menumaestro.backend.util.RequestBodies;
import com.menumaestro.backend.util.Responses;

import jakarta.servlet.http.HttpServletRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** POST /api/chat: RTR AI (menu recommendations, restaurant facts and how to book a table). */
@RestController
public class ChatController {
  private static final Logger log = LoggerFactory.getLogger(ChatController.class);

  private final AppConfig config;
  private final AiClient ai;
  private final AiQuota quota;
  private final CatalogService catalog;
  private final KnowledgeStore knowledge;
  private final MenuRetriever retriever;
  private final ConversationCache conversations;
  private final RateLimiter limiter;
  private final ClientIp clientIp;

  public ChatController(AppConfig config, AiClient ai, AiQuota quota, CatalogService catalog, KnowledgeStore knowledge,
                        MenuRetriever retriever, ConversationCache conversations, RateLimiter limiter, ClientIp clientIp) {
    this.config = config;
    this.ai = ai;
    this.quota = quota;
    this.catalog = catalog;
    this.knowledge = knowledge;
    this.retriever = retriever;
    this.conversations = conversations;
    this.limiter = limiter;
    this.clientIp = clientIp;
  }

  @PostMapping("/api/chat")
  public ResponseEntity<String> chat(HttpServletRequest request) {
    String ip = clientIp.of(request);
    limiter.limit("chat:" + ip, 10);
    limiter.limit("chat-hour:" + ip, 40, 3600000);
    ObjectNode input = (ObjectNode) MenuSchemas.CHAT.parse(RequestBodies.json(request));
    String requestId = UUID.randomUUID().toString();
    JsonNode messages = input.get("messages");
    String question = Json.str(messages.get(messages.size() - 1), "content");
    String action = Json.str(input, "action");
    String conversationId = Json.str(input, "conversationId");
    // Booking questions get a fixed answer (no LLM, no AI quota, no availability check): a link to the
    // booking page for up to six guests, the restaurant's WhatsApp/email for larger groups.
    if ("availability".equals(action) || ("chat".equals(action) && BookingAssistant.isBookingQuestion(question))) {
      String language = "availability".equals(action) ? Json.str(input, "language") : BookingAssistant.detectLanguage(question, Json.str(input, "language"));
      ObjectNode answer = BookingAssistant.answerBooking("chat".equals(action) ? question : "", input.has("booking") ? input.get("booking") : Json.obj(),
        language, ReservationRules.restaurantNow(config.timeZone(), Instant.now()).date());
      if (conversationId != null) answer.put("conversationId", conversationId);
      answer.put("requestId", requestId);
      return Responses.ok(answer);
    }
    if ("chat".equals(action)) quota.use();
    ChatState previous = conversations.read(conversationId, messages);
    ChatState[] state = {previous};
    RecommendationService recommendation = new RecommendationService(ai, retriever,
      popular -> popular ? catalog.current() : catalog.current().withoutPopularity(),
      (memory, questions, language) -> {
        ObjectNode scoped = Json.copy(input);
        scoped.put("language", language);
        return RestaurantAssistant.answer(scoped, language, knowledge.read(), ai, memory, questions == null ? null : Json.strArray(questions));
      },
      next -> state[0] = next,
      event -> {
        ObjectNode entry = Json.obj("requestId", requestId);
        entry.setAll(event);
        log.warn(Json.stringify(entry));
      });
    ObjectNode answer = recommendation.recommend(input, previous);
    // Missing, expired or mismatched context starts a fresh isolated conversation.
    String savedId = conversations.save(previous != null ? conversationId : null, messages, Json.str(answer, "reply", ""), state[0] != null ? state[0] : new ChatState());
    answer.put("conversationId", savedId);
    answer.put("requestId", requestId);
    return Responses.ok(answer);
  }
}
