package com.menumaestro.backend.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import com.menumaestro.backend.common.json.Js;
import com.menumaestro.backend.common.json.Json;
import com.menumaestro.backend.common.validation.Schema;
import com.menumaestro.backend.common.validation.Z;
import com.menumaestro.backend.common.web.ApiException;
import com.menumaestro.backend.menu.CatalogService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The restaurant knowledge Markdown file with optimistic-version saves, serialized and atomic. */
public class KnowledgeStore {
  public static final Path DEFAULT_PATH = Path.of("knowledge", "restaurant.md");
  public static final Schema UPDATE = Z.object()
    .field("content", Z.string().trim().min(1).max(24000))
    .field("version", Z.string().regex("^[a-f0-9]{64}$", null))
    .strict();
  private static final Pattern SECTION_SPLIT = Js.re("(?=^##\\s)", "m");

  public record Document(String content, String version) {
    public ObjectNode toJson() { return Json.obj("content", content, "version", version); }
  }

  public record Section(String id, String title, String content) {}

  private final Path path;
  private final ReentrantLock queue = new ReentrantLock(true);

  public KnowledgeStore(Path path) { this.path = path == null ? DEFAULT_PATH : path; }

  public Path path() { return path; }

  static String versionOf(String content) { return CatalogService.sha256(content); }

  public static List<Section> sections(String content) {
    List<Section> sections = new ArrayList<>();
    List<String> parts = Js.split(content, SECTION_SPLIT);
    for (int index = 0; index < parts.size(); index++) {
      String part = parts.get(index);
      String[] title = Js.match(part, "^#+\\s+(.+)", "m");
      String text = Js.trim(part);
      String heading = title != null && title[1] != null ? Js.trim(title[1]) : "";
      if (!text.isEmpty()) sections.add(new Section("section-" + (index + 1), heading.isEmpty() ? "Restaurant information" : heading, text));
    }
    return sections;
  }

  public Document read() {
    String content;
    try { content = Files.readString(path, StandardCharsets.UTF_8); }
    catch (IOException error) { throw new IllegalStateException("ENOENT: no such file or directory, open '" + path + "'", error); }
    if (content.length() > 24000) throw new ApiException(503, "Knowledge base exceeds 24,000 characters. Please shorten it in the source file.");
    return new Document(content, versionOf(content));
  }

  public Document save(JsonNode input) {
    queue.lock();
    try {
      JsonNode next = UPDATE.parse(input);
      String content = Json.str(next, "content");
      Document current = read();
      if (!current.version().equals(Json.str(next, "version"))) throw new ApiException(409, "The knowledge base changed. Reload it before saving your edits.");
      Path parent = path.toAbsolutePath().getParent();
      Files.createDirectories(parent);
      Path temporary = Path.of(path + "." + UUID.randomUUID() + ".tmp");
      try {
        Files.writeString(temporary, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try { Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { /* Non-POSIX file system. */ }
        try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException error) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
      } finally {
        Files.deleteIfExists(temporary);
      }
      return new Document(content, versionOf(content));
    } catch (IOException error) {
      throw new IllegalStateException(error);
    } finally {
      queue.unlock();
    }
  }
}
