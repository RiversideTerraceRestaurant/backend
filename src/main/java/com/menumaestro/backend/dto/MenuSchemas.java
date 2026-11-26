package com.menumaestro.backend.dto;

import java.util.List;

import com.menumaestro.backend.util.Json;
import com.menumaestro.backend.validation.Schema;
import com.menumaestro.backend.validation.Z;

import tools.jackson.databind.JsonNode;

/** Request schemas for menu, categories and chat (validation.js). */
public final class MenuSchemas {
  private MenuSchemas() {}

  public static final List<String> BADGES = List.of("pork", "beef", "vegetarian", "vegan", "gluten_free", "spicy", "dairy");
  public static final List<String> TRANSLATION_KEYS = List.of("description_ko", "description_ja", "description_cn", "description_vi", "description_ru", "description_kz", "description_es", "description_fr", "description_it");
  public static final List<String> LANGUAGES = List.of("en", "vi", "ko", "ja", "cn", "ru", "kz", "es", "fr", "it");

  public static Schema id() { return Z.string().uuid(); }

  public static final Schema ID = id();

  public static final Schema ITEM;
  static {
    Z.ObjectSchema item = Z.object()
      .field("name", Z.string().trim().min(1).max(200))
      .field("description", Z.string().max(5000))
      .field("price", Z.number().finite().min(0).max(100000000))
      .field("vat", Z.number().finite().min(0).max(100))
      .field("category_id", id().nullable())
      .field("badges", Z.array(Z.enumOf(BADGES)).max(7))
      .field("image_url", Z.string().url().max(2000).nullable())
      .field("thumbnail_url", Z.string().url().max(2000).nullable());
    for (String key : TRANSLATION_KEYS) item = item.field(key, Z.string().max(5000).nullable().optional());
    ITEM = item.strict();
  }

  public static final Z.ObjectSchema CATEGORY = Z.object()
    .field("name", Z.string().trim().min(1).max(150))
    .field("display_name", Z.string().trim().min(1).max(150))
    .field("display_order", Z.number().integer().min(0).max(10000))
    .field("is_active", Z.bool())
    .strict();

  public static Schema language() { return Z.enumOf(LANGUAGES); }

  public static Z.ObjectSchema preferences() {
    return Z.object()
      .field("diet", Z.enumOf("any", "vegetarian", "vegan", "gluten_free", "dairy_free").withDefault(() -> Json.node("any")))
      .field("maxPrice", Z.number().finite().min(0).max(100000000).nullable().withDefault(() -> Json.F.nullNode()))
      .field("mild", Z.bool().withDefault(() -> Json.node(false)))
      .field("avoid", Z.array(Z.string().trim().min(1).max(80)).max(10).withDefault(Json::arr));
  }

  public static final Schema CHAT = Z.object()
    .field("conversationId", id().optional())
    .field("context", Z.enumOf("home", "menu").withDefault(() -> Json.node("menu")))
    .field("action", Z.enumOf("chat", "suggest", "popular", "vegetarian", "mild", "availability").withDefault(() -> Json.node("chat")))
    .field("language", language().withDefault(() -> Json.node("en")))
    .field("messages", Z.array(Z.object()
      .field("role", Z.enumOf("user", "assistant"))
      .field("content", Z.string().trim().min(1).max(1000))).min(1).max(12))
    .field("preferences", preferences().withDefault(Json::obj))
    // Structured table-availability request from the chat's quick form.
    .field("booking", Z.object()
      .field("date", Z.string().regex("^\\d{4}-\\d{2}-\\d{2}$", null).optional())
      .field("time", Z.string().regex("^([01]\\d|2[0-3]):[0-5]\\d$", null).optional())
      .field("guests", Z.number().integer().min(1).max(50).optional())
      .field("seating", Z.enumOf("inside", "outside").optional())
      .optional())
    .refine(value -> {
      JsonNode messages = Json.get(value, "messages");
      if (messages == null || messages.isEmpty()) return false;
      return "user".equals(Json.str(messages.get(messages.size() - 1), "role"));
    }, "The last message must be from the guest.");
}
