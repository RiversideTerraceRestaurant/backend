package com.menumaestro.backend.push;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.menumaestro.backend.auth.AuthService;
import com.menumaestro.backend.common.web.RateLimiter;
import com.menumaestro.backend.common.web.RequestBodies;
import com.menumaestro.backend.common.web.Responses;

import jakarta.servlet.http.HttpServletRequest;

/** Admin web-push subscription management. */
@RestController
@RequestMapping("/api/admin/push")
public class PushController {
  private final PushService push;
  private final RateLimiter limiter;

  public PushController(PushService push, RateLimiter limiter) {
    this.push = push;
    this.limiter = limiter;
  }

  @GetMapping("/config")
  public ResponseEntity<String> config() { return Responses.ok(push.config()); }

  @PostMapping("/subscriptions")
  public ResponseEntity<String> subscribe(HttpServletRequest request) {
    return Responses.created(push.subscribe(PushService.SUBSCRIPTION.parse(RequestBodies.json(request, 8192)),
      AuthService.currentAdmin(request).userId(), request.getHeader("user-agent")));
  }

  @DeleteMapping("/subscriptions")
  public ResponseEntity<String> unsubscribe(HttpServletRequest request) {
    return Responses.ok(push.unsubscribe(PushService.ENDPOINT.parse(RequestBodies.json(request, 8192))));
  }

  @PostMapping("/test")
  public ResponseEntity<String> test(HttpServletRequest request) {
    limiter.limit("push-test:" + AuthService.currentAdmin(request).userId(), 5);
    return Responses.ok(push.test());
  }
}
