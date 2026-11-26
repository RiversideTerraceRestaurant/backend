package com.menumaestro.backend.service.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.menumaestro.backend.util.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class BookingAssistantTest {
  private static final String TODAY = "2026-11-18";

  @Test
  void recognisesBookingQuestionsInSeveralLanguages() {
    assertTrue(BookingAssistant.isBookingQuestion("Còn bàn trống tối nay không?"));
    assertTrue(BookingAssistant.isBookingQuestion("Can I book a table for 4 tomorrow?"));
    assertTrue(BookingAssistant.isBookingQuestion("予約できますか"));
    assertTrue(BookingAssistant.isBookingQuestion("столик на двоих"));
    assertFalse(BookingAssistant.isBookingQuestion("Món pasta nào ngon?"));
  }

  @Test
  void parsesDatesGuestsTimesAndSeating() {
    BookingAssistant.Request request = BookingAssistant.parseBookingRequest("bàn cho 4 người tối mai 7h", TODAY);
    assertEquals("2026-11-19", request.date());
    assertEquals(4, request.guests());
    assertEquals("19:00", request.time());
    assertNull(request.seating());

    BookingAssistant.Request english = BookingAssistant.parseBookingRequest("table for 2 on 25 dec at 7:30pm outside", TODAY);
    assertEquals("2026-12-25", english.date());
    assertEquals(2, english.guests());
    assertEquals("19:00", english.time());
    assertEquals("outside", english.seating());

    BookingAssistant.Request past = BookingAssistant.parseBookingRequest("ngày 3 phòng roma 3 người", TODAY);
    assertEquals("2026-12-03", past.date());
    assertEquals("ROMA", past.area());
    assertEquals("inside", past.seating());

    assertEquals("2026-11-20", BookingAssistant.parseBookingRequest("book friday", TODAY).date());
    assertEquals("2027-01-05", BookingAssistant.parseBookingRequest("5/1", TODAY).date());
  }

  @Test
  void dateHelpersFollowJavaScriptSemantics() {
    assertEquals("2026-03-02", BookingAssistant.addDays("2026-02-30", 0));
    assertEquals("2027-01-01", BookingAssistant.addDays("2026-12-31", 1));
    assertEquals("21:30", BookingAssistant.endTimeFor("19:30"));
    assertEquals("23:30", BookingAssistant.endTimeFor("22:00"));
    assertEquals("19:30", BookingAssistant.nextSlotAfter("19:00"));
    assertNull(BookingAssistant.nextSlotAfter("21:30"));
  }

  @Test
  void formatsDatesLikeIntlDateTimeFormat() {
    assertEquals("Wed 18/11", BookingAssistant.formatDate("en", TODAY));
    assertEquals("Thứ 4, 18/11", BookingAssistant.formatDate("vi", TODAY));
    assertEquals("CN, 4/1", BookingAssistant.formatDate("vi", "2026-01-04"));
    assertEquals("11. 18. (수)", BookingAssistant.formatDate("ko", TODAY));
    assertEquals("11/18(水)", BookingAssistant.formatDate("ja", TODAY));
    assertEquals("11/18周三", BookingAssistant.formatDate("cn", TODAY));
    assertEquals("ср, 18.11", BookingAssistant.formatDate("ru", TODAY));
    assertEquals("18.11, ср", BookingAssistant.formatDate("kz", TODAY));
    assertEquals("mié, 18/11", BookingAssistant.formatDate("es", TODAY));
    assertEquals("mer. 18/11", BookingAssistant.formatDate("fr", TODAY));
    assertEquals("mer 18/11", BookingAssistant.formatDate("it", TODAY));
  }

  @Test
  void detectsReplyLanguageFromScript() {
    assertEquals("vi", BookingAssistant.detectLanguage("đặt bàn", "en"));
    assertEquals("ko", BookingAssistant.detectLanguage("예약", "en"));
    assertEquals("ja", BookingAssistant.detectLanguage("よやく", "en"));
    assertEquals("cn", BookingAssistant.detectLanguage("订位", "en"));
    assertEquals("kz", BookingAssistant.detectLanguage("үстел", "en"));
    assertEquals("ru", BookingAssistant.detectLanguage("Столик", "en"));
    assertEquals("fr", BookingAssistant.detectLanguage("une table", "fr"));
  }

  @Test
  void smallPartiesGetABookingLinkWithoutAnAvailabilityCheck() {
    ObjectNode answer = BookingAssistant.answerBooking("đặt bàn cho 4 người tối mai 7h", Json.obj(), "vi", "2026-11-21");
    JsonNode booking = answer.get("booking");
    assertEquals("online", Json.str(booking, "kind"));
    assertEquals(4, booking.get("guests").asInt());
    assertEquals("2026-11-22", Json.str(booking.get("query"), "date"));
    assertEquals("19:00", Json.str(booking.get("query"), "time"));
    assertTrue(Json.str(answer, "reply").contains("đặt bàn online"));
    assertFalse(Json.str(answer, "reply").contains("WhatsApp"));
  }

  @Test
  void unknownPartySizeMentionsBothRoutes() {
    ObjectNode answer = BookingAssistant.answerBooking("", Json.obj(), "en", "2026-11-21");
    assertEquals("online", Json.str(answer.get("booking"), "kind"));
    assertFalse(answer.get("booking").has("guests"));
    assertTrue(Json.str(answer, "reply").contains("WhatsApp"));
  }

  @Test
  void largePartiesAreSentToWhatsAppOrEmail() {
    ObjectNode answer = BookingAssistant.answerBooking("table for 10 people tomorrow", Json.obj(), "en", "2026-11-21");
    JsonNode booking = answer.get("booking");
    assertEquals("contact", Json.str(booking, "kind"));
    assertEquals("https://wa.me/84911500440", Json.str(booking, "whatsapp"));
    assertEquals("litalianoriverside@gmail.com", Json.str(booking, "email"));
    assertFalse(booking.has("query"));
  }
}
