package com.menumaestro.backend.common.web;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.menumaestro.backend.config.AppConfig;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Applied to every request before routing, in the original order: security headers,
 * origin allow-list and CORS, preflight, cross-site check, then the global rate limit.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiGatewayFilter extends OncePerRequestFilter {
  private final AppConfig config;
  private final RateLimiter limiter;
  private final ClientIp clientIp;

  public ApiGatewayFilter(AppConfig config, RateLimiter limiter, ClientIp clientIp) {
    this.config = config;
    this.limiter = limiter;
    this.clientIp = clientIp;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Referrer-Policy", "same-origin");
    String origin = request.getHeader("origin");
    try {
      if (origin != null && !config.origins().contains(origin)) throw new ApiException(403, "Origin not allowed.");
      if (origin != null) {
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Vary", "Origin");
        response.setHeader("Access-Control-Allow-Credentials", "true");
      }
      if ("OPTIONS".equals(request.getMethod())) {
        response.setStatus(204);
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
        response.setHeader("Access-Control-Allow-Methods", "GET,POST,PATCH,DELETE,OPTIONS");
        return;
      }
      if ("cross-site".equals(request.getHeader("sec-fetch-site")) && origin == null) throw new ApiException(403, "Cross-site request rejected.");
      limiter.limit("api:" + clientIp.of(request), 240);
    } catch (ApiException error) {
      ErrorResponses.write(request, response, error);
      return;
    }
    chain.doFilter(request, response);
  }

  @Override
  protected boolean shouldNotFilterAsyncDispatch() { return true; }

  @Override
  protected boolean shouldNotFilterErrorDispatch() { return true; }
}
