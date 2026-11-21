package com.menumaestro.backend.chat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.menumaestro.backend.common.json.Js;
import com.menumaestro.backend.common.json.Json;
import com.menumaestro.backend.reservation.ReservationRules;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Chat assistant for booking questions. Parses natural-language requests ("bàn cho 4 người tối
 * mai 7h", "table for 2 tomorrow at 7pm") only to pre-fill the booking link and to tell parties
 * over the online limit to contact the restaurant. It never checks or invents availability.
 */
public final class BookingAssistant {
  private BookingAssistant() {}

  public static final String FIRST_SLOT = "11:00";
  public static final String LAST_SLOT = "21:30";
  public static final int DEFAULT_DURATION_MINUTES = 120;

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
  public static final String PHONE_DISPLAY = "(+84) 911 500 440";
  public static final String WHATSAPP_URL = "https://wa.me/84911500440";
  public static final String PHONE_URL = "tel:+84911500440";
  public static final String EMAIL = "litalianoriverside@gmail.com";

  /** online: how to book on the site; groups: the rule for larger parties; large: reply to a party over the limit. */
  private record Copy(Function<Integer, String> online, String groups, Function<Integer, String> large) {}

  private static final String CONTACT = "WhatsApp " + PHONE_DISPLAY + " / " + EMAIL;
  private static final Map<String, Copy> COPY = Map.of(
    "en", new Copy(max -> "You can book a table online right here (up to " + max + " guests): pick your tables, enter your details and confirm by email. Tap the button below to start.",
      "For more than 6 guests, please contact us via " + CONTACT + ".",
      max -> "Online booking is for up to " + max + " guests. For your group, please contact the restaurant via " + CONTACT + " and we will arrange the seating for you."),
    "vi", new Copy(max -> "Bạn đặt bàn online ngay tại đây nhé (tối đa " + max + " khách): chọn bàn, nhập thông tin và xác nhận qua email. Nhấn nút bên dưới để bắt đầu.",
      "Nhóm trên 6 khách vui lòng liên hệ nhà hàng qua " + CONTACT + ".",
      max -> "Đặt bàn online chỉ áp dụng cho nhóm tối đa " + max + " khách. Với nhóm của bạn, vui lòng liên hệ nhà hàng qua " + CONTACT + " để được sắp xếp chỗ ngồi."),
    "ko", new Copy(max -> "여기에서 바로 온라인 예약하실 수 있어요(최대 " + max + "명): 테이블 선택, 정보 입력 후 이메일로 확인합니다. 아래 버튼을 눌러 시작하세요.",
      "7명 이상은 " + CONTACT + " 로 문의해 주세요.",
      max -> "온라인 예약은 최대 " + max + "명까지입니다. 단체는 " + CONTACT + " 로 연락 주시면 자리를 준비해 드립니다."),
    "ja", new Copy(max -> "こちらからオンライン予約ができます（最大" + max + "名）。席を選び、情報を入力してメールで確認します。下のボタンから始めてください。",
      "7名以上は " + CONTACT + " までご連絡ください。",
      max -> "オンライン予約は最大" + max + "名までです。団体のお客様は " + CONTACT + " までご連絡いただければお席をご用意します。"),
    "cn", new Copy(max -> "您可以在这里在线订位（最多" + max + "人）：选择餐桌、填写信息并通过邮件确认。点击下方按钮开始。",
      "超过6人请通过 " + CONTACT + " 联系我们。",
      max -> "在线订位最多支持" + max + "人。团体请通过 " + CONTACT + " 联系餐厅，我们会为您安排座位。"),
    "ru", new Copy(max -> "Забронировать столик можно онлайн прямо здесь (до " + max + " гостей): выберите столик, введите данные и подтвердите по email. Нажмите кнопку ниже.",
      "Для групп больше 6 человек свяжитесь с нами: " + CONTACT + ".",
      max -> "Онлайн-бронирование доступно до " + max + " гостей. Для вашей группы свяжитесь с рестораном: " + CONTACT + " — мы подготовим места."),
    "kz", new Copy(max -> "Үстелді осы жерден онлайн брондай аласыз (" + max + " қонаққа дейін): үстелді таңдап, деректерді енгізіп, email арқылы растаңыз. Төмендегі батырманы басыңыз.",
      "6 адамнан көп топтар үшін бізге хабарласыңыз: " + CONTACT + ".",
      max -> "Онлайн брондау " + max + " қонаққа дейін. Тобыңыз үшін мейрамханаға хабарласыңыз: " + CONTACT + "."),
    "es", new Copy(max -> "Puedes reservar online aquí mismo (hasta " + max + " personas): elige tu mesa, introduce tus datos y confirma por email. Pulsa el botón de abajo para empezar.",
      "Para más de 6 personas, contáctanos por " + CONTACT + ".",
      max -> "La reserva online es para un máximo de " + max + " personas. Para tu grupo, contacta con el restaurante por " + CONTACT + " y te prepararemos las mesas."),
    "fr", new Copy(max -> "Vous pouvez réserver en ligne ici même (jusqu'à " + max + " personnes) : choisissez votre table, saisissez vos coordonnées et confirmez par email. Touchez le bouton ci-dessous.",
      "Au-delà de 6 personnes, contactez-nous via " + CONTACT + ".",
      max -> "La réservation en ligne est limitée à " + max + " personnes. Pour votre groupe, contactez le restaurant via " + CONTACT + " et nous organiserons votre table."),
    "it", new Copy(max -> "Puoi prenotare online proprio qui (fino a " + max + " persone): scegli il tavolo, inserisci i tuoi dati e conferma via email. Tocca il pulsante qui sotto.",
      "Per più di 6 persone contattaci via " + CONTACT + ".",
      max -> "La prenotazione online è per massimo " + max + " persone. Per il tuo gruppo contatta il ristorante via " + CONTACT + " e prepareremo i tavoli."));

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
   * Answers a booking question without checking availability: parties of up to six get a link to
   * the booking page (pre-filled with the date, time and party size the guest mentioned), larger
   * parties get the restaurant's WhatsApp and email.
   */
  public static ObjectNode answerBooking(String text, JsonNode request, String language, String today) {
    String lang = COPY.containsKey(language) ? language : "en";
    Copy copy = COPY.get(lang);
    Request parsed = parseBookingRequest(text == null ? "" : text, today);
    JsonNode requested = Json.get(request, "guests");
    Request asked = parsed.with(nonEmpty(request, "date"), requested == null || !requested.isNumber() ? null : requested.asInt(), nonEmpty(request, "time"), nonEmpty(request, "seating"));
    Integer guests = asked.guests() == null || asked.guests() <= 0 ? null : asked.guests();
    int max = ReservationRules.MAX_ONLINE_GUESTS;
    ObjectNode contact = Json.obj("whatsapp", WHATSAPP_URL, "phone", PHONE_URL, "phoneLabel", PHONE_DISPLAY, "email", EMAIL);
    if (guests != null && guests > max) {
      ObjectNode booking = Json.obj("kind", "contact", "guests", guests, "maxOnlineGuests", max);
      booking.setAll(contact);
      return Json.obj("reply", copy.large().apply(max), "items", Json.arr(), "mode", "booking", "language", lang, "booking", booking);
    }
    ObjectNode query = Json.obj();
    if (asked.date() != null && asked.date().compareTo(today) >= 0) query.put("date", asked.date());
    if (asked.time() != null && !asked.time().isEmpty()) query.put("time", asked.time());
    if (guests != null) query.put("guests", guests);
    if (asked.seating() != null) query.put("seating", asked.seating());
    String reply = copy.online().apply(max) + (guests == null ? "\n" + copy.groups() : "");
    ObjectNode booking = Json.obj("kind", "online", "guests", guests == null ? Json.UNDEFINED : guests, "maxOnlineGuests", max, "query", query);
    booking.setAll(contact);
    return Json.obj("reply", reply, "items", Json.arr(), "mode", "booking", "language", lang, "booking", booking);
  }

  private static String nonEmpty(JsonNode request, String key) {
    JsonNode value = Json.get(request, key);
    if (value == null || value.isNull()) return null;
    String text = Json.jsString(value);
    return text.isEmpty() ? null : text;
  }
}
