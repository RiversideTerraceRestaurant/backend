package com.menumaestro.backend.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.menumaestro.backend.dto.MenuSchemas;
import com.menumaestro.backend.exception.ValidationException;
import com.menumaestro.backend.util.Js;
import com.menumaestro.backend.util.Json;

class ValidationAndJsTest {
  private static String error(Runnable action) { return assertThrows(ValidationException.class, action::run).getMessage(); }

  @Test
  void chatSchemaAppliesDefaultsAndZodMessages() {
    var parsed = MenuSchemas.CHAT.parse(Json.obj("messages", List.of(Json.obj("role", "user", "content", "  hi  "))));
    assertEquals("{\"context\":\"menu\",\"action\":\"chat\",\"language\":\"en\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
      + "\"preferences\":{\"diet\":\"any\",\"maxPrice\":null,\"mild\":false,\"avoid\":[]}}", Json.stringify(parsed));
    assertEquals("messages: Array must contain at least 1 element(s); : The last message must be from the guest.",
      error(() -> MenuSchemas.CHAT.parse(Json.obj("messages", List.of()))));
    assertEquals("messages: Required", error(() -> MenuSchemas.CHAT.parse(Json.obj())));
    assertEquals(": Expected object, received array", error(() -> MenuSchemas.CHAT.parse(Json.arr())));
    assertEquals("language: Invalid enum value. Expected 'en' | 'vi' | 'ko' | 'ja' | 'cn' | 'ru' | 'kz' | 'es' | 'fr' | 'it', received 'de'",
      error(() -> MenuSchemas.CHAT.parse(Json.obj("language", "de", "messages", List.of(Json.obj("role", "user", "content", "x"))))));
  }

  @Test
  void strictObjectsRejectUnknownKeys() {
    assertEquals(": Unrecognized key(s) in object: 'extra'", error(() -> MenuSchemas.CATEGORY.parse(Json.obj("name", "a", "display_name", "A", "display_order", 1, "is_active", true, "extra", 1))));
    assertEquals("display_order: Expected integer, received float", error(() -> MenuSchemas.CATEGORY.partial().parse(Json.obj("display_order", 1.5))));
    assertEquals(": Invalid uuid", error(() -> MenuSchemas.ID.parse(Json.node("nope"))));
  }

  @Test
  void javascriptHelpersBehaveLikeTheRuntime() {
    assertEquals("{\"a\":150000,\"b\":12.5}", Json.stringify(Json.parse("{\"a\":150000.00,\"b\":12.5}")));
    assertEquals(16.0, Js.toNumber("0x10"));
    assertEquals(0.0, Js.toNumber("  "));
    assertEquals(Double.NaN, Js.toNumber("12px"));
    assertEquals(List.of("intro\n", "## A\nx\n", "## B"), Js.split("intro\n## A\nx\n## B", Js.re("(?=^##\\s)", "m")));
    assertEquals("a b", Js.replaceFirstLiteral("a.b.", ".", " ").substring(0, 3));
    assertEquals(true, Js.test("\\bсок\\b", "x сок") == false);
  }
}
