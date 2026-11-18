package com.menumaestro.backend.common.validation;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.menumaestro.backend.common.json.Js;
import com.menumaestro.backend.common.json.Json;
import com.menumaestro.backend.common.validation.Schema.Parsed;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Factory for {@link Schema} instances mirroring the zod API used by the original backend. */
public final class Z {
  private Z() {}

  private static final Pattern EMAIL = Pattern.compile("^(?!\\.)(?!.*\\.\\.)([A-Z0-9_'+\\-.]*)[A-Z0-9_+-]@([A-Z0-9][A-Z0-9\\-]*\\.)+[A-Z]{2,}\\z", Pattern.CASE_INSENSITIVE);
  private static final Pattern UUID = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\z");

  static Schema wrap(Schema inner, BiFunction<JsonNode, ParseContext, Parsed> body) {
    return new Schema() {
      @Override protected Parsed run(JsonNode input, ParseContext ctx) { return body.apply(input, ctx); }
    };
  }

  static String received(JsonNode node) {
    if (node == null) return "undefined";
    if (node.isNull()) return "null";
    if (node.isString()) return "string";
    if (node.isNumber()) return Double.isNaN(node.doubleValue()) ? "nan" : "number";
    if (node.isBoolean()) return "boolean";
    if (node.isArray()) return "array";
    return "object";
  }

  static Parsed invalidType(ParseContext ctx, String expected, JsonNode input) {
    String got = received(input);
    ctx.issue("undefined".equals(got) ? "Required" : "Expected " + expected + ", received " + got, true);
    return Parsed.INVALID;
  }

  // ---------- primitives ----------
  public static StringSchema string() { return new StringSchema(); }

  public static NumberSchema number() { return new NumberSchema(false); }

  /** z.coerce.number() */
  public static NumberSchema coerceNumber() { return new NumberSchema(true); }

  public static Schema bool() {
    return new Schema() {
      @Override protected Parsed run(JsonNode input, ParseContext ctx) {
        return input != null && input.isBoolean() ? Parsed.ok(input) : invalidType(ctx, "boolean", input);
      }
    };
  }

  public static Schema enumOf(String... values) { return enumOf(Arrays.asList(values)); }

  public static Schema enumOf(List<String> values) {
    String expected = values.stream().map(value -> "'" + value + "'").collect(Collectors.joining(" | "));
    return new Schema() {
      @Override protected Parsed run(JsonNode input, ParseContext ctx) {
        if (input == null || !input.isString()) return invalidType(ctx, expected, input);
        if (!values.contains(input.stringValue())) {
          ctx.issue("Invalid enum value. Expected " + expected + ", received '" + input.stringValue() + "'", true);
          return Parsed.INVALID;
        }
        return Parsed.ok(input);
      }
    };
  }

  public static Schema literal(String value) {
    return new Schema() {
      @Override protected Parsed run(JsonNode input, ParseContext ctx) {
        if (input != null && input.isString() && input.stringValue().equals(value)) return Parsed.ok(input);
        ctx.issue("Invalid literal value, expected " + Json.stringify(value), true);
        return Parsed.INVALID;
      }
    };
  }

  /** z.any(): accepts every defined value. */
  public static Schema any() {
    return new Schema() {
      @Override protected Parsed run(JsonNode input, ParseContext ctx) { return Parsed.ok(input); }
    };
  }

  public static ArraySchema array(Schema element) { return new ArraySchema(element); }

  public static ObjectSchema object() { return new ObjectSchema(new LinkedHashMap<>(), false); }

  public static Schema union(Schema... options) {
    return new Schema() {
      @Override protected Parsed run(JsonNode input, ParseContext ctx) {
        Parsed dirty = null;
        ParseContext dirtyCtx = null;
        for (Schema option : options) {
          ParseContext child = ctx.isolated();
          Parsed result = option.run(input, child);
          if (result.valid() && child.issues().isEmpty()) return result;
          if (!result.aborted() && dirty == null) { dirty = result; dirtyCtx = child; }
        }
        if (dirty != null) { ctx.addAll(dirtyCtx.issues()); return dirty; }
        ctx.issue("Invalid input", true);
        return Parsed.INVALID;
      }
    };
  }

