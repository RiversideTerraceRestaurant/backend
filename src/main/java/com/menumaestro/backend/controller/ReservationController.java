package com.menumaestro.backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.menumaestro.backend.dto.ReservationSchemas;
import com.menumaestro.backend.security.ClientIp;
import com.menumaestro.backend.security.RateLimiter;
import com.menumaestro.backend.service.ReservationService;
import com.menumaestro.backend.util.Json;
import com.menumaestro.backend.util.RequestBodies;
import com.menumaestro.backend.util.Responses;

import jakarta.servlet.http.HttpServletRequest;

/** Guest booking endpoints: tables, availability, best-table suggestion and email OTP verification. */
@RestController
@RequestMapping("/api/reservations")
public class ReservationController {
  private final ReservationService service;
  private final RateLimiter limiter;
  private final ClientIp clientIp;

  public ReservationController(ReservationService service, RateLimiter limiter, ClientIp clientIp) {
    this.service = service;
    this.limiter = limiter;
    this.clientIp = clientIp;
  }

  @GetMapping("/tables")
  public ResponseEntity<String> tables(HttpServletRequest request) {
    String area = request.getParameter("area");
    return Responses.ok(service.tables(area == null || area.isEmpty() ? null : ReservationSchemas.AVAILABILITY.shape("area").parse(Json.node(area)).stringValue()));
  }

  @GetMapping("/availability")
  public ResponseEntity<String> availability(HttpServletRequest request) {
    limiter.limit("availability:" + clientIp.of(request), 90);
    return Responses.ok(service.availability(ReservationSchemas.AVAILABILITY.parse(ReservationSchemas.fromQuery(request))));
  }

  @GetMapping("/suggest")
  public ResponseEntity<String> suggest(HttpServletRequest request) {
    limiter.limit("availability:" + clientIp.of(request), 90);
    return Responses.ok(service.suggest(ReservationSchemas.SUGGEST.parse(ReservationSchemas.fromQuery(request))));
  }

  @PostMapping("/request-otp")
  public ResponseEntity<String> requestOtp(HttpServletRequest request) {
    String ip = clientIp.of(request);
    limiter.limit("booking-otp:" + ip, 5);
    limiter.limit("booking-otp-hour:" + ip, 20, 3600000);
    return Responses.created(service.requestOtp(ReservationSchemas.REQUEST_OTP.parse(RequestBodies.json(request))));
  }

  @PostMapping("/resend-otp")
  public ResponseEntity<String> resendOtp(HttpServletRequest request) {
    String ip = clientIp.of(request);
    limiter.limit("booking-otp:" + ip, 5);
    limiter.limit("booking-otp-hour:" + ip, 20, 3600000);
    return Responses.ok(service.resendOtp(ReservationSchemas.RESEND_OTP.parse(RequestBodies.json(request))));
  }

  @PostMapping("/verify-otp")
  public ResponseEntity<String> verifyOtp(HttpServletRequest request) {
    limiter.limit("booking-verify:" + clientIp.of(request), 10);
    return Responses.ok(service.verifyOtp(ReservationSchemas.VERIFY_OTP.parse(RequestBodies.json(request))));
  }
}
