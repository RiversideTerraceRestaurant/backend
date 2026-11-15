// Chat assistant for table availability. Parses natural-language requests
// ("bàn cho 4 người tối mai 7h", "table for 2 tomorrow at 7pm") and answers from
// live reservation data. Deterministic: it never invents availability.
import { AREA_LABELS, MAX_ONLINE_GUESTS, bestTableGroup, restaurantNow, suggestionAreas } from './reservations.js';

// Bookable start times. Keep in sync with frontend/src/lib/bookingSlots.ts.
export const FIRST_SLOT = '11:00';
export const LAST_SLOT = '21:30';
export const DEFAULT_DURATION_MINUTES = 120;
const DEFAULT_TIME = '19:00';

const toMinutes = time => Number(time.slice(0, 2)) * 60 + Number(time.slice(3, 5));
const toTime = minutes => `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
export const endTimeFor = start => toTime(Math.min(toMinutes(start) + DEFAULT_DURATION_MINUTES, 23 * 60 + 30));
export function addDays(date, days) {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}
/** First bookable slot strictly after `time`, or null when the day is over. */
function nextSlotAfter(time) {
  const minutes = Math.max(toMinutes(FIRST_SLOT), Math.ceil((toMinutes(time) + 1) / 30) * 30);
  return minutes <= toMinutes(LAST_SLOT) ? toTime(minutes) : null;
}

const normalize = text => text.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/đ/g, 'd');

const BOOKING_WORDS = [
  /\b(dat|giu|con|kiem tra|check)\s+(ban|cho)\b/, /\bban\s+(trong|con)\b/, /\bcon\s+(ban|cho)\b/,
  /\bbook(ing)?\b/, /\breserv/, /\btable(s)?\s+(for|available)\b/, /\b(free|available|open)\s+(table|seat)/, /\bavailability\b/,
  /예약|빈\s*자리|자리\s*있/, /予約|空席|空いて/, /订位|订桌|预订|预定|空位|有位/, /столик|брон/, /үстел|бронда/,
  /\bmesa\b/, /\btable pour\b|\bune table\b/, /\bprenot|\btavolo\b/,
  // "bàn"/"table" together with a party size, a day or a time.
  /\b(ban|table)\b[^.?!]*(\d+\s*(nguoi|khach|people|guests|pax)|toi nay|hom nay|ngay mai|tonight|today|tomorrow|\d{1,2}\s*(h|gio|pm|am)\b)/,
];
export const isBookingQuestion = text => BOOKING_WORDS.some(pattern => pattern.test(normalize(text)));

const MONTHS = { jan: 1, feb: 2, mar: 3, apr: 4, may: 5, jun: 6, jul: 7, aug: 8, sep: 9, oct: 10, nov: 11, dec: 12 };
const WEEKDAYS = [
  [/\bsunday\b|\bchu nhat\b|\bcn\b|일요일|日曜|星期日|周日|воскрес|domingo|dimanche|domenica|жексенбі/, 0],
  [/\bmonday\b|\bthu (2|hai)\b|월요일|月曜|星期一|周一|понедельн|lunes|lundi|lunedi|дүйсенбі/, 1],
  [/\btuesday\b|\bthu (3|ba)\b|화요일|火曜|星期二|周二|вторник|martes|mardi|martedi|сейсенбі/, 2],
  [/\bwednesday\b|\bthu (4|tu)\b|수요일|水曜|星期三|周三|сред|miercoles|mercredi|mercoledi|сәрсенбі/, 3],
  [/\bthursday\b|\bthu (5|nam)\b|목요일|木曜|星期四|周四|четверг|jueves|jeudi|giovedi|бейсенбі/, 4],
  [/\bfriday\b|\bthu (6|sau)\b|금요일|金曜|星期五|周五|пятниц|viernes|vendredi|venerdi|жұма/, 5],
  [/\bsaturday\b|\bthu (7|bay)\b|토요일|土曜|星期六|周六|суббот|sabado|samedi|sabato|сенбі/, 6],
];
const EVENING = /\b(pm|evening|tonight|toi|chieu|noche|soir|sera)\b|저녁|오후|晚|夜|下午|午後|вечер/;
const NUMBER_WORDS = { one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10, hai: 2, bon: 4, sau: 6, bay: 7, tam: 8, chin: 9, muoi: 10 };

/** Extracts what the guest asked for. Missing fields stay undefined. */
export function parseBookingRequest(text, today) {
  let rest = normalize(text);
  const result = {};
  const takeDate = (date, match) => { result.date = date; rest = rest.replace(match, ' '); };
  let match;
  if ((match = rest.match(/\b(20\d{2})-(\d{1,2})-(\d{1,2})\b/))) takeDate(`${match[1]}-${match[2].padStart(2, '0')}-${match[3].padStart(2, '0')}`, match[0]);
  else if ((match = rest.match(/\b(\d{1,2})[/.-](\d{1,2})(?:[/.-](20\d{2}|\d{2}))?\b/)) && Number(match[2]) <= 12 && Number(match[1]) <= 31) {
    const year = match[3] ? (match[3].length === 2 ? `20${match[3]}` : match[3]) : today.slice(0, 4);
    let date = `${year}-${match[2].padStart(2, '0')}-${match[1].padStart(2, '0')}`;
    if (!match[3] && date < today) date = `${Number(year) + 1}${date.slice(4)}`;
    takeDate(date, match[0]);
  } else if ((match = rest.match(/\bngay (\d{1,2})(?: thang (\d{1,2}))?\b/))) {
    let year = Number(today.slice(0, 4));
    let month = match[2] ? Number(match[2]) : Number(today.slice(5, 7));
    const day = match[1].padStart(2, '0');
    if (`${year}-${String(month).padStart(2, '0')}-${day}` < today) {
      // "ngày 3" after the 3rd means next month; an explicit past month means next year.
      if (match[2]) year++; else if (++month > 12) { month = 1; year++; }
    }
    takeDate(`${year}-${String(month).padStart(2, '0')}-${day}`, match[0]);
  } else if ((match = rest.match(/\b(\d{1,2})(?:st|nd|rd|th)?\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\b|\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\s+(\d{1,2})(?:st|nd|rd|th)?\b/))) {
    const day = match[1] || match[4];
    const month = MONTHS[match[2] || match[3]];
    let date = `${today.slice(0, 4)}-${String(month).padStart(2, '0')}-${day.padStart(2, '0')}`;
    if (date < today) date = `${Number(today.slice(0, 4)) + 1}${date.slice(4)}`;
    takeDate(date, match[0]);
  } else if (/day after tomorrow|ngay kia|모레|明後日|后天|послезавтра|pasado manana|apres-demain|dopodomani|бүрсігүні/.test(rest)) result.date = addDays(today, 2);
  else if (/tomorrow|ngay mai|\btoi mai\b|\bsang mai\b|\bmai\b|내일|明日|明天|завтра|manana|demain|domani|ертең/.test(rest)) result.date = addDays(today, 1);
  else if (/today|tonight|hom nay|toi nay|chieu nay|오늘|今日|今天|今晚|сегодня|\bhoy\b|aujourd|stasera|\boggi\b|бүгін/.test(rest)) result.date = today;
  else {
    const weekday = WEEKDAYS.find(([pattern]) => pattern.test(rest));
    if (weekday) {
      const current = new Date(`${today}T00:00:00Z`).getUTCDay();
      result.date = addDays(today, (weekday[1] - current + 7) % 7);
    }
  }

  // Guests before time so "4 nguoi luc 7h" does not read 4 as an hour.
  const guestPattern = /\b(\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|hai|bon|sau|bay|tam|chin|muoi)\s*(nguoi|khach|ng\b|people|persons?|guests?|pax|adults?|personas|personnes|persone|человек|чел|адам)|(\d{1,2})\s*(명|人|位|名)|\b(?:for|party of|table for|ban cho|cho)\s+(\d{1,2}|two|three|four|five|six|seven|eight|nine|ten)\b(?!\s*(:|h\b|gio|am|pm))/;
  if ((match = rest.match(guestPattern))) {
    const raw = match[1] || match[3] || match[5];
    result.guests = NUMBER_WORDS[raw] ?? Number(raw);
    rest = rest.replace(match[0], ' ');
  }

  let hour; let minute = 0;
  if ((match = rest.match(/\b(\d{1,2})\s*[:h]\s*(\d{2})\b/))) { hour = Number(match[1]); minute = Number(match[2]); }
  else if ((match = rest.match(/\b(\d{1,2})\s*(am|pm|h\b|gio|giờ|o'?clock|uhr)|(\d{1,2})\s*(시|時|点)|(?:^|\s)(?:at|luc|vao luc|a las|alle|a|в)\s+(\d{1,2})(?!\d)/))) {
    hour = Number(match[1] || match[3] || match[5]);
    if (match[2] === 'am' && hour === 12) hour = 0;
    if ((match[2] === 'pm' || EVENING.test(rest)) && hour < 12) hour += 12;
    if (/rưỡi|ruoi|half|半/.test(rest)) minute = 30;
  }
  if (hour !== undefined && hour <= 23 && minute < 60) {
    // A bare "7" at a restaurant means the evening.
    if (hour >= 1 && hour <= 9 && !/\bam\b|sang|morning|오전|午前|上午|утр/.test(rest)) hour += 12;
    result.time = toTime(hour * 60 + Math.round(minute / 30) * 30);
  }

  const areas = Object.keys(AREA_LABELS).filter(area => rest.includes(area.toLowerCase()) && area !== 'TERRACE');
  if (/outside|outdoor|terrace|ngoai troi|ngoai san|ben ngoai|view song|river|야외|テラス|屋外|户外|室外|露台|улиц|террас|terraza|exterieur|terrasse|fuori|esterno|сырт/.test(rest)) result.seating = 'outside';
  else if (/inside|indoor|trong nha|ben trong|phong lanh|may lanh|실내|室内|в зале|внутри|interior|interieur|dentro|interno|ішкі/.test(rest) || areas.length) result.seating = 'inside';
  if (areas.length) result.area = areas[0];
  return result;
}

const LOCALES = { en: 'en-GB', vi: 'vi-VN', ko: 'ko-KR', ja: 'ja-JP', cn: 'zh-CN', ru: 'ru-RU', kz: 'kk-KZ', es: 'es-ES', fr: 'fr-FR', it: 'it-IT' };
const COPY = {
  en: {
    available: (when, guests) => `Good news — there are tables for ${guests} on ${when}:`,
    line: (area, free, tables) => `• ${area}: ${free} free table${free === 1 ? '' : 's'} — suggested ${tables}`,
    none: (when, guests) => `Sorry, online booking has no suitable tables for ${guests} on ${when}.`,
    next: when => `The next day with free tables is ${when}.`,
    large: max => `Online booking takes up to ${max} guests. For larger groups please call (+84) 911 500 440 or email litalianoriverside@gmail.com.`,
    assumed: parts => `(I assumed ${parts.join(', ')} — tell me if you'd like something else.)`,
    guests: guests => `${guests} guests`, time: time => `${time}`, today: 'today',
    moved: 'That time has passed, so I checked the next available slot.',
    cta: 'Tap a suggestion to reserve. Your table is held once you confirm the code sent to your email; the restaurant then approves it.',
  },
  vi: {
    available: (when, guests) => `Tin vui — vẫn còn bàn cho ${guests} khách vào ${when}:`,
    line: (area, free, tables) => `• ${area}: còn ${free} bàn — gợi ý ${tables}`,
    none: (when, guests) => `Rất tiếc, ${when} không còn bàn phù hợp cho ${guests} khách để đặt online.`,
    next: when => `Ngày gần nhất còn bàn là ${when}.`,
    large: max => `Đặt bàn online nhận tối đa ${max} khách. Nhóm đông hơn vui lòng gọi (+84) 911 500 440 hoặc email litalianoriverside@gmail.com.`,
    assumed: parts => `(Mình tạm hiểu là ${parts.join(', ')} — bạn muốn khác thì cứ nhắn nhé.)`,
    guests: guests => `${guests} khách`, time: time => `lúc ${time}`, today: 'hôm nay',
    moved: 'Giờ bạn chọn đã qua nên mình kiểm tra khung giờ gần nhất.',
    cta: 'Chọn một gợi ý để đặt. Bàn được giữ sau khi bạn xác nhận mã gửi qua email, sau đó nhà hàng sẽ duyệt.',
  },
  ko: {
    available: (when, guests) => `${when}에 ${guests}명 자리가 있습니다:`,
    line: (area, free, tables) => `• ${area}: 빈 테이블 ${free}개 — 추천 ${tables}`,
    none: (when, guests) => `죄송합니다. ${when}에는 ${guests}명이 온라인으로 예약할 수 있는 테이블이 없습니다.`,
    next: when => `가장 가까운 예약 가능일은 ${when}입니다.`,
    large: max => `온라인 예약은 최대 ${max}명까지 가능합니다. 단체는 (+84) 911 500 440으로 연락해 주세요.`,
    assumed: parts => `(${parts.join(', ')}(으)로 확인했습니다.)`,
    guests: guests => `${guests}명`, time: time => time, today: '오늘',
    moved: '선택한 시간이 지나 가장 가까운 시간으로 확인했습니다.',
    cta: '추천을 눌러 예약하세요. 이메일 인증 후 테이블이 확보되고 레스토랑이 승인합니다.',
  },
  ja: {
    available: (when, guests) => `${when}、${guests}名様のお席がございます：`,
    line: (area, free, tables) => `• ${area}：空き${free}卓 — おすすめ ${tables}`,
    none: (when, guests) => `申し訳ありません。${when}は${guests}名様のオンライン予約可能なお席がありません。`,
    next: when => `最も近い空きのある日は${when}です。`,
    large: max => `オンライン予約は${max}名様までです。大人数の場合は (+84) 911 500 440 へお電話ください。`,
    assumed: parts => `（${parts.join('、')}で確認しました）`,
    guests: guests => `${guests}名`, time: time => time, today: '本日',
    moved: 'ご指定の時間を過ぎているため、直近の時間で確認しました。',
    cta: '候補をタップしてご予約ください。メール認証後にお席を確保し、店舗が承認します。',
  },
  cn: {
    available: (when, guests) => `好消息，${when}有适合${guests}位的座位：`,
    line: (area, free, tables) => `• ${area}：空桌${free}张 — 推荐 ${tables}`,
    none: (when, guests) => `抱歉，${when}没有适合${guests}位的在线可订座位。`,
    next: when => `最近有空位的日期是${when}。`,
    large: max => `在线订位最多${max}位。更多人数请致电 (+84) 911 500 440。`,
    assumed: parts => `（已按${parts.join('、')}查询）`,
    guests: guests => `${guests}位`, time: time => time, today: '今天',
    moved: '所选时间已过，已为您查询最近的时段。',
    cta: '点击推荐即可预订。邮箱验证后为您保留座位，餐厅确认后生效。',
  },
  ru: {
    available: (when, guests) => `Есть свободные столики на ${guests} — ${when}:`,
    line: (area, free, tables) => `• ${area}: свободно ${free} — рекомендуем ${tables}`,
    none: (when, guests) => `К сожалению, на ${when} нет подходящих столиков на ${guests} для онлайн-брони.`,
    next: when => `Ближайшая дата со свободными столиками: ${when}.`,
    large: max => `Онлайн-бронь — до ${max} гостей. Для больших компаний звоните (+84) 911 500 440.`,
    assumed: parts => `(Проверил для: ${parts.join(', ')}.)`,
    guests: guests => `${guests} гост.`, time: time => time, today: 'сегодня',
    moved: 'Это время уже прошло, я проверил ближайшее.',
    cta: 'Нажмите на вариант, чтобы забронировать. Столик закрепляется после подтверждения кода из письма.',
  },
  kz: {
    available: (when, guests) => `${when} уақытына ${guests} қонаққа бос үстелдер бар:`,
    line: (area, free, tables) => `• ${area}: бос ${free} үстел — ұсыныс ${tables}`,
    none: (when, guests) => `Кешіріңіз, ${when} уақытына ${guests} қонаққа онлайн бос үстел жоқ.`,
    next: when => `Бос үстелі бар ең жақын күн: ${when}.`,
    large: max => `Онлайн брондау ${max} қонаққа дейін. Үлкен топтар үшін (+84) 911 500 440 нөміріне хабарласыңыз.`,
    assumed: parts => `(${parts.join(', ')} бойынша тексердім.)`,
    guests: guests => `${guests} қонақ`, time: time => time, today: 'бүгін',
    moved: 'Таңдалған уақыт өтіп кетті, ең жақын уақытты тексердім.',
    cta: 'Брондау үшін ұсынысты басыңыз. Электрондық поштадағы кодты растағаннан кейін үстел сақталады.',
  },
  es: {
    available: (when, guests) => `Hay mesas para ${guests} el ${when}:`,
    line: (area, free, tables) => `• ${area}: ${free} libres — sugerencia ${tables}`,
    none: (when, guests) => `Lo sentimos, el ${when} no hay mesas disponibles online para ${guests}.`,
    next: when => `El próximo día con mesas libres es el ${when}.`,
    large: max => `La reserva online admite hasta ${max} personas. Para grupos mayores llame al (+84) 911 500 440.`,
    assumed: parts => `(He supuesto ${parts.join(', ')}.)`,
    guests: guests => `${guests} personas`, time: time => time, today: 'hoy',
    moved: 'Esa hora ya pasó; he consultado el siguiente horario.',
    cta: 'Pulse una sugerencia para reservar. La mesa queda retenida al confirmar el código enviado por email.',
  },
  fr: {
    available: (when, guests) => `Il reste des tables pour ${guests} le ${when} :`,
    line: (area, free, tables) => `• ${area} : ${free} libres — suggestion ${tables}`,
    none: (when, guests) => `Désolé, aucune table disponible en ligne pour ${guests} le ${when}.`,
    next: when => `Le prochain jour avec des tables libres est le ${when}.`,
    large: max => `La réservation en ligne accepte jusqu'à ${max} personnes. Pour les groupes, appelez le (+84) 911 500 440.`,
    assumed: parts => `(J'ai supposé ${parts.join(', ')}.)`,
    guests: guests => `${guests} personnes`, time: time => time, today: "aujourd'hui",
    moved: "Cet horaire est passé ; j'ai vérifié le créneau suivant.",
    cta: 'Touchez une suggestion pour réserver. La table est bloquée après confirmation du code reçu par e-mail.',
  },
  it: {
    available: (when, guests) => `Ci sono tavoli per ${guests} il ${when}:`,
    line: (area, free, tables) => `• ${area}: ${free} liberi — consigliato ${tables}`,
    none: (when, guests) => `Spiacenti, il ${when} non ci sono tavoli prenotabili online per ${guests}.`,
    next: when => `Il primo giorno con tavoli liberi è il ${when}.`,
    large: max => `La prenotazione online accetta fino a ${max} ospiti. Per gruppi chiamate il (+84) 911 500 440.`,
    assumed: parts => `(Ho ipotizzato ${parts.join(', ')}.)`,
    guests: guests => `${guests} persone`, time: time => time, today: 'oggi',
    moved: "L'orario è già passato; ho controllato il primo disponibile.",
    cta: 'Tocca un suggerimento per prenotare. Il tavolo è bloccato dopo la conferma del codice ricevuto via email.',
  },
};

