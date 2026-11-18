package com.menumaestro.backend.knowledge;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.menumaestro.backend.common.json.Js;
import com.menumaestro.backend.common.json.Json;

/**
 * Applies the approved restaurant contact facts (email and owner) to the active knowledge
 * file exactly once, guarded by a marker file so later admin edits survive deployments
 * (formerly deployment/update-restaurant-contact.mjs).
 */
@Component
@Order(1)
public class ContactMigration implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(ContactMigration.class);
  static final String EMAIL = "litalianoriverside@gmail.com";
  static final String OWNER = "Mr. Darin";

  private final KnowledgeStore store;

  public ContactMigration(KnowledgeStore store) { this.store = store; }

  public static String updatedRestaurantContact(String content) {
    List<String> parts = new java.util.ArrayList<>(Js.split(content, Js.re("(?=^##\\s)", "m")));
    int index = -1;
    for (int i = 0; i < parts.size(); i++) if (Js.re("^##[^\\n]*(?:liên hệ|contact)", "iu").matcher(parts.get(i)).find()) { index = i; break; }
    String facts = "Email: " + EMAIL + ".\nChủ nhà hàng: " + OWNER + ".";
    if (index < 0) return Js.trimEnd(content) + "\n\n## Địa chỉ và liên hệ\n" + facts + "\n";
    String part = parts.get(index);
    if (part.contains("Email: " + EMAIL + ".") && part.contains("Chủ nhà hàng: " + OWNER + ".")) return content;
    parts.set(index, Js.trimEnd(Js.replaceAll(part, Js.re("^(?:[-*]\\s*)?(?:Email|E-mail|Chủ nhà hàng|Owner)\\s*:[^\\n]*(?:\\n|$)", "gmi"), "")) + "\n" + facts + "\n\n");
    return String.join("", parts);
  }

  /** Returns true when the file changed. */
  public boolean migrate() throws IOException {
    Path marker = Path.of(store.path() + ".contact-v1.done");
    if (Files.exists(marker)) return false;
    KnowledgeStore.Document current = store.read();
    String content = updatedRestaurantContact(current.content());
    if (!content.equals(current.content())) store.save(Json.obj("version", current.version(), "content", content));
    try { Files.writeString(marker, "restaurant-contact-v1\n", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); }
    catch (FileAlreadyExistsException ignored) { /* Another instance finished first. */ }
    return !content.equals(current.content());
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      migrate();
      log.info("Restaurant contact knowledge is up to date.");
    } catch (Exception error) {
      log.warn("Restaurant contact migration skipped: {}", error.getMessage());
    }
  }
}
