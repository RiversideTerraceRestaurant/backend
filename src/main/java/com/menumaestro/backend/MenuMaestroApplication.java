package com.menumaestro.backend;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import com.menumaestro.backend.config.AppConfig;
import com.menumaestro.backend.config.EnvFile;

/** Menu Maestro API: menu, RTR AI chat, knowledge base, reservations and admin tools in one monolith. */
@SpringBootApplication
public class MenuMaestroApplication {
  public static void main(String[] args) {
    SpringApplication application = new SpringApplication(MenuMaestroApplication.class);
    // Same configuration source as `node --env-file-if-exists=.env`: ./.env values are defaults,
    // real environment variables win.
    Map<String, Object> defaults = new HashMap<>(EnvFile.read(Path.of(".env")));
    defaults.put("server.port", "${PORT:3001}");
    defaults.put("server.address", "0.0.0.0");
    application.setDefaultProperties(defaults);
    application.run(args);
  }

  @Bean
  AppConfig appConfig(Environment environment) {
    // Raw lookups: secrets containing "${" must never be treated as placeholders.
    return AppConfig.load(key -> {
      if (environment instanceof org.springframework.core.env.ConfigurableEnvironment configurable) {
        for (org.springframework.core.env.PropertySource<?> source : configurable.getPropertySources()) {
          Object value = source.getProperty(key);
          if (value != null) return String.valueOf(value);
        }
        return null;
      }
      return environment.getProperty(key);
    });
  }
}
