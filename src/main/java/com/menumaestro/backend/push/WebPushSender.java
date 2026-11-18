package com.menumaestro.backend.push;

import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.menumaestro.backend.common.json.Json;
import com.menumaestro.backend.supabase.SupabaseHttp;

/**
 * Web Push sender equivalent to the web-push npm package: VAPID (RFC 8292, ES256 JWT)
 * and aes128gcm payload encryption (RFC 8291), using only JDK cryptography.
 */
public class WebPushSender {
  /** Thrown for non-2xx push service responses (statusCode like web-push's WebPushError). */
  public static class PushException extends Exception {
    private final int statusCode;

    public PushException(int statusCode, String message) {
      super(message);
      this.statusCode = statusCode;
    }

    public int statusCode() { return statusCode; }
  }

  public interface Transport {
    SupabaseHttp.Response send(String method, String url, Map<String, String> headers, byte[] body, Duration timeout) throws Exception;
  }

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Decoder B64 = Base64.getUrlDecoder();
  private static final Base64.Encoder B64E = Base64.getUrlEncoder().withoutPadding();

  private final String subject;
  private final String publicKey;
  private final ECPrivateKey privateKey;
  private final Transport transport;

  public WebPushSender(String subject, String publicKey, String privateKey, Transport transport) {
    this.subject = subject;
    this.publicKey = publicKey;
    this.privateKey = privateKey(decode(privateKey));
    this.transport = transport;
  }

  static byte[] decode(String base64url) { return B64.decode(base64url.trim().replace('+', '-').replace('/', '_').replace("=", "")); }

  static ECParameterSpec curve() {
    try {
      AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
      parameters.init(new ECGenParameterSpec("secp256r1"));
      return parameters.getParameterSpec(ECParameterSpec.class);
    } catch (Exception error) { throw new IllegalStateException(error); }
  }

  static ECPrivateKey privateKey(byte[] scalar) {
    try {
      return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), curve()));
    } catch (Exception error) { throw new IllegalArgumentException("Invalid VAPID private key", error); }
  }

  static ECPublicKey publicKey(byte[] uncompressed) {
    try {
      if (uncompressed.length != 65 || uncompressed[0] != 4) throw new IllegalArgumentException("Expected an uncompressed P-256 point");
      ECPoint point = new ECPoint(new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33)), new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65)));
      return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, curve()));
    } catch (IllegalArgumentException error) { throw error; }
    catch (Exception error) { throw new IllegalArgumentException("Invalid public key", error); }
  }

  static byte[] uncompressed(ECPublicKey key) {
    byte[] out = new byte[65];
    out[0] = 4;
    byte[] x = unsigned(key.getW().getAffineX(), 32);
    byte[] y = unsigned(key.getW().getAffineY(), 32);
    System.arraycopy(x, 0, out, 1, 32);
    System.arraycopy(y, 0, out, 33, 32);
    return out;
  }

  private static byte[] unsigned(BigInteger value, int length) {
    byte[] bytes = value.toByteArray();
    if (bytes.length == length) return bytes;
    byte[] out = new byte[length];
    if (bytes.length > length) System.arraycopy(bytes, bytes.length - length, out, 0, length);
    else System.arraycopy(bytes, 0, out, length - bytes.length, bytes.length);
    return out;
  }

  static byte[] hmac(byte[] key, byte[]... parts) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      for (byte[] part : parts) mac.update(part);
      return mac.doFinal();
    } catch (Exception error) { throw new IllegalStateException(error); }
  }

  private static byte[] concat(byte[]... parts) {
    int length = 0;
    for (byte[] part : parts) length += part.length;
    ByteBuffer buffer = ByteBuffer.allocate(length);
    for (byte[] part : parts) buffer.put(part);
    return buffer.array();
  }

  /** RFC 8291 aes128gcm body for a single record, with the given ephemeral key pair and salt (testable). */
  static byte[] encrypt(byte[] payload, byte[] uaPublic, byte[] authSecret, KeyPair ephemeral, byte[] salt) throws Exception {
    KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
    agreement.init(ephemeral.getPrivate());
    agreement.doPhase(publicKey(uaPublic), true);
    byte[] ecdhSecret = agreement.generateSecret();
    byte[] asPublic = uncompressed((ECPublicKey) ephemeral.getPublic());
    byte[] prkKey = hmac(authSecret, ecdhSecret);
    byte[] keyInfo = concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[] {0}, uaPublic, asPublic, new byte[] {1});
    byte[] ikm = hmac(prkKey, keyInfo);
    byte[] prk = hmac(salt, ikm);
    byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII), new byte[] {0, 1})), 16);
    byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII), new byte[] {0, 1})), 12);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
    byte[] ciphertext = cipher.doFinal(concat(payload, new byte[] {2}));
    ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length);
    header.put(salt).putInt(4096).put((byte) asPublic.length).put(asPublic);
    return concat(header.array(), ciphertext);
  }

  static KeyPair ephemeralKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
    return generator.generateKeyPair();
  }

  /** VAPID JWT (ES256) for the push service origin, valid for 12 hours like web-push. */
  String vapidToken(String audience, long nowSeconds) throws Exception {
    String header = B64E.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
    String claims = B64E.encodeToString(Json.stringify(Json.obj("aud", audience, "exp", nowSeconds + 12 * 3600, "sub", subject)).getBytes(StandardCharsets.UTF_8));
    Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
    signature.initSign(privateKey);
    signature.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
    return header + "." + claims + "." + B64E.encodeToString(signature.sign());
  }

  /** Sends an encrypted notification; TTL 3600 and Urgency high as configured in the original. */
  public void send(String endpoint, String p256dh, String auth, String payload) throws Exception {
    URI uri = URI.create(endpoint);
    String audience = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    byte[] salt = new byte[16];
    RANDOM.nextBytes(salt);
    byte[] body = encrypt(payload.getBytes(StandardCharsets.UTF_8), decode(p256dh), decode(auth), ephemeralKeyPair(), salt);
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("TTL", "3600");
    headers.put("Urgency", "high");
    headers.put("Content-Encoding", "aes128gcm");
    headers.put("Content-Type", "application/octet-stream");
    headers.put("Authorization", "vapid t=" + vapidToken(audience, System.currentTimeMillis() / 1000) + ", k=" + publicKey);
    SupabaseHttp.Response response = transport.send("POST", endpoint, headers, body, Duration.ofSeconds(30));
    if (!response.ok()) throw new PushException(response.status(), "Received unexpected response code");
  }
}
