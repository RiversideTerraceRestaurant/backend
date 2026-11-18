package com.menumaestro.backend.common.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * JSON helpers with JavaScript semantics. Most API payloads are passed through as
 * {@link JsonNode} trees so field order, nulls and omitted fields match the original
 * Node.js responses exactly. A {@code null} Java reference means "undefined" (absent).
 */
public final class Json {
  public static final JsonMapper MAPPER = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
  public static final JsonNodeFactory F = JsonNodeFactory.instance;
  private static final double MAX_SAFE = 9007199254740991d;

  private Json() {}

  public static ObjectNode obj() { return F.objectNode(); }

  /** Builds an object from key/value pairs, skipping undefined (Java null) values. */
  public static ObjectNode obj(Object... pairs) {
    ObjectNode node = F.objectNode();
    for (int i = 0; i < pairs.length; i += 2) {
      Object value = pairs[i + 1];
      if (value == UNDEFINED) continue;
      node.set((String) pairs[i], node(value));
    }
    return node;
  }

  /** Marker for {@link #obj(Object...)} to omit a field, as JSON.stringify omits undefined. */
  public static final Object UNDEFINED = new Object();

  public static ArrayNode arr() { return F.arrayNode(); }

  public static ArrayNode arr(Collection<?> values) {
    ArrayNode node = F.arrayNode();
    for (Object value : values) node.add(node(value));
    return node;
  }

  public static ArrayNode arrOf(Object... values) { return arr(List.of(values)); }

  /** Converts a Java value to a JSON node. Java null becomes JSON null. */
  public static JsonNode node(Object value) {
    if (value == null) return F.nullNode();
    if (value instanceof JsonNode json) return json;
    if (value instanceof String text) return F.stringNode(text);
    if (value instanceof Boolean bool) return F.booleanNode(bool);
    if (value instanceof Integer || value instanceof Long || value instanceof Short) return F.numberNode(((Number) value).longValue());
    if (value instanceof Number number) return num(number.doubleValue());
    if (value instanceof Map<?, ?> map) {
      ObjectNode node = F.objectNode();
      map.forEach((key, item) -> node.set(String.valueOf(key), node(item)));
      return node;
    }
    if (value instanceof Collection<?> list) return arr(list);
    if (value instanceof Object[] array) return arr(List.of(array));
    return MAPPER.valueToTree(value);
  }

  /** A JavaScript number: integral values serialize without a fraction, like JSON.stringify. */
  public static JsonNode num(double value) {
    if (Double.isNaN(value) || Double.isInfinite(value)) return F.nullNode();
    if (value == Math.rint(value) && Math.abs(value) <= MAX_SAFE) {
      long whole = (long) value;
      if (whole == 0 && 1 / value < 0) return F.numberNode(0L);
      return whole >= Integer.MIN_VALUE && whole <= Integer.MAX_VALUE ? F.numberNode((int) whole) : F.numberNode(whole);
    }
    return F.numberNode(value);
  }

  /** Recursively converts parsed numbers to JavaScript number semantics (doubles, integral without fraction). */
  public static JsonNode normalize(JsonNode node) {
    if (node == null) return null;
    if (node.isObject()) {
      ObjectNode object = (ObjectNode) node;
      for (Map.Entry<String, JsonNode> entry : new ArrayList<>(object.properties())) object.set(entry.getKey(), normalize(entry.getValue()));
      return object;
    }
    if (node.isArray()) {
      ArrayNode array = (ArrayNode) node;
      for (int i = 0; i < array.size(); i++) array.set(i, normalize(array.get(i)));
      return array;
    }
    if (node.isNumber()) {
      if (node.isBigInteger()) {
        BigInteger big = node.bigIntegerValue();
        return num(big.doubleValue());
      }
      if (node.isBigDecimal()) return num(((BigDecimal) node.decimalValue()).doubleValue());
      return num(node.doubleValue());
    }
    return node;
  }

  /** JSON.parse: rejects empty input and trailing garbage. */
  public static JsonNode parse(String text) {
    JsonNode node = MAPPER.readTree(text);
    if (node == null || node.isMissingNode()) throw new IllegalArgumentException("Empty JSON");
    return normalize(node);
  }

  public static JsonNode parse(byte[] bytes) { return parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)); }

  /** JSON.stringify for trees built by this class. */
  public static String stringify(Object value) { return MAPPER.writeValueAsString(node(value)); }

  // ---------- typed reads (undefined/null tolerant) ----------
  public static boolean has(JsonNode node, String key) { return node != null && node.isObject() && node.get(key) != null; }

  public static JsonNode get(JsonNode node, String key) { return node == null || !node.isObject() ? null : node.get(key); }

  public static String str(JsonNode node, String key) {
    JsonNode value = get(node, key);
    return value != null && value.isString() ? value.stringValue() : null;
  }

  public static String str(JsonNode node, String key, String fallback) {
    String value = str(node, key);
    return value == null ? fallback : value;
  }

  /** Nullable number (null when undefined or JSON null). */
  public static Double dbl(JsonNode node, String key) {
    JsonNode value = get(node, key);
    return value != null && value.isNumber() ? value.doubleValue() : null;
  }

  public static boolean bool(JsonNode node, String key) {
    JsonNode value = get(node, key);
    return value != null && truthy(value);
  }

  public static ArrayNode array(JsonNode node, String key) {
    JsonNode value = get(node, key);
    return value != null && value.isArray() ? (ArrayNode) value : null;
  }

  public static ObjectNode object(JsonNode node, String key) {
    JsonNode value = get(node, key);
    return value != null && value.isObject() ? (ObjectNode) value : null;
  }

  public static List<String> strings(JsonNode node, String key) {
    ArrayNode values = array(node, key);
    List<String> result = new ArrayList<>();
    if (values != null) for (JsonNode value : values) result.add(value.isString() ? value.stringValue() : value.toString());
    return result;
  }

  public static List<JsonNode> list(JsonNode array) {
    List<JsonNode> result = new ArrayList<>();
    if (array != null && array.isArray()) array.values().forEach(result::add);
    return result;
  }

  /** JavaScript truthiness of a JSON value (undefined is falsy). */
  public static boolean truthy(JsonNode value) {
    if (value == null || value.isNull() || value.isMissingNode()) return false;
    if (value.isBoolean()) return value.booleanValue();
    if (value.isNumber()) { double number = value.doubleValue(); return number != 0 && !Double.isNaN(number); }
    if (value.isString()) return !value.stringValue().isEmpty();
    return true;
  }

  /** JavaScript String(value) for scalars. */
  public static String jsString(JsonNode value) {
    if (value == null) return "undefined";
    if (value.isNull()) return "null";
    if (value.isString()) return value.stringValue();
    if (value.isNumber()) return Js.numberToString(value.doubleValue());
    if (value.isBoolean()) return String.valueOf(value.booleanValue());
    return value.toString();
  }

  /** Shallow copy, like the JavaScript object spread {...value}. Nested nodes are copied to avoid aliasing. */
  public static ObjectNode copy(JsonNode value) {
    return value != null && value.isObject() ? (ObjectNode) value.deepCopy() : F.objectNode();
  }

  public static ArrayNode strArray(Collection<String> values) {
    ArrayNode node = F.arrayNode();
    values.forEach(node::add);
    return node;
  }
}
