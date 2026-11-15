// Web push alerts for admins, ported from the RTR PushNotificationService.
import webpush from 'web-push';
import { z } from 'zod';
import { HttpError } from './errors.js';

export const subscriptionSchema = z.object({
  endpoint: z.string().url().max(2000),
  keys: z.object({ p256dh: z.string().min(1).max(200), auth: z.string().min(1).max(100) }),
});
export const endpointSchema = z.object({ endpoint: z.string().url().max(2000) });

export function bookingNotification(booking) {
  const tables = booking.tables.map(table => table.tableNumber).join(', ') || '—';
  const date = booking.bookingDate.split('-').reverse().slice(0, 2).join('/');
  return {
    title: `New booking ${booking.bookingCode}`,
    body: `${booking.customerName} · ${booking.numberOfGuests} guests · ${date} ${booking.startTime} · ${booking.area} ${tables}`,
    url: `/admin/booking/list?bookingId=${booking.id}`,
    tag: `booking-${booking.id}`,
  };
}

export function createPush({ db, publicKey, privateKey, subject, logger = console, sender = webpush }) {
  const configured = Boolean(db && publicKey && privateKey);
  const options = configured ? { vapidDetails: { subject: subject || 'mailto:admin@riversideterrace.vn', publicKey, privateKey }, TTL: 3600, urgency: 'high' } : null;
  const requireReady = () => { if (!configured) throw new HttpError(503, 'Push notifications are not configured. Set VAPID_PUBLIC_KEY and VAPID_PRIVATE_KEY.'); };
  const unwrap = ({ data, error }) => { if (error) throw new HttpError(502, 'Database request failed. Please try again.'); return data; };

  async function sendTo(subscription, payload) {
    try {
      await sender.sendNotification({ endpoint: subscription.endpoint, keys: { p256dh: subscription.p256dh, auth: subscription.auth } }, JSON.stringify(payload), options);
      return true;
    } catch (error) {
      // The browser revoked the subscription: forget it.
      if (error.statusCode === 404 || error.statusCode === 410) await db.from('push_subscriptions').delete().eq('id', subscription.id);
      else logger.warn(JSON.stringify({ event: 'push.failed', status: error.statusCode || null }));
      return false;
    }
  }
  async function broadcast(payload) {
    if (!configured) return { registered: 0, accepted: 0 };
    const subscriptions = unwrap(await db.from('push_subscriptions').select('id, endpoint, p256dh, auth'));
    const results = await Promise.all(subscriptions.map(subscription => sendTo(subscription, payload)));
    return { registered: subscriptions.length, accepted: results.filter(Boolean).length };
  }

  return {
    configured,
    async config() {
      if (!configured) return { enabled: false, publicKey: '', subscriptionCount: 0 };
      const { count } = await db.from('push_subscriptions').select('id', { count: 'exact', head: true });
      return { enabled: true, publicKey, subscriptionCount: count || 0 };
    },
    async subscribe(input, { userId, userAgent }) {
      requireReady();
      unwrap(await db.from('push_subscriptions').upsert({
        endpoint: input.endpoint, p256dh: input.keys.p256dh, auth: input.keys.auth,
        user_id: userId || null, user_agent: (userAgent || '').slice(0, 500), updated_at: new Date().toISOString(),
      }, { onConflict: 'endpoint' }));
      return { ok: true };
    },
    async unsubscribe({ endpoint }) {
      requireReady();
      unwrap(await db.from('push_subscriptions').delete().eq('endpoint', endpoint));
      return { ok: true };
    },
    test() {
      requireReady();
      return broadcast({ title: 'Riverside Terrace', body: 'Push notifications are working on this device.', url: '/admin/booking', tag: 'push-test' });
    },
    notifyNewBooking: booking => broadcast(bookingNotification(booking)),
  };
}
