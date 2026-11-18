package com.menumaestro.backend.chat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.menumaestro.backend.common.json.Js;
import com.menumaestro.backend.common.json.Json;
import com.menumaestro.backend.reservation.ReservationRules;
import com.menumaestro.backend.reservation.ReservationService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Chat assistant for table availability (booking-assistant.js). Parses natural-language
 * requests ("bàn cho 4 người tối mai 7h", "table for 2 tomorrow at 7pm") and answers from
 * live reservation data. Deterministic: it never invents availability.
 */
public final class BookingAssistant {
  private BookingAssistant() {}

  public static final String FIRST_SLOT = "11:00";
  public static final String LAST_SLOT = "21:30";
  public static final int DEFAULT_DURATION_MINUTES = 120;
  private static final String DEFAULT_TIME = "19:00";

  static int toMinutes(String time) { return Integer.parseInt(time.substring(0, 2)) * 60 + Integer.parseInt(time.substring(3, 5)); }

  static String toTime(int minutes) { return Js.padStart(String.valueOf(minutes / 60), 2, '0') + ":" + Js.padStart(String.valueOf(minutes % 60), 2, '0'); }

  public static String endTimeFor(String start) { return toTime(Math.min(toMinutes(start) + DEFAULT_DURATION_MINUTES, 23 * 60 + 30)); }

  /** Adds days to a yyyy-MM-dd date with JavaScript Date roll-over (e.g. 02-30 + 0 = 03-02). */
  public static String addDays(String date, int days) {
    int year = Integer.parseInt(date.substring(0, 4));
    int month = Integer.parseInt(date.substring(5, 7));
    int day = Integer.parseInt(date.substring(8, 10));
    return LocalDate.of(year, 1, 1).plusMonths(month - 1).plusDays(day - 1L + days).toString();
  }

  /** First bookable slot strictly after `time`, or null when the day is over. */
  static String nextSlotAfter(String time) {
    int minutes = Math.max(toMinutes(FIRST_SLOT), (int) Math.ceil((toMinutes(time) + 1) / 30.0) * 30);
    return minutes <= toMinutes(LAST_SLOT) ? toTime(minutes) : null;
  }

  static String normalize(String text) { return Js.lower(text).transform(Js::nfd).replaceAll("[\\u0300-\\u036f]", "").replace("đ", "d"); }

  private static final List<Pattern> BOOKING_WORDS = List.of(
    Js.re("\\b(dat|giu|con|kiem tra|check)\\s+(ban|cho)\\b"), Js.re("\\bban\\s+(trong|con)\\b"), Js.re("\\bcon\\s+(ban|cho)\\b"),
    Js.re("\\bbook(ing)?\\b"), Js.re("\\breserv"), Js.re("\\btable(s)?\\s+(for|available)\\b"), Js.re("\\b(free|available|open)\\s+(table|seat)"), Js.re("\\bavailability\\b"),
    Js.re("예약|빈\\s*자리|자리\\s*있"), Js.re("予約|空席|空いて"), Js.re("订位|订桌|预订|预定|空位|有位"), Js.re("столик|брон"), Js.re("үстел|бронда"),
    Js.re("\\bmesa\\b"), Js.re("\\btable pour\\b|\\bune table\\b"), Js.re("\\bprenot|\\btavolo\\b"),
    // "bàn"/"table" together with a party size, a day or a time.
    Js.re("\\b(ban|table)\\b[^.?!]*(\\d+\\s*(nguoi|khach|people|guests|pax)|toi nay|hom nay|ngay mai|tonight|today|tomorrow|\\d{1,2}\\s*(h|gio|pm|am)\\b)"));

  public static boolean isBookingQuestion(String text) {
    String normalized = normalize(text);
    return BOOKING_WORDS.stream().anyMatch(pattern -> pattern.matcher(normalized).find());
  }

