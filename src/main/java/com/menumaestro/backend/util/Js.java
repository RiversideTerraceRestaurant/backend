package com.menumaestro.backend.util;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small helpers that reproduce JavaScript string, number and RegExp semantics used by the original API. */
public final class Js {
  private Js() {}

  private static final String SPACE_EXTRA = "\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF";
  private static final ConcurrentHashMap<String, Pattern> CACHE = new ConcurrentHashMap<>();

  /**
   * Compiles a JavaScript regular expression source with JavaScript flags (i, m, g, u ignored where not relevant).
   * Translates JS-specific syntax: Unicode {@code \s}, end-of-input {@code $}, and case-insensitive Unicode matching.
   */
  public static Pattern re(String source, String flags) {
    return CACHE.computeIfAbsent(flags + "/" + source, key -> Pattern.compile(translate(source, flags.contains("m")), javaFlags(flags)));
  }

  public static Pattern re(String source) { return re(source, ""); }

  private static int javaFlags(String flags) {
    int result = 0;
    if (flags.contains("i")) result |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    if (flags.contains("m")) result |= Pattern.MULTILINE;
    return result;
  }

  private static String translate(String source, boolean multiline) {
    StringBuilder out = new StringBuilder();
    boolean inClass = false;
    for (int i = 0; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '\\' && i + 1 < source.length()) {
        char next = source.charAt(i + 1);
        if (next == 's') out.append(inClass ? "\\s" + SPACE_EXTRA : "[\\s" + SPACE_EXTRA + "]");
        else if (next == 'S') out.append(inClass ? "\\S" : "[^\\s" + SPACE_EXTRA + "]");
        else out.append(c).append(next);
        i++;
        continue;
      }
      if (inClass) {
        if (c == ']') inClass = false;
        else if (c == '[') { out.append("\\["); continue; }
        else if (c == '&' && i + 1 < source.length() && source.charAt(i + 1) == '&') { out.append("\\&"); continue; }
        out.append(c);
        continue;
      }
      if (c == '[') {
        inClass = true;
        out.append(c);
        if (i + 1 < source.length() && source.charAt(i + 1) == '^') { out.append('^'); i++; }
        if (i + 1 < source.length() && source.charAt(i + 1) == ']') { out.append("\\]"); i++; }
        continue;
      }
      if (c == '$' && !multiline) { out.append("\\z"); continue; }
      out.append(c);
    }
    return out.toString();
  }

  public static boolean test(String source, String text) { return re(source).matcher(text).find(); }

  public static boolean test(Pattern pattern, String text) { return pattern.matcher(text).find(); }

  /** String.prototype.replace with a global regex and a literal replacement. */
  public static String replaceAll(String text, String source, String replacement) {
    return re(source).matcher(text).replaceAll(Matcher.quoteReplacement(replacement));
  }

  public static String replaceAll(String text, Pattern pattern, String replacement) {
    return pattern.matcher(text).replaceAll(Matcher.quoteReplacement(replacement));
  }

  /** String.prototype.replace with a non-global regex: only the first match. */
  public static String replaceFirst(String text, String source, String replacement) {
    return re(source).matcher(text).replaceFirst(Matcher.quoteReplacement(replacement));
  }

  /** String.prototype.replace(string, string): replaces the first literal occurrence only. */
  public static String replaceFirstLiteral(String text, String search, String replacement) {
    int index = text.indexOf(search);
    return index < 0 ? text : text.substring(0, index) + replacement + text.substring(index + search.length());
  }

  /** First match groups like String.prototype.match without g; null when there is no match. Missing groups are null. */
  public static String[] match(String text, String source) { return match(text, source, ""); }

  public static String[] match(String text, String source, String flags) {
    Matcher matcher = re(source, flags).matcher(text);
    if (!matcher.find()) return null;
    String[] groups = new String[matcher.groupCount() + 1];
    for (int i = 0; i <= matcher.groupCount(); i++) groups[i] = matcher.group(i);
    return groups;
  }

  /** All matches (String.prototype.match with g). */
  public static List<String> matchAll(String text, String source) {
    Matcher matcher = re(source).matcher(text);
    List<String> result = new ArrayList<>();
    while (matcher.find()) result.add(matcher.group());
    return result;
  }

  /** String.prototype.split(regex) semantics for zero-width separators (keeps trailing pieces). */
  public static List<String> split(String text, Pattern pattern) {
    List<String> parts = new ArrayList<>();
    Matcher matcher = pattern.matcher(text);
    int start = 0;
    while (matcher.find()) {
      if (matcher.end() == matcher.start() && matcher.start() == start) continue;
      if (matcher.start() >= text.length()) break;
      parts.add(text.substring(start, matcher.start()));
      start = matcher.end();
    }
    parts.add(text.substring(start));
    return parts;
  }

  /** Unicode NFD, then NFC. */
  public static String nfd(String value) { return Normalizer.normalize(value, Normalizer.Form.NFD); }

  public static String nfc(String value) { return Normalizer.normalize(value, Normalizer.Form.NFC); }

  public static String lower(String value) { return value.toLowerCase(Locale.ROOT); }

  public static String padStart(String value, int length, char fill) {
    StringBuilder builder = new StringBuilder();
    for (int i = value.length(); i < length; i++) builder.append(fill);
    return builder.append(value).toString();
  }

  /** String.prototype.slice(0, n) by UTF-16 units. */
  public static String slice(String value, int end) { return value.length() <= end ? value : value.substring(0, end); }

  /** String.prototype.trim (JavaScript whitespace and line terminators). */
  public static String trim(String value) {
    int start = 0;
    int end = value.length();
    while (start < end && isJsSpace(value.charAt(start))) start++;
    while (end > start && isJsSpace(value.charAt(end - 1))) end--;
    return value.substring(start, end);
  }

  public static String trimEnd(String value) {
    int end = value.length();
    while (end > 0 && isJsSpace(value.charAt(end - 1))) end--;
    return value.substring(0, end);
  }

  private static boolean isJsSpace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r' || c == ' ' || c == ' '
      || (c >= ' ' && c <= ' ') || c == ' ' || c == ' ' || c == ' ' || c == ' ' || c == '　' || c == '﻿';
  }

  /** Number.prototype.toString for finite values used in messages and keys. */
  public static String numberToString(double value) {
    if (Double.isNaN(value)) return "NaN";
    if (Double.isInfinite(value)) return value > 0 ? "Infinity" : "-Infinity";
    if (value == Math.rint(value) && Math.abs(value) < 1e21) return BigDecimal.valueOf(value).toBigInteger().toString();
    String text = Double.toString(value);
    if (text.contains("E")) {
      BigDecimal decimal = new BigDecimal(text);
      if (Math.abs(value) >= 1e-6) return decimal.stripTrailingZeros().toPlainString();
    }
    return text;
  }

  /** JavaScript Number(string) coercion. */
  public static double toNumber(String text) {
    if (text == null) return Double.NaN;
    String value = trim(text);
    if (value.isEmpty()) return 0;
    switch (value) {
      case "Infinity", "+Infinity": return Double.POSITIVE_INFINITY;
      case "-Infinity": return Double.NEGATIVE_INFINITY;
      default: break;
    }
    String lower = value.toLowerCase(Locale.ROOT);
    try {
      if (lower.startsWith("0x")) return Long.parseLong(value.substring(2), 16);
      if (lower.startsWith("0o")) return Long.parseLong(value.substring(2), 8);
      if (lower.startsWith("0b")) return Long.parseLong(value.substring(2), 2);
    } catch (NumberFormatException error) { return Double.NaN; }
    if (!value.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")) return Double.NaN;
    return Double.parseDouble(value);
  }
}
