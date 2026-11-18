package com.menumaestro.backend.common.web;

import java.io.IOException;

import org.springframework.web.HttpMediaTypeException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.menumaestro.backend.auth.AuthService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Maps every failure to the original {error, code?} JSON body and status. */
@RestControllerAdvice
public class ApiExceptionHandler {
  private final AuthService auth;

  public ApiExceptionHandler(AuthService auth) { this.auth = auth; }

  /** Unknown routes (and unsupported methods) are 404; admin paths authenticate first, as before. */
  @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class, HttpMediaTypeException.class})
  public void notFound(HttpServletRequest request, HttpServletResponse response) throws IOException {
    try {
      if (request.getRequestURI().startsWith("/api/admin/") && AuthService.currentAdmin(request) == null) auth.admin(request, response);
      throw new ApiException(404, "API route not found.");
    } catch (RuntimeException error) {
      ErrorResponses.write(request, response, error);
    }
  }

  @ExceptionHandler(Throwable.class)
  public void handle(Throwable error, HttpServletRequest request, HttpServletResponse response) throws IOException {
    Throwable cause = error;
    while ((cause instanceof java.util.concurrent.CompletionException || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) cause = cause.getCause();
    ErrorResponses.write(request, response, cause);
  }
}