  // ---------- string ----------
  public static final class StringSchema extends Schema {
    private final List<Function<String, Object>> checks = new ArrayList<>();

    /** Each check returns the (possibly transformed) string or an error message wrapped in {@link Failure}. */
    private record Failure(String message) {}

    private StringSchema add(Function<String, Object> check) { checks.add(check); return this; }

    public StringSchema trim() { return add(Js::trim); }

    public StringSchema toLowerCase() { return add(value -> value.toLowerCase(java.util.Locale.ROOT)); }

    public StringSchema min(int length) {
      return add(value -> value.length() < length ? new Failure("String must contain at least " + length + " character(s)") : value);
    }

    public StringSchema max(int length) {
      return add(value -> value.length() > length ? new Failure("String must contain at most " + length + " character(s)") : value);
    }

    public StringSchema regex(String jsRegex, String message) {
      Pattern pattern = Js.re(jsRegex);
      return add(value -> pattern.matcher(value).find() ? value : new Failure(message == null ? "Invalid" : message));
    }

    public StringSchema email() { return add(value -> EMAIL.matcher(value).find() ? value : new Failure("Invalid email")); }

    public StringSchema uuid() { return add(value -> UUID.matcher(value).find() ? value : new Failure("Invalid uuid")); }

    /** zod .url(): anything the WHATWG URL constructor accepts (absolute URL with a scheme). */
    public StringSchema url() { return add(value -> isUrl(value) ? value : new Failure("Invalid url")); }

    private static boolean isUrl(String value) {
      try {
        URI uri = new URI(Js.trim(value));
        if (uri.getScheme() == null) return false;
        String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (List.of("http", "https", "ws", "wss", "ftp").contains(scheme)) return uri.getHost() != null && !uri.getHost().isEmpty();
        return true;
      } catch (Exception error) { return false; }
    }

    @Override protected Parsed run(JsonNode input, ParseContext ctx) {
      if (input == null || !input.isString()) return invalidType(ctx, "string", input);
      String value = input.stringValue();
      boolean dirty = false;
      for (Function<String, Object> check : checks) {
        Object result = check.apply(value);
        if (result instanceof Failure failure) { ctx.issue(failure.message(), false); dirty = true; }
        else value = (String) result;
      }
      JsonNode node = Json.F.stringNode(value);
      return dirty ? Parsed.dirty(node) : Parsed.ok(node);
    }
  }

  // ---------- number ----------
  public static final class NumberSchema extends Schema {
    private final boolean coerce;
    private final List<Function<Double, String>> checks = new ArrayList<>();

    NumberSchema(boolean coerce) { this.coerce = coerce; }

    public NumberSchema integer() { checks.add(value -> value == Math.rint(value) ? null : "Expected integer, received float"); return this; }

    public NumberSchema min(double min) { checks.add(value -> value >= min ? null : "Number must be greater than or equal to " + Js.numberToString(min)); return this; }

    public NumberSchema max(double max) { checks.add(value -> value <= max ? null : "Number must be less than or equal to " + Js.numberToString(max)); return this; }

    public NumberSchema positive() { checks.add(value -> value > 0 ? null : "Number must be greater than 0"); return this; }

    public NumberSchema finite() { checks.add(value -> Double.isFinite(value) ? null : "Number must be finite"); return this; }

    @Override protected Parsed run(JsonNode input, ParseContext ctx) {
      double value;
      if (coerce) {
        if (input == null) value = Double.NaN;
        else if (input.isNull()) value = 0;
        else if (input.isBoolean()) value = input.booleanValue() ? 1 : 0;
        else if (input.isNumber()) value = input.doubleValue();
        else if (input.isString()) value = Js.toNumber(input.stringValue());
        else value = Double.NaN;
        if (Double.isNaN(value)) {
          ctx.issue("Expected number, received nan", true);
          return Parsed.INVALID;
        }
      } else {
        if (input == null || !input.isNumber()) return invalidType(ctx, "number", input);
        value = input.doubleValue();
      }
      boolean dirty = false;
      for (Function<Double, String> check : checks) {
        String message = check.apply(value);
        if (message != null) { ctx.issue(message, false); dirty = true; }
      }
      JsonNode node = Json.num(value);
      return dirty ? Parsed.dirty(node) : Parsed.ok(node);
    }
  }

