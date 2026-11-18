package com.menumaestro.backend.common.web;

import org.springframework.stereotype.Component;

import com.menumaestro.backend.config.AppConfig;

import jakarta.servlet.http.HttpServletRequest;

/** Client address for rate limits. X-Forwarded-For is only trusted when TRUST_PROXY=true. */
@Component
public class ClientIp {
  private final boolean trustProxy;

  public ClientIp(AppConfig config) { this.trustProxy = config.trustProxy(); }

  public String of(HttpServletRequest request) {
    if (!trustProxy) return request.getRemoteAddr();
    String forwarded = request.getHeader("x-forwarded-for");
    String value = forwarded != null && !forwarded.isEmpty() ? forwarded : request.getRemoteAddr();
    String[] parts = value.split(",", -1);
    return parts[parts.length - 1].trim();
  }
}
