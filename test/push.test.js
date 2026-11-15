import test from 'node:test';
import assert from 'node:assert/strict';
import { bookingNotification, createPush } from '../src/push.js';

function fakeDb(rows) {
  const deleted = [];
  return {
    deleted, rows,
    from() {
      const query = {
        select: () => query, eq: (key, value) => { query.filter = [key, value]; return query; },
        delete: () => { query.remove = true; return query; },
        upsert: row => { rows.push({ id: rows.length + 1, ...row }); return Promise.resolve({ data: null, error: null }); },
        then: (resolve, reject) => {
          if (query.remove) deleted.push(query.filter[1]);
          return Promise.resolve({ data: rows, count: rows.length, error: null }).then(resolve, reject);
        },
      };
      return query;
    },
  };
}

test('new booking alert names the guest, party, time and tables', () => {
  const payload = bookingNotification({ id: 7, bookingCode: 'RTR-1', customerName: 'Lan', numberOfGuests: 4, bookingDate: '2026-11-20', startTime: '19:00', area: 'TERRACE', tables: [{ tableNumber: 'T5' }, { tableNumber: 'T6' }] });
  assert.equal(payload.body, 'Lan · 4 guests · 20/11 19:00 · TERRACE T5, T6');
  assert.equal(payload.url, '/admin/booking/list?bookingId=7');
});

test('broadcast sends to every device and forgets revoked subscriptions', async () => {
  const db = fakeDb([{ id: 1, endpoint: 'https://push.example/a', p256dh: 'k', auth: 'a' }, { id: 2, endpoint: 'https://push.example/gone', p256dh: 'k', auth: 'a' }]);
  const sent = [];
  const sender = { sendNotification: async (subscription, payload) => { sent.push([subscription.endpoint, JSON.parse(payload).title]); if (subscription.endpoint.endsWith('gone')) throw Object.assign(new Error('gone'), { statusCode: 410 }); } };
  const push = createPush({ db, publicKey: 'pub', privateKey: 'priv', sender, logger: { warn() {} } });
  assert.deepEqual(await push.test(), { registered: 2, accepted: 1 });
  assert.equal(sent.length, 2);
  assert.deepEqual(db.deleted, [2]);
});

test('without VAPID keys push reports itself disabled', async () => {
  const push = createPush({ db: fakeDb([]), publicKey: '', privateKey: '' });
  assert.equal(push.configured, false);
  assert.deepEqual(await push.config(), { enabled: false, publicKey: '', subscriptionCount: 0 });
  assert.throws(() => push.test(), /not configured/);
});
