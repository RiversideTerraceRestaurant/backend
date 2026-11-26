package com.menumaestro.backend.exception;

/** An error with an HTTP status and a guest-safe message (the original HttpError). */
public class ApiException extends RuntimeException {
  private final int status;
  private final String code;
  private long retryAfterMs = -1;

  public ApiException(int status, String message) { this(status, message, null); }

  public ApiException(int status, String message, String code) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public int status() { return status; }

  public String code() { return code; }

  public long retryAfterMs() { return retryAfterMs; }

  public ApiException withRetryAfter(long millis) { this.retryAfterMs = millis; return this; }

  public static ApiException badRequest(String message) { return new ApiException(400, message); }

  public static ApiException notFound(String message) { return new ApiException(404, message); }

  /** Database failures never leak provider details to the client. */
  public static ApiException database() { return new ApiException(502, "Database request failed. Please try again."); }
}
