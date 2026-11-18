package com.menumaestro.backend.auth;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Every /api/admin route requires a signed-in admin before the body is read or validated. */
@Component
public class AdminInterceptor implements HandlerInterceptor {
  private final AuthService auth;

  public AdminInterceptor(AuthService auth) { this.auth = auth; }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (request.getAttribute(AuthService.ADMIN_ATTRIBUTE) == null) request.setAttribute(AuthService.ADMIN_ATTRIBUTE, auth.admin(request, response));
    return true;
  }
}