  private static final Map<String, Integer> MONTHS = Map.ofEntries(Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mar", 3), Map.entry("apr", 4), Map.entry("may", 5),
    Map.entry("jun", 6), Map.entry("jul", 7), Map.entry("aug", 8), Map.entry("sep", 9), Map.entry("oct", 10), Map.entry("nov", 11), Map.entry("dec", 12));
  private static final List<Object[]> WEEKDAYS = List.of(
    new Object[] {"\\bsunday\\b|\\bchu nhat\\b|\\bcn\\b|일요일|日曜|星期日|周日|воскрес|domingo|dimanche|domenica|жексенбі", 0},
    new Object[] {"\\bmonday\\b|\\bthu (2|hai)\\b|월요일|月曜|星期一|周一|понедельн|lunes|lundi|lunedi|дүйсенбі", 1},
    new Object[] {"\\btuesday\\b|\\bthu (3|ba)\\b|화요일|火曜|星期二|周二|вторник|martes|mardi|martedi|сейсенбі", 2},
    new Object[] {"\\bwednesday\\b|\\bthu (4|tu)\\b|수요일|水曜|星期三|周三|сред|miercoles|mercredi|mercoledi|сәрсенбі", 3},
    new Object[] {"\\bthursday\\b|\\bthu (5|nam)\\b|목요일|木曜|星期四|周四|четверг|jueves|jeudi|giovedi|бейсенбі", 4},
    new Object[] {"\\bfriday\\b|\\bthu (6|sau)\\b|금요일|金曜|星期五|周五|пятниц|viernes|vendredi|venerdi|жұма", 5},
    new Object[] {"\\bsaturday\\b|\\bthu (7|bay)\\b|토요일|土曜|星期六|周六|суббот|sabado|samedi|sabato|сенбі", 6});
  private static final String EVENING = "\\b(pm|evening|tonight|toi|chieu|noche|soir|sera)\\b|저녁|오후|晚|夜|下午|午後|вечер";
  private static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
    Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("hai", 2),
    Map.entry("bon", 4), Map.entry("sau", 6), Map.entry("bay", 7), Map.entry("tam", 8), Map.entry("chin", 9), Map.entry("muoi", 10));

  /** What the guest asked for. Missing fields stay absent. */
  public record Request(String date, Integer guests, String time, String seating, String area) {
    Request with(String date, Integer guests, String time, String seating) {
      return new Request(date != null ? date : this.date, guests != null ? guests : this.guests, time != null ? time : this.time, seating != null ? seating : this.seating, area);
    }
  }

  private static String pad2(String value) { return Js.padStart(value, 2, '0'); }

  public static Request parseBookingRequest(String text, String today) {
    String rest = normalize(text);
    String date = null;
    String[] match;
    if ((match = Js.match(rest, "\\b(20\\d{2})-(\\d{1,2})-(\\d{1,2})\\b")) != null) {
      date = match[1] + "-" + pad2(match[2]) + "-" + pad2(match[3]);
      rest = Js.replaceFirstLiteral(rest, match[0], " ");
    } else if ((match = Js.match(rest, "\\b(\\d{1,2})[/.-](\\d{1,2})(?:[/.-](20\\d{2}|\\d{2}))?\\b")) != null
      && Integer.parseInt(match[2]) <= 12 && Integer.parseInt(match[1]) <= 31) {
      String year = match[3] != null ? (match[3].length() == 2 ? "20" + match[3] : match[3]) : today.substring(0, 4);
      String value = year + "-" + pad2(match[2]) + "-" + pad2(match[1]);
      if (match[3] == null && value.compareTo(today) < 0) value = (Integer.parseInt(year) + 1) + value.substring(4);
      date = value;
      rest = Js.replaceFirstLiteral(rest, match[0], " ");
    } else if ((match = Js.match(rest, "\\bngay (\\d{1,2})(?: thang (\\d{1,2}))?\\b")) != null) {
      int year = Integer.parseInt(today.substring(0, 4));
      int month = match[2] != null ? Integer.parseInt(match[2]) : Integer.parseInt(today.substring(5, 7));
      String day = pad2(match[1]);
      if ((year + "-" + pad2(String.valueOf(month)) + "-" + day).compareTo(today) < 0) {
        // "ngày 3" after the 3rd means next month; an explicit past month means next year.
        if (match[2] != null) year++;
        else if (++month > 12) { month = 1; year++; }
      }
      date = year + "-" + pad2(String.valueOf(month)) + "-" + day;
      rest = Js.replaceFirstLiteral(rest, match[0], " ");
    } else if ((match = Js.match(rest, "\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\b|\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b")) != null) {
      String day = match[1] != null ? match[1] : match[4];
      int month = MONTHS.get(match[2] != null ? match[2] : match[3]);
      String value = today.substring(0, 4) + "-" + pad2(String.valueOf(month)) + "-" + pad2(day);
      if (value.compareTo(today) < 0) value = (Integer.parseInt(today.substring(0, 4)) + 1) + value.substring(4);
      date = value;
      rest = Js.replaceFirstLiteral(rest, match[0], " ");
    } else if (Js.test("day after tomorrow|ngay kia|모레|明後日|后天|послезавтра|pasado manana|apres-demain|dopodomani|бүрсігүні", rest)) date = addDays(today, 2);
    else if (Js.test("tomorrow|ngay mai|\\btoi mai\\b|\\bsang mai\\b|\\bmai\\b|내일|明日|明天|завтра|manana|demain|domani|ертең", rest)) date = addDays(today, 1);
    else if (Js.test("today|tonight|hom nay|toi nay|chieu nay|오늘|今日|今天|今晚|сегодня|\\bhoy\\b|aujourd|stasera|\\boggi\\b|бүгін", rest)) date = today;
    else {
      for (Object[] weekday : WEEKDAYS) {
        if (Js.test((String) weekday[0], rest)) {
          int current = LocalDate.parse(today).getDayOfWeek().getValue() % 7;
          date = addDays(today, ((Integer) weekday[1] - current + 7) % 7);
          break;
        }
      }
    }

    // Guests before time so "4 nguoi luc 7h" does not read 4 as an hour.
    Integer guests = null;
    match = Js.match(rest, "\\b(\\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|hai|bon|sau|bay|tam|chin|muoi)\\s*(nguoi|khach|ng\\b|people|persons?|guests?|pax|adults?|personas|personnes|persone|человек|чел|адам)|(\\d{1,2})\\s*(명|人|位|名)|\\b(?:for|party of|table for|ban cho|cho)\\s+(\\d{1,2}|two|three|four|five|six|seven|eight|nine|ten)\\b(?!\\s*(:|h\\b|gio|am|pm))");
    if (match != null) {
      String raw = match[1] != null ? match[1] : match[3] != null ? match[3] : match[5];
      guests = NUMBER_WORDS.containsKey(raw) ? NUMBER_WORDS.get(raw) : Integer.valueOf(Integer.parseInt(raw));
      rest = Js.replaceFirstLiteral(rest, match[0], " ");
    }

    Integer hour = null;
    int minute = 0;
    if ((match = Js.match(rest, "\\b(\\d{1,2})\\s*[:h]\\s*(\\d{2})\\b")) != null) {
      hour = Integer.parseInt(match[1]);
      minute = Integer.parseInt(match[2]);
    } else if ((match = Js.match(rest, "\\b(\\d{1,2})\\s*(am|pm|h\\b|gio|giờ|o'?clock|uhr)|(\\d{1,2})\\s*(시|時|点)|(?:^|\\s)(?:at|luc|vao luc|a las|alle|a|в)\\s+(\\d{1,2})(?!\\d)")) != null) {
      hour = Integer.parseInt(match[1] != null ? match[1] : match[3] != null ? match[3] : match[5]);
      if ("am".equals(match[2]) && hour == 12) hour = 0;
      if (("pm".equals(match[2]) || Js.test(EVENING, rest)) && hour < 12) hour += 12;
      if (Js.test("rưỡi|ruoi|half|半", rest)) minute = 30;
    }
    String time = null;
    if (hour != null && hour <= 23 && minute < 60) {
      // A bare "7" at a restaurant means the evening.
      if (hour >= 1 && hour <= 9 && !Js.test("\\bam\\b|sang|morning|오전|午前|上午|утр", rest)) hour += 12;
      time = toTime(hour * 60 + (int) Math.round(minute / 30.0) * 30);
    }

    final String remaining = rest;
    List<String> areas = ReservationRules.AREAS.stream().filter(area -> remaining.contains(Js.lower(area)) && !"TERRACE".equals(area)).toList();
    String seating = null;
    if (Js.test("outside|outdoor|terrace|ngoai troi|ngoai san|ben ngoai|view song|river|야외|テラス|屋外|户外|室外|露台|улиц|террас|terraza|exterieur|terrasse|fuori|esterno|сырт", rest)) seating = "outside";
    else if (Js.test("inside|indoor|trong nha|ben trong|phong lanh|may lanh|실내|室内|в зале|внутри|interior|interieur|dentro|interno|ішкі", rest) || !areas.isEmpty()) seating = "inside";
    return new Request(date, guests, time, seating, areas.isEmpty() ? null : areas.get(0));
  }

  // ---------- localized copy ----------
  private record Copy(BiFunction<String, Integer, String> available, Function<Object[], String> line, BiFunction<String, Integer, String> none,
                      Function<String, String> next, Function<Integer, String> large, Function<List<String>, String> assumed,
                      Function<Integer, String> guests, Function<String, String> time, String today, String moved, String cta) {}

  private static final Map<String, Copy> COPY = Map.of(
    "en", new Copy((when, g) -> "Good news — there are tables for " + g + " on " + when + ":",
      l -> "• " + l[0] + ": " + l[1] + " free table" + (((Integer) l[1]) == 1 ? "" : "s") + " — suggested " + l[2],
      (when, g) -> "Sorry, online booking has no suitable tables for " + g + " on " + when + ".",
      when -> "The next day with free tables is " + when + ".",
      max -> "Online booking takes up to " + max + " guests. For larger groups please call (+84) 911 500 440 or email litalianoriverside@gmail.com.",
      parts -> "(I assumed " + String.join(", ", parts) + " — tell me if you'd like something else.)",
      g -> g + " guests", t -> t, "today", "That time has passed, so I checked the next available slot.",
      "Tap a suggestion to reserve. Your table is held once you confirm the code sent to your email; the restaurant then approves it."),
    "vi", new Copy((when, g) -> "Tin vui — vẫn còn bàn cho " + g + " khách vào " + when + ":",
      l -> "• " + l[0] + ": còn " + l[1] + " bàn — gợi ý " + l[2],
      (when, g) -> "Rất tiếc, " + when + " không còn bàn phù hợp cho " + g + " khách để đặt online.",
      when -> "Ngày gần nhất còn bàn là " + when + ".",
      max -> "Đặt bàn online nhận tối đa " + max + " khách. Nhóm đông hơn vui lòng gọi (+84) 911 500 440 hoặc email litalianoriverside@gmail.com.",
      parts -> "(Mình tạm hiểu là " + String.join(", ", parts) + " — bạn muốn khác thì cứ nhắn nhé.)",
      g -> g + " khách", t -> "lúc " + t, "hôm nay", "Giờ bạn chọn đã qua nên mình kiểm tra khung giờ gần nhất.",
      "Chọn một gợi ý để đặt. Bàn được giữ sau khi bạn xác nhận mã gửi qua email, sau đó nhà hàng sẽ duyệt."),
    "ko", new Copy((when, g) -> when + "에 " + g + "명 자리가 있습니다:",
      l -> "• " + l[0] + ": 빈 테이블 " + l[1] + "개 — 추천 " + l[2],
      (when, g) -> "죄송합니다. " + when + "에는 " + g + "명이 온라인으로 예약할 수 있는 테이블이 없습니다.",
      when -> "가장 가까운 예약 가능일은 " + when + "입니다.",
      max -> "온라인 예약은 최대 " + max + "명까지 가능합니다. 단체는 (+84) 911 500 440으로 연락해 주세요.",
      parts -> "(" + String.join(", ", parts) + "(으)로 확인했습니다.)",
      g -> g + "명", t -> t, "오늘", "선택한 시간이 지나 가장 가까운 시간으로 확인했습니다.",
      "추천을 눌러 예약하세요. 이메일 인증 후 테이블이 확보되고 레스토랑이 승인합니다."),
    "ja", new Copy((when, g) -> when + "、" + g + "名様のお席がございます：",
      l -> "• " + l[0] + "：空き" + l[1] + "卓 — おすすめ " + l[2],
      (when, g) -> "申し訳ありません。" + when + "は" + g + "名様のオンライン予約可能なお席がありません。",
      when -> "最も近い空きのある日は" + when + "です。",
      max -> "オンライン予約は" + max + "名様までです。大人数の場合は (+84) 911 500 440 へお電話ください。",
      parts -> "（" + String.join("、", parts) + "で確認しました）",
      g -> g + "名", t -> t, "本日", "ご指定の時間を過ぎているため、直近の時間で確認しました。",
      "候補をタップしてご予約ください。メール認証後にお席を確保し、店舗が承認します。"),
    "cn", new Copy((when, g) -> "好消息，" + when + "有适合" + g + "位的座位：",
      l -> "• " + l[0] + "：空桌" + l[1] + "张 — 推荐 " + l[2],
      (when, g) -> "抱歉，" + when + "没有适合" + g + "位的在线可订座位。",
      when -> "最近有空位的日期是" + when + "。",
      max -> "在线订位最多" + max + "位。更多人数请致电 (+84) 911 500 440。",
      parts -> "（已按" + String.join("、", parts) + "查询）",
      g -> g + "位", t -> t, "今天", "所选时间已过，已为您查询最近的时段。",
      "点击推荐即可预订。邮箱验证后为您保留座位，餐厅确认后生效。"),
    "ru", new Copy((when, g) -> "Есть свободные столики на " + g + " — " + when + ":",
      l -> "• " + l[0] + ": свободно " + l[1] + " — рекомендуем " + l[2],
      (when, g) -> "К сожалению, на " + when + " нет подходящих столиков на " + g + " для онлайн-брони.",
      when -> "Ближайшая дата со свободными столиками: " + when + ".",
      max -> "Онлайн-бронь — до " + max + " гостей. Для больших компаний звоните (+84) 911 500 440.",
      parts -> "(Проверил для: " + String.join(", ", parts) + ".)",
      g -> g + " гост.", t -> t, "сегодня", "Это время уже прошло, я проверил ближайшее.",
      "Нажмите на вариант, чтобы забронировать. Столик закрепляется после подтверждения кода из письма."),
    "kz", new Copy((when, g) -> when + " уақытына " + g + " қонаққа бос үстелдер бар:",
      l -> "• " + l[0] + ": бос " + l[1] + " үстел — ұсыныс " + l[2],
      (when, g) -> "Кешіріңіз, " + when + " уақытына " + g + " қонаққа онлайн бос үстел жоқ.",
      when -> "Бос үстелі бар ең жақын күн: " + when + ".",
      max -> "Онлайн брондау " + max + " қонаққа дейін. Үлкен топтар үшін (+84) 911 500 440 нөміріне хабарласыңыз.",
      parts -> "(" + String.join(", ", parts) + " бойынша тексердім.)",
      g -> g + " қонақ", t -> t, "бүгін", "Таңдалған уақыт өтіп кетті, ең жақын уақытты тексердім.",
      "Брондау үшін ұсынысты басыңыз. Электрондық поштадағы кодты растағаннан кейін үстел сақталады."),
    "es", new Copy((when, g) -> "Hay mesas para " + g + " el " + when + ":",
      l -> "• " + l[0] + ": " + l[1] + " libres — sugerencia " + l[2],
      (when, g) -> "Lo sentimos, el " + when + " no hay mesas disponibles online para " + g + ".",
      when -> "El próximo día con mesas libres es el " + when + ".",
      max -> "La reserva online admite hasta " + max + " personas. Para grupos mayores llame al (+84) 911 500 440.",
      parts -> "(He supuesto " + String.join(", ", parts) + ".)",
      g -> g + " personas", t -> t, "hoy", "Esa hora ya pasó; he consultado el siguiente horario.",
      "Pulse una sugerencia para reservar. La mesa queda retenida al confirmar el código enviado por email."),
    "fr", new Copy((when, g) -> "Il reste des tables pour " + g + " le " + when + " :",
      l -> "• " + l[0] + " : " + l[1] + " libres — suggestion " + l[2],
      (when, g) -> "Désolé, aucune table disponible en ligne pour " + g + " le " + when + ".",
      when -> "Le prochain jour avec des tables libres est le " + when + ".",
      max -> "La réservation en ligne accepte jusqu'à " + max + " personnes. Pour les groupes, appelez le (+84) 911 500 440.",
      parts -> "(J'ai supposé " + String.join(", ", parts) + ".)",
      g -> g + " personnes", t -> t, "aujourd'hui", "Cet horaire est passé ; j'ai vérifié le créneau suivant.",
      "Touchez une suggestion pour réserver. La table est bloquée après confirmation du code reçu par e-mail."),
    "it", new Copy((when, g) -> "Ci sono tavoli per " + g + " il " + when + ":",
      l -> "• " + l[0] + ": " + l[1] + " liberi — consigliato " + l[2],
      (when, g) -> "Spiacenti, il " + when + " non ci sono tavoli prenotabili online per " + g + ".",
      when -> "Il primo giorno con tavoli liberi è il " + when + ".",
      max -> "La prenotazione online accetta fino a " + max + " ospiti. Per gruppi chiamate il (+84) 911 500 440.",
      parts -> "(Ho ipotizzato " + String.join(", ", parts) + ".)",
      g -> g + " persone", t -> t, "oggi", "L'orario è già passato; ho controllato il primo disponibile.",
      "Tocca un suggerimento per prenotare. Il tavolo è bloccato dopo la conferma del codice ricevuto via email."));

  /** Picks the reply language from the text itself, falling back to the UI language. */
  public static String detectLanguage(String text, String fallback) {
    if (Js.re("[ăâđêôơưạảấầẩẫậắằẳẵặẹẻẽếềểễệỉịọỏốồổỗộớờởỡợụủứừửữựỳỵỷỹ]", "i").matcher(text).find()) return "vi";
    if (Js.test("[가-힯]", text)) return "ko";
    if (Js.test("[぀-ヿ]", text)) return "ja";
    if (Js.test("[一-鿿]", text)) return "cn";
    if (Js.re("[әғқңөұүһі]", "i").matcher(text).find()) return "kz";
    if (Js.re("[а-яё]", "i").matcher(text).find()) return "ru";
    return fallback;
  }

  private static final String[] EN_DAYS = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
  private static final String[] VI_DAYS = {"CN", "Thứ 2", "Thứ 3", "Thứ 4", "Thứ 5", "Thứ 6", "Thứ 7"};
  private static final String[] KO_DAYS = {"일", "월", "화", "수", "목", "금", "토"};
  private static final String[] JA_DAYS = {"日", "月", "火", "水", "木", "金", "土"};
  private static final String[] CN_DAYS = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
  private static final String[] RU_DAYS = {"вс", "пн", "вт", "ср", "чт", "пт", "сб"};
  private static final String[] KZ_DAYS = {"жс", "дс", "сс", "ср", "бс", "жм", "сб"};
  private static final String[] ES_DAYS = {"dom", "lun", "mar", "mié", "jue", "vie", "sáb"};
  private static final String[] FR_DAYS = {"dim.", "lun.", "mar.", "mer.", "jeu.", "ven.", "sam."};
  private static final String[] IT_DAYS = {"dom", "lun", "mar", "mer", "gio", "ven", "sab"};

  /** Intl.DateTimeFormat(locale, {weekday: 'short', day: 'numeric', month: 'numeric'}) for each language. */
  static String formatDate(String lang, String day) {
    LocalDate date = LocalDate.parse(addDays(day, 0));
    int weekday = date.getDayOfWeek().getValue() % 7;
    int d = date.getDayOfMonth();
    int m = date.getMonthValue();
    String dd = pad2(String.valueOf(d));
    String mm = pad2(String.valueOf(m));
    return switch (lang) {
      case "vi" -> VI_DAYS[weekday] + ", " + d + "/" + m;
      case "ko" -> m + ". " + d + ". (" + KO_DAYS[weekday] + ")";
      case "ja" -> m + "/" + d + "(" + JA_DAYS[weekday] + ")";
      case "cn" -> m + "/" + d + CN_DAYS[weekday];
      case "ru" -> RU_DAYS[weekday] + ", " + dd + "." + mm;
      case "kz" -> dd + "." + mm + ", " + KZ_DAYS[weekday];
      case "es" -> ES_DAYS[weekday] + ", " + d + "/" + m;
      case "fr" -> FR_DAYS[weekday] + " " + dd + "/" + mm;
      case "it" -> IT_DAYS[weekday] + " " + dd + "/" + mm;
      default -> EN_DAYS[weekday] + " " + dd + "/" + mm;
    };
  }

  /**
   * Answers an availability question. Guest-facing rule: a table with any booking that
   * day is shown as taken, the same as the booking page.
   */
  public static ObjectNode answerAvailability(String text, JsonNode request, String language, ReservationService reservations, String timeZone) {
    String lang = COPY.containsKey(language) ? language : "en";
    Copy copy = COPY.get(lang);
    ReservationRules.Now now = ReservationRules.restaurantNow(timeZone, reservations.now());
    Request parsed = parseBookingRequest(text == null ? "" : text, now.date());
    // Structured quick-form values override what was parsed from text (empty values are ignored).
    JsonNode requested = Json.get(request, "guests");
    Request asked = parsed.with(nonEmpty(request, "date"), requested == null || !requested.isNumber() ? null : requested.asInt(),
      nonEmpty(request, "time"), nonEmpty(request, "seating"));
    List<String> assumed = new ArrayList<>();
    Integer requestedGuests = asked.guests();
    int guests;
    if (requestedGuests == null || requestedGuests == 0) { guests = 2; assumed.add(copy.guests().apply(2)); }
    else guests = requestedGuests;
    if (guests > ReservationRules.MAX_ONLINE_GUESTS) {
      return Json.obj("reply", copy.large().apply(ReservationRules.MAX_ONLINE_GUESTS), "items", Json.arr(), "mode", "booking", "language", lang, "booking", null);
    }
    String date = asked.date() != null && asked.date().compareTo(now.date()) >= 0 ? asked.date() : now.date();
    String time = asked.time() != null && !asked.time().isEmpty() ? asked.time() : DEFAULT_TIME;
    if (toMinutes(time) < toMinutes(FIRST_SLOT)) time = FIRST_SLOT;
    if (toMinutes(time) > toMinutes(LAST_SLOT)) time = LAST_SLOT;
    boolean moved = false;
    if (date.equals(now.date()) && time.compareTo(now.time()) <= 0) {
      String next = nextSlotAfter(now.time());
      if (next != null) time = next;
      else {
        date = addDays(date, 1);
        time = asked.time() != null && !asked.time().isEmpty() && toMinutes(asked.time()) <= toMinutes(LAST_SLOT) ? asked.time() : DEFAULT_TIME;
      }
      moved = true;
    }
    if (!moved) {
      if (asked.time() == null || asked.time().isEmpty()) assumed.add(0, copy.time().apply(time));
      if (asked.date() == null || asked.date().isEmpty()) assumed.add(0, copy.today());
    }

    final String slot = time;
    final int party = guests;
    Function<String, List<ObjectNode>> optionsOn = day -> {
      List<String> areas = asked.seating() != null ? ReservationRules.suggestionAreas(asked.seating(), party)
        : concat(List.of("TERRACE"), ReservationRules.suggestionAreas("inside", party));
      if (asked.area() != null) areas = List.of(asked.area());
      if (party <= 2) areas = areas.stream().filter(area -> !"VERONA".equals(area)).toList();
      LinkedHashSet<String> unique = new LinkedHashSet<>(areas);
      if (asked.seating() == null && asked.area() == null) unique.add("SORRENTO");
      List<ObjectNode> options = new ArrayList<>();
      for (String area : unique) {
        List<ObjectNode> free = reservations.availability(area, day, slot, endTimeFor(slot)).stream().filter(table -> "AVAILABLE".equals(Json.str(table, "status"))).toList();
        List<ObjectNode> tables = ReservationRules.bestTableGroup(area, free, party);
        if (!tables.isEmpty()) {
          ArrayNode picked = Json.arr();
          for (ObjectNode table : tables) picked.add(Json.obj("id", table.get("id"), "tableNumber", table.get("tableNumber")));
          options.add(Json.obj("area", area, "label", ReservationRules.AREA_LABELS.get(area), "freeCount", free.size(), "tables", picked));
        }
      }
      return options;
    };

    String when = formatDate(lang, date) + " " + time;
    List<ObjectNode> options = optionsOn.apply(date);
    List<String> lines = new ArrayList<>();
    String nextDate = null;
    if (moved) lines.add(copy.moved());
    if (!options.isEmpty()) {
      lines.add(copy.available().apply(when, guests));
      for (ObjectNode option : options) {
        List<String> numbers = Json.list(option.get("tables")).stream().map(table -> Json.jsString(table.get("tableNumber"))).toList();
        lines.add(copy.line().apply(new Object[] {Json.str(option, "label"), option.get("freeCount").asInt(), String.join("+", numbers)}));
      }
      lines.add("");
      lines.add(copy.cta());
    } else {
      lines.add(copy.none().apply(when, guests));
      for (int offset = 1; offset <= 7 && nextDate == null; offset++) if (!optionsOn.apply(addDays(date, offset)).isEmpty()) nextDate = addDays(date, offset);
      if (nextDate != null) lines.add(copy.next().apply(formatDate(lang, nextDate)));
    }
    if (!assumed.isEmpty()) lines.add(copy.assumed().apply(assumed));
    return Json.obj("reply", String.join("\n", lines), "items", Json.arr(), "mode", "booking", "language", lang,
      "booking", Json.obj("date", date, "time", time, "endTime", endTimeFor(time), "guests", guests, "seating", asked.seating(), "options", options, "nextDate", nextDate));
  }

  private static String nonEmpty(JsonNode request, String key) {
    JsonNode value = Json.get(request, key);
    if (value == null || value.isNull()) return null;
    String text = Json.jsString(value);
    return text.isEmpty() ? null : text;
  }

  private static List<String> concat(List<String> first, List<String> second) {
    List<String> result = new ArrayList<>(first);
    result.addAll(second);
    return result;
  }
}
