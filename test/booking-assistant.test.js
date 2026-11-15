import test from 'node:test';
import assert from 'node:assert/strict';
import { answerAvailability, detectLanguage, isBookingQuestion, parseBookingRequest } from '../src/booking-assistant.js';

const today = '2026-11-15';
// 10:00 in Da Nang on the fixed test day.
const clock = () => new Date('2026-11-15T03:00:00Z');

test('parses dates, times, party size and seating in several languages', () => {
  assert.deepEqual(parseBookingRequest('Còn bàn cho 4 người tối mai lúc 7h không?', today), { date: '2026-11-16', guests: 4, time: '19:00' });
  assert.deepEqual(parseBookingRequest('table for 2 tomorrow at 7pm outside', today), { date: '2026-11-16', guests: 2, time: '19:00', seating: 'outside' });
  assert.deepEqual(parseBookingRequest('đặt bàn ngày 20/11 19h30 3 khách trong nhà', today), { date: '2026-11-20', guests: 3, time: '19:30', seating: 'inside' });
  assert.equal(parseBookingRequest('book a table on saturday for six', today).date, '2026-11-21');
  assert.deepEqual(parseBookingRequest('明天晚上7点 4位 订位', today), { date: '2026-11-16', guests: 4, time: '19:00' });
  assert.equal(parseBookingRequest('reserve 25 dec 8pm', today).time, '20:00');
  assert.equal(parseBookingRequest('ngày 3 đặt bàn', today).date, '2026-12-03');
  assert.equal(parseBookingRequest('bàn ở Verona tối nay 8h30', today).area, 'VERONA');
});

test('recognises booking questions without catching menu questions', () => {
  for (const text of ['Còn bàn trống tối nay không?', 'Can I book a table?', 'table tonight for 2 people?', '明天订位', 'Хочу забронировать столик']) assert.ok(isBookingQuestion(text), text);
  for (const text of ['Do you have vegan pasta?', 'món này cho 2 người ăn được không', 'what time do you open']) assert.ok(!isBookingQuestion(text), text);
  assert.equal(detectLanguage('còn bàn không', 'en'), 'vi');
  assert.equal(detectLanguage('table please', 'fr'), 'fr');
});

function reservationsWith(freeByDate) {
  return {
    availability: async ({ area, date }) => Array.from({ length: 12 }, (_, index) => ({
      id: index + 1, tableNumber: `${area[0]}${index + 1}`, area, capacity: 2,
      status: (freeByDate[date] ?? []).includes(area) ? 'AVAILABLE' : 'RESERVED',
    })),
  };
}

test('answers with real suggestions and a booking payload', async () => {
  const answer = await answerAvailability({ text: 'bàn cho 4 người ngày 20/11 lúc 19h ngoài trời', language: 'vi', reservations: reservationsWith({ '2026-11-20': ['TERRACE'] }), timeZone: 'Asia/Ho_Chi_Minh', clock });
  assert.equal(answer.mode, 'booking');
  assert.match(answer.reply, /còn bàn cho 4 khách/);
  assert.deepEqual(answer.booking.options.map(option => option.area), ['TERRACE']);
  assert.equal(answer.booking.options[0].tables.length, 2);
  assert.equal(answer.booking.endTime, '21:00');
});

test('points to the next free day when the requested day is full', async () => {
  const answer = await answerAvailability({ request: { date: '2026-11-20', time: '19:00', guests: 2 }, language: 'en', reservations: reservationsWith({ '2026-11-22': ['ROMA'] }), timeZone: 'Asia/Ho_Chi_Minh', clock });
  assert.deepEqual(answer.booking.options, []);
  assert.equal(answer.booking.nextDate, '2026-11-22');
  assert.match(answer.reply, /next day with free tables/);
});

test('large parties are sent to the phone line', async () => {
  const answer = await answerAvailability({ text: 'table for 9 people tomorrow', language: 'en', reservations: reservationsWith({}), timeZone: 'Asia/Ho_Chi_Minh', clock });
  assert.equal(answer.booking, null);
  assert.match(answer.reply, /up to 6 guests/);
});