/** Picks the reply language from the text itself, falling back to the UI language. */
export function detectLanguage(text, fallback) {
  if (/[ăâđêôơưạảấầẩẫậắằẳẵặẹẻẽếềểễệỉịọỏốồổỗộớờởỡợụủứừửữựỳỵỷỹ]/i.test(text)) return 'vi';
  if (/[가-힯]/.test(text)) return 'ko';
  if (/[぀-ヿ]/.test(text)) return 'ja';
  if (/[一-鿿]/.test(text)) return 'cn';
  if (/[әғқңөұүһі]/i.test(text)) return 'kz';
  if (/[а-яё]/i.test(text)) return 'ru';
  return fallback;
}

/**
 * Answers an availability question. Guest-facing rule: a table with any booking that
 * day is shown as taken, the same as the booking page.
 */
export async function answerAvailability({ text = '', request = {}, language = 'en', reservations, timeZone, clock = () => new Date() }) {
  const lang = COPY[language] ? language : 'en';
  const copy = COPY[lang];
  const now = restaurantNow(timeZone, clock());
  const asked = { ...parseBookingRequest(text, now.date), ...Object.fromEntries(Object.entries(request).filter(([, value]) => value !== undefined && value !== '')) };
  const assumed = [];
  let guests = asked.guests;
  if (!guests) { guests = 2; assumed.push(copy.guests(2)); }
  if (guests > MAX_ONLINE_GUESTS) return { reply: copy.large(MAX_ONLINE_GUESTS), items: [], mode: 'booking', language: lang, booking: null };

  let date = asked.date && asked.date >= now.date ? asked.date : now.date;
  let time = asked.time || DEFAULT_TIME;
  if (toMinutes(time) < toMinutes(FIRST_SLOT)) time = FIRST_SLOT;
  if (toMinutes(time) > toMinutes(LAST_SLOT)) time = LAST_SLOT;
  let moved = false;
  if (date === now.date && time <= now.time) {
    const next = nextSlotAfter(now.time);
    if (next) time = next; else { date = addDays(date, 1); time = asked.time && toMinutes(asked.time) <= toMinutes(LAST_SLOT) ? asked.time : DEFAULT_TIME; }
    moved = true;
  }
  if (!moved) {
    if (!asked.time) assumed.unshift(copy.time(time));
    if (!asked.date) assumed.unshift(copy.today);
  }

  const areasFor = () => {
    let areas = asked.seating ? suggestionAreas(asked.seating, guests) : ['TERRACE', ...suggestionAreas('inside', guests)];
    if (asked.area) areas = [asked.area];
    if (guests <= 2) areas = areas.filter(area => area !== 'VERONA');
    return [...new Set([...areas, ...(asked.seating || asked.area ? [] : ['SORRENTO'])])];
  };
  const optionsOn = async day => {
    const options = [];
    for (const area of areasFor()) {
      const free = (await reservations.availability({ area, date: day, startTime: time, endTime: endTimeFor(time) })).filter(table => table.status === 'AVAILABLE');
      const tables = bestTableGroup(area, free, guests);
      if (tables.length) options.push({ area, label: AREA_LABELS[area], freeCount: free.length, tables: tables.map(({ id, tableNumber }) => ({ id, tableNumber })) });
    }
    return options;
  };

  const formatDate = day => new Intl.DateTimeFormat(LOCALES[lang], { weekday: 'short', day: 'numeric', month: 'numeric', timeZone: 'UTC' }).format(new Date(`${day}T00:00:00Z`));
  const when = `${formatDate(date)} ${time}`;
  const options = await optionsOn(date);
  const lines = [];
  let nextDate = null;
  if (moved) lines.push(copy.moved);
  if (options.length) {
    lines.push(copy.available(when, guests), ...options.map(option => copy.line(option.label, option.freeCount, option.tables.map(table => table.tableNumber).join('+'))));
    lines.push('', copy.cta);
  } else {
    lines.push(copy.none(when, guests));
    for (let offset = 1; offset <= 7 && !nextDate; offset++) if ((await optionsOn(addDays(date, offset))).length) nextDate = addDays(date, offset);
    if (nextDate) lines.push(copy.next(formatDate(nextDate)));
  }
  if (assumed.length) lines.push(copy.assumed(assumed));
  return {
    reply: lines.join('\n'), items: [], mode: 'booking', language: lang,
    booking: { date, time, endTime: endTimeFor(time), guests, seating: asked.seating || null, options, nextDate },
  };
}
