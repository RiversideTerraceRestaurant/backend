package com.menumaestro.backend.config;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

/**
 * Runtime configuration read from the same environment variables (and backend/.env file)
 * as the original Node.js API, so the production environment needs no changes.
 */
public record AppConfig(
  int port, boolean production, List<String> origins,
  String supabaseUrl, String supabaseKey, String sessionSecret,
  String groqKey, String groqModel, String geminiKey, String geminiModel,
  boolean trustProxy, int dailyLimit, String knowledgePath,
  String supabaseServiceKey, String timeZone, Push push, Mail mail) {

  public record Push(String publicKey, String privateKey, String subject) {}

  public record Mail(String host, int port, boolean secure, String user, String password, String from) {}

  public static AppConfig load(Function<String, String> env) {
    boolean production = "production".equals(env.apply("NODE_ENV"));
    if (empty(env.apply("SUPABASE_URL")) || empty(env.apply("SUPABASE_ANON_KEY"))) {
      throw new IllegalStateException("Configure SUPABASE_URL and SUPABASE_ANON_KEY in backend/.env");
    }
    String secret = env.apply("SESSION_SECRET");
    if (production && (secret == null ? 0 : secret.length()) < 32) {
      throw new IllegalStateException("Production requires SESSION_SECRET with at least 32 characters");
    }
    if (empty(secret)) {
      byte[] random = new byte[32];
      new SecureRandom().nextBytes(random);
      secret = HexFormat.of().formatHex(random);
    }
    String groqModel = env.apply("GROQ_MODEL") == null ? "" : env.apply("GROQ_MODEL").trim();
    return new AppConfig(
      (int) number(env.apply("PORT"), 3001), production,
      Arrays.stream(or(env.apply("FRONTEND_ORIGIN"), "http://localhost:8080").split(",")).map(String::trim).toList(),
      env.apply("SUPABASE_URL"), env.apply("SUPABASE_ANON_KEY"), secret,
      or(env.apply("GROQ_API_KEY"), ""), groqModel.isEmpty() ? "auto" : groqModel,
      or(env.apply("GEMINI_API_KEY"), ""), or(env.apply("GEMINI_MODEL"), "gemini-2.5-flash"),
      "true".equals(env.apply("TRUST_PROXY")), (int) number(env.apply("AI_DAILY_LIMIT"), 500),
      empty(env.apply("KNOWLEDGE_BASE_PATH")) ? null : env.apply("KNOWLEDGE_BASE_PATH"),
      or(env.apply("SUPABASE_SERVICE_ROLE_KEY"), ""), or(env.apply("RESTAURANT_TIMEZONE"), "Asia/Ho_Chi_Minh"),
      new Push(or(env.apply("VAPID_PUBLIC_KEY"), ""), or(env.apply("VAPID_PRIVATE_KEY"), ""), or(env.apply("VAPID_SUBJECT"), "mailto:admin@riversideterrace.vn")),
      new Mail(or(env.apply("MAIL_HOST"), ""), (int) number(env.apply("MAIL_PORT"), 587), "true".equals(env.apply("MAIL_SECURE")),
        or(env.apply("MAIL_USERNAME"), ""), or(env.apply("MAIL_PASSWORD"), ""), or(env.apply("MAIL_FROM"), "")));
  }

  private static boolean empty(String value) { return value == null || value.isEmpty(); }

  /** JavaScript `value || fallback` for strings. */
  private static String or(String value, String fallback) { return empty(value) ? fallback : value; }

  /** Number(value || fallback). */
  private static double number(String value, double fallback) {
    if (empty(value)) return fallback;
    return com.menumaestro.backend.common.json.Js.toNumber(value);
  }
}
