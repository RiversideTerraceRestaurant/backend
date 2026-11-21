package com.menumaestro.backend.mail;

import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

import com.menumaestro.backend.common.json.Json;
import com.menumaestro.backend.config.AppConfig;

import jakarta.mail.internet.MimeMessage;
import tools.jackson.databind.JsonNode;

/**
 * SMTP mailer for booking emails. Without SMTP settings, development logs messages
 * (including the OTP); production reports itself as unconfigured so booking is refused.
 */
public interface Mailer {
  boolean configured();

  void send(String kind, JsonNode booking, Map<String, String> extra) throws Exception;

  static Mailer create(AppConfig config) {
    Logger log = LoggerFactory.getLogger(Mailer.class);
    AppConfig.Mail mail = config.mail();
    if (mail.host().isEmpty()) {
      if (config.production()) {
        return new Mailer() {
          public boolean configured() { return false; }
          public void send(String kind, JsonNode booking, Map<String, String> extra) { throw new IllegalStateException("Email is not configured."); }
        };
      }
      return new Mailer() {
        public boolean configured() { return true; }
        public void send(String kind, JsonNode booking, Map<String, String> extra) {
          log.info("[mail:dev] {} -> {} ({}){}", kind, Json.jsString(booking.get("customerEmail")), Json.jsString(booking.get("bookingCode")),
            extra.get("otp") != null ? " OTP=" + extra.get("otp") : "");
        }
      };
    }
    JavaMailSenderImpl sender = new JavaMailSenderImpl();
    sender.setHost(mail.host());
    sender.setPort(mail.port());
    sender.setDefaultEncoding("UTF-8");
    Properties properties = sender.getJavaMailProperties();
    if (!mail.user().isEmpty()) {
      sender.setUsername(mail.user());
      sender.setPassword(mail.password());
      properties.put("mail.smtp.auth", "true");
    }
    if (mail.secure()) {
      sender.setProtocol("smtps");
      properties.put("mail.smtps.ssl.enable", "true");
      if (!mail.user().isEmpty()) properties.put("mail.smtps.auth", "true");
    } else {
      // nodemailer upgrades plain connections with STARTTLS when the server offers it.
      properties.put("mail.smtp.starttls.enable", "true");
    }
    properties.put("mail.smtp.connectiontimeout", "120000");
    properties.put("mail.smtp.timeout", "600000");
    properties.put("mail.smtp.writetimeout", "600000");
    String from = mail.from().isEmpty() ? mail.user() : mail.from();
    return new Mailer() {
      public boolean configured() { return !from.isEmpty(); }

      public void send(String kind, JsonNode booking, Map<String, String> extra) throws Exception {
        if (from.isEmpty()) throw new IllegalStateException("Email sender is not configured. Set MAIL_FROM or MAIL_USERNAME.");
        BookingEmailRenderer.Email email = BookingEmailRenderer.render(kind, booking, extra, config.siteUrl());
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
        helper.setFrom(from);
        helper.setTo(Json.jsString(booking.get("customerEmail")));
        helper.setSubject(email.subject());
        helper.setText(email.html(), true);
        sender.send(message);
      }
    };
  }
}
