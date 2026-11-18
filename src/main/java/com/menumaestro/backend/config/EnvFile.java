package com.menumaestro.backend.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads a dotenv file like Node's --env-file-if-exists: KEY=value lines, optional quotes,
 * comments, and never overriding variables already present in the process environment.
 */
public final class EnvFile {
  private EnvFile() {}

  public static Map<String, String> read(Path path) {
    Map<String, String> values = new LinkedHashMap<>();
    if (!Files.isRegularFile(path)) return values;
    String content;
    try { content = Files.readString(path, StandardCharsets.UTF_8); }
    catch (IOException error) { return values; }
    String[] lines = content.split("\r?\n");
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].strip();
      if (line.isEmpty() || line.startsWith("#")) continue;
      if (line.startsWith("export ")) line = line.substring(7).strip();
      int equals = line.indexOf('=');
      if (equals <= 0) continue;
      String key = line.substring(0, equals).strip();
      String value = line.substring(equals + 1).strip();
      if (!value.isEmpty() && (value.charAt(0) == '"' || value.charAt(0) == '\'' || value.charAt(0) == '`')) {
        char quote = value.charAt(0);
        StringBuilder builder = new StringBuilder(value.substring(1));
        // Quoted values may span multiple lines until the closing quote.
        while (builder.indexOf(String.valueOf(quote)) < 0 && i + 1 < lines.length) builder.append('\n').append(lines[++i]);
        int end = builder.lastIndexOf(String.valueOf(quote));
        value = end >= 0 ? builder.substring(0, end) : builder.toString();
        if (quote == '"') value = value.replace("\\n", "\n");
      } else {
        int comment = value.indexOf(" #");
        if (comment >= 0) value = value.substring(0, comment).strip();
      }
      values.put(key, value);
    }
    return values;
  }

  /** Environment lookup: real environment first, then the dotenv file. */
  public static java.util.function.Function<String, String> environment(Path dotenv) {
    Map<String, String> file = read(dotenv);
    Map<String, String> process = System.getenv();
    return key -> process.containsKey(key) ? process.get(key) : file.get(key);
  }
}
