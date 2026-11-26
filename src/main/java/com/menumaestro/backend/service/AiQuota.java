package com.menumaestro.backend.service;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.stereotype.Component;

import com.menumaestro.backend.config.AppConfig;
import com.menumaestro.backend.exception.ApiException;

/** Process-wide daily AI request budget (UTC day), shared by chat, translation and maintenance. */
@Component
public class AiQuota {
  private final int dailyLimit;
  private String date = "";
  private int count;

  public AiQuota(AppConfig config) { this.dailyLimit = config.dailyLimit(); }

  public synchronized void use() {
    String today = LocalDate.now(ZoneOffset.UTC).toString();
    if (!today.equals(date)) { date = today; count = 0; }
    if (++count > dailyLimit) throw new ApiException(429, "AI daily limit reached. Please use the menu filters.");
  }
}
