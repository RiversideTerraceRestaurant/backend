package com.menumaestro.backend.validation;

import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.menumaestro.backend.exception.ValidationException;

import tools.jackson.databind.JsonNode;

/**
 * A declarative request schema with the same parsing rules and error messages as the
 * zod schemas of the original API (defaults, transforms, strict objects, coercion).
 * Java {@code null} input/output means "undefined".
 */
public abstract class Schema {
  enum Status { VALID, DIRTY, ABORTED }

  record Parsed(JsonNode value, Status status) {
    static Parsed ok(JsonNode value) { return new Parsed(value, Status.VALID); }
    static Parsed dirty(JsonNode value) { return new Parsed(value, Status.DIRTY); }
    static final Parsed INVALID = new Parsed(null, Status.ABORTED);
    boolean aborted() { return status == Status.ABORTED; }
    boolean valid() { return status == Status.VALID; }
  }

  protected abstract Parsed run(JsonNode input, ParseContext ctx);

  /** Parses or throws {@link ValidationException} with zod-formatted issues. */
  public JsonNode parse(JsonNode input) {
    ParseContext ctx = new ParseContext();
    Parsed result = run(input, ctx);
    if (!ctx.issues().isEmpty() || result.aborted()) throw new ValidationException(ctx.issues());
    return result.value();
  }

  /** Parses, returning null instead of throwing (safeParse().success ? data : undefined). */
  public JsonNode parseOrNull(JsonNode input) {
    try { return parse(input); } catch (ValidationException error) { return null; }
  }

  public Schema optional() { return Z.wrap(this, (input, ctx) -> input == null ? Parsed.ok(null) : run(input, ctx)); }

  public Schema nullable() { return Z.wrap(this, (input, ctx) -> input != null && input.isNull() ? Parsed.ok(input) : run(input, ctx)); }

  public Schema nullish() { return nullable().optional(); }

  public Schema withDefault(Supplier<JsonNode> value) { return Z.wrap(this, (input, ctx) -> run(input == null ? value.get() : input, ctx)); }

  /** zod .catch(value): any failure yields the fallback without reporting issues. value may return null (undefined). */
  public Schema catchValue(Supplier<JsonNode> value) {
    return Z.wrap(this, (input, ctx) -> {
      ParseContext isolated = ctx.isolated();
      Parsed result = run(input, isolated);
      return result.valid() && isolated.issues().isEmpty() ? result : Parsed.ok(value.get());
    });
  }

  public Schema transform(Function<JsonNode, JsonNode> transform) {
    return Z.wrap(this, (input, ctx) -> {
      Parsed result = run(input, ctx);
      return result.valid() ? Parsed.ok(transform.apply(result.value())) : result;
    });
  }

  public Schema refine(Predicate<JsonNode> check, String message) {
    return Z.wrap(this, (input, ctx) -> {
      Parsed result = run(input, ctx);
      if (result.aborted()) return result;
      if (!check.test(result.value())) {
        ctx.issue(message == null ? "Invalid input" : message, false);
        return Parsed.dirty(result.value());
      }
      return result;
    });
  }
}
