package com.menumaestro.backend.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BookingBackupJobTest {
  @Test
  void backupRunsAtTheNextConfiguredHour() {
    java.time.ZonedDateTime morning = java.time.ZonedDateTime.parse("2026-11-21T06:30:00+07:00[Asia/Ho_Chi_Minh]");
    assertEquals("2026-11-21T07:00", BookingBackupJob.nextRun(morning, 7).toLocalDateTime().toString());
    assertEquals("2026-11-22T07:00", BookingBackupJob.nextRun(morning.withHour(7), 7).toLocalDateTime().toString());
  }
}
