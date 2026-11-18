package com.menumaestro.backend.push;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.menumaestro.backend.common.json.Json;
import com.menumaestro.backend.supabase.SupabaseHttp;

class WebPushSenderTest {
  private static byte[] concat(byte[]... parts) {
    ByteBuffer buffer = ByteBuffer.allocate(Arrays.stream(parts).mapToInt(part -> part.length).sum());
    for (byte[] part : parts) buffer.put(part);
    return buffer.array();
  }

  /** Receiver side of RFC 8291, used to verify the sender's aes128gcm encoding. */
  private static byte[] decrypt(byte[] body, KeyPair receiver, byte[] authSecret) throws Exception {
    byte[] salt = Arrays.copyOfRange(body, 0, 16);
    int keyLength = body[20];
    byte[] senderPublic = Arrays.copyOfRange(body, 21, 21 + keyLength);
    byte[] ciphertext = Arrays.copyOfRange(body, 21 + keyLength, body.length);
    KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
    agreement.init(receiver.getPrivate());
    agreement.doPhase(WebPushSender.publicKey(senderPublic), true);
    byte[] receiverPublic = WebPushSender.uncompressed((ECPublicKey) receiver.getPublic());
    byte[] prkKey = WebPushSender.hmac(authSecret, agreement.generateSecret());
    byte[] ikm = WebPushSender.hmac(prkKey, concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[] {0}, receiverPublic, senderPublic, new byte[] {1}));
    byte[] prk = WebPushSender.hmac(salt, ikm);
    byte[] cek = Arrays.copyOf(WebPushSender.hmac(prk, concat("Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII), new byte[] {0, 1})), 16);
    byte[] nonce = Arrays.copyOf(WebPushSender.hmac(prk, concat("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII), new byte[] {0, 1})), 12);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
    byte[] plain = cipher.doFinal(ciphertext);
    assertEquals(2, plain[plain.length - 1]);
    return Arrays.copyOf(plain, plain.length - 1);
  }

  @Test
  void encryptsPayloadsThatTheBrowserCanDecrypt() throws Exception {
    KeyPair receiver = WebPushSender.ephemeralKeyPair();
    byte[] auth = new byte[16];
    new java.security.SecureRandom().nextBytes(auth);
    byte[] body = WebPushSender.encrypt("{\"title\":\"Hi\"}".getBytes(StandardCharsets.UTF_8), WebPushSender.uncompressed((ECPublicKey) receiver.getPublic()), auth,
      WebPushSender.ephemeralKeyPair(), new byte[16]);
    assertEquals(4096, ByteBuffer.wrap(body, 16, 4).getInt());
    assertEquals("{\"title\":\"Hi\"}", new String(decrypt(body, receiver, auth), StandardCharsets.UTF_8));
  }

  @Test
  void signsVapidHeadersAndReportsPushServiceErrors() throws Exception {
    KeyPair vapid = WebPushSender.ephemeralKeyPair();
    String publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(WebPushSender.uncompressed((ECPublicKey) vapid.getPublic()));
    byte[] scalar = ((java.security.interfaces.ECPrivateKey) vapid.getPrivate()).getS().toByteArray();
    String privateKey = Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOfRange(scalar, Math.max(0, scalar.length - 32), scalar.length));
    KeyPair browser = WebPushSender.ephemeralKeyPair();
    String p256dh = Base64.getUrlEncoder().withoutPadding().encodeToString(WebPushSender.uncompressed((ECPublicKey) browser.getPublic()));
    AtomicReference<Map<String, String>> captured = new AtomicReference<>();
    WebPushSender sender = new WebPushSender("mailto:admin@example.com", publicKey, privateKey, (method, url, headers, body, timeout) -> {
      captured.set(headers);
      return new SupabaseHttp.Response(410, new byte[0], Map.of());
    });
    WebPushSender.PushException error = org.junit.jupiter.api.Assertions.assertThrows(WebPushSender.PushException.class,
      () -> sender.send("https://push.example.com/abc", p256dh, "AAAAAAAAAAAAAAAAAAAAAA", "{}"));
    assertEquals(410, error.statusCode());
    Map<String, String> headers = captured.get();
    assertEquals("aes128gcm", headers.get("Content-Encoding"));
    assertEquals("3600", headers.get("TTL"));
    assertEquals("high", headers.get("Urgency"));
    String authorization = headers.get("Authorization");
    assertTrue(authorization.endsWith(", k=" + publicKey));
    String jwt = authorization.substring("vapid t=".length(), authorization.indexOf(','));
    String[] parts = jwt.split("\\.");
    var claims = Json.parse(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
    assertEquals("https://push.example.com", Json.str(claims, "aud"));
    Signature verify = Signature.getInstance("SHA256withECDSAinP1363Format");
    verify.initVerify(vapid.getPublic());
    verify.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
    assertTrue(verify.verify(Base64.getUrlDecoder().decode(parts[2])));
  }

  @Test
  void formatsNewBookingAlerts() {
    var booking = Json.obj("id", 12, "bookingCode", "RTR-20261120-0001", "customerName", "An", "numberOfGuests", 4, "bookingDate", "2026-11-20",
      "startTime", "19:00", "area", "ROMA", "tables", List.of(Json.obj("tableNumber", "R1"), Json.obj("tableNumber", "R2")));
    var notification = PushService.bookingNotification(booking);
    assertEquals("New booking RTR-20261120-0001", Json.str(notification, "title"));
    assertEquals("An · 4 guests · 20/11 19:00 · ROMA R1, R2", Json.str(notification, "body"));
    assertEquals("/admin/booking/list?bookingId=12", Json.str(notification, "url"));
    assertArrayEquals(new String[] {"title", "body", "url", "tag"}, notification.propertyNames().toArray(new String[0]));
  }
}
