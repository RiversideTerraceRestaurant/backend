package com.menumaestro.backend.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** In-process "booking.changed" event bus (admin SSE streams and push alerts). */
public class BookingEvents {
  private static final Logger log = LoggerFactory.getLogger(BookingEvents.class);

  public record Changed(long bookingId, boolean newCustomerBooking) {}

  private final List<Consumer<Changed>> listeners = new CopyOnWriteArrayList<>();

  public void on(Consumer<Changed> listener) { listeners.add(listener); }

  public void off(Consumer<Changed> listener) { listeners.remove(listener); }

  public void emit(Changed event) {
    for (Consumer<Changed> listener : listeners) {
      try { listener.accept(event); }
      catch (RuntimeException error) { log.debug("booking.changed listener failed: {}", error.getMessage()); }
    }
  }
}