  // ---------- array ----------
  public static final class ArraySchema extends Schema {
    private final Schema element;
    private Integer min;
    private Integer max;

    ArraySchema(Schema element) { this.element = element; }

    public ArraySchema min(int length) { this.min = length; return this; }

    public ArraySchema max(int length) { this.max = length; return this; }

    @Override protected Parsed run(JsonNode input, ParseContext ctx) {
      if (input == null || !input.isArray()) return invalidType(ctx, "array", input);
      boolean dirty = false;
      if (min != null && input.size() < min) { ctx.issue("Array must contain at least " + min + " element(s)", false); dirty = true; }
      if (max != null && input.size() > max) { ctx.issue("Array must contain at most " + max + " element(s)", false); dirty = true; }
      ArrayNode out = Json.arr();
      boolean aborted = false;
      for (int i = 0; i < input.size(); i++) {
        ctx.push(i);
        Parsed result = element.run(input.get(i), ctx);
        ctx.pop();
        if (result.aborted()) aborted = true;
        else { if (result.status() == Schema.Status.DIRTY) dirty = true; out.add(result.value() == null ? Json.F.nullNode() : result.value()); }
      }
      if (aborted) return Parsed.INVALID;
      return dirty ? Parsed.dirty(out) : Parsed.ok(out);
    }
  }

  // ---------- object ----------
  public static final class ObjectSchema extends Schema {
    private final LinkedHashMap<String, Schema> shape;
    private final boolean strict;

    ObjectSchema(LinkedHashMap<String, Schema> shape, boolean strict) {
      this.shape = shape;
      this.strict = strict;
    }

    public ObjectSchema field(String name, Schema schema) {
      LinkedHashMap<String, Schema> next = new LinkedHashMap<>(shape);
      next.put(name, schema);
      return new ObjectSchema(next, strict);
    }

    public ObjectSchema strict() { return new ObjectSchema(shape, true); }

    public ObjectSchema partial() {
      LinkedHashMap<String, Schema> next = new LinkedHashMap<>();
      shape.forEach((key, schema) -> next.put(key, schema.optional()));
      return new ObjectSchema(next, strict);
    }

    public ObjectSchema pick(String... keys) {
      LinkedHashMap<String, Schema> next = new LinkedHashMap<>();
      for (String key : keys) if (shape.containsKey(key)) next.put(key, shape.get(key));
      return new ObjectSchema(next, strict);
    }

    public ObjectSchema extend(ObjectSchema other) {
      LinkedHashMap<String, Schema> next = new LinkedHashMap<>(shape);
      next.putAll(other.shape);
      return new ObjectSchema(next, strict);
    }

    public Schema shape(String key) { return shape.get(key); }

    @Override protected Parsed run(JsonNode input, ParseContext ctx) {
      if (input == null || !input.isObject()) return invalidType(ctx, "object", input);
      ObjectNode out = Json.obj();
      boolean aborted = false;
      boolean dirty = false;
      for (Map.Entry<String, Schema> entry : shape.entrySet()) {
        ctx.push(entry.getKey());
        Parsed result = entry.getValue().run(input.get(entry.getKey()), ctx);
        ctx.pop();
        if (result.aborted()) { aborted = true; continue; }
        if (result.status() == Schema.Status.DIRTY) dirty = true;
        if (result.value() != null) out.set(entry.getKey(), result.value());
      }
      if (strict) {
        List<String> extra = new ArrayList<>();
        for (String key : input.propertyNames()) if (!shape.containsKey(key)) extra.add(key);
        if (!extra.isEmpty()) {
          ctx.issue("Unrecognized key(s) in object: " + extra.stream().map(key -> "'" + key + "'").collect(Collectors.joining(", ")), false);
          dirty = true;
        }
      }
      if (aborted) return Parsed.INVALID;
      return dirty ? Parsed.dirty(out) : Parsed.ok(out);
    }
  }
}
