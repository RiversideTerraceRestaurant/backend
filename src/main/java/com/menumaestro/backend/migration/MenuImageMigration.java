package com.menumaestro.backend.migration;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.menumaestro.backend.client.CloudinaryClient;
import com.menumaestro.backend.client.supabase.SupabaseClient;
import com.menumaestro.backend.config.AppConfig;
import com.menumaestro.backend.config.BeansConfig.ServiceDatabase;
import com.menumaestro.backend.service.CatalogService;
import com.menumaestro.backend.service.ImageService;
import com.menumaestro.backend.util.Json;

import tools.jackson.databind.node.ObjectNode;

/**
 * Moves menu photos that still live in Supabase Storage to Cloudinary, in the background after
 * startup. It only touches items whose image URL points at the menu-images bucket, so it is safe to
 * run on every start and finishes quickly once everything has moved. The Supabase files are left in
 * place as a backup.
 */
@Component
public class MenuImageMigration implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(MenuImageMigration.class);

  private final ImageService images;
  private final CloudinaryClient cloudinary;
  private final CatalogService catalog;
  private final AppConfig config;
  private final SupabaseClient db;

  public MenuImageMigration(ImageService images, CloudinaryClient cloudinary, CatalogService catalog, AppConfig config, ServiceDatabase database) {
    this.images = images;
    this.cloudinary = cloudinary;
    this.catalog = catalog;
    this.config = config;
    this.db = database.client();
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!cloudinary.configured() || db == null) return;
    Thread.ofVirtual().name("menu-image-migration").start(this::migrate);
  }

  /** Returns the number of items moved. */
  int migrate() {
    int moved = 0;
    int failed = 0;
    try {
      List<ObjectNode> pending = CatalogService.allItems(db, "id,name,image_url,thumbnail_url").stream()
        .filter(item -> images.storedOnSupabase(item, config.supabaseUrl())).toList();
      if (pending.isEmpty()) return 0;
      log.info(Json.stringify(Json.obj("event", "menu_images.migration_started", "items", pending.size())));
      for (ObjectNode item : pending) {
        try {
          if (images.optimizeItem(db, item, config.supabaseUrl()) != null) moved++;
        } catch (RuntimeException error) {
          failed++;
          log.warn(Json.stringify(Json.obj("event", "menu_images.migration_item_failed", "id", item.get("id"), "error", String.valueOf(error.getMessage()))));
        }
      }
      log.info(Json.stringify(Json.obj("event", "menu_images.migration_finished", "moved", moved, "failed", failed)));
    } catch (RuntimeException error) {
      log.warn(Json.stringify(Json.obj("event", "menu_images.migration_failed", "error", String.valueOf(error.getMessage()))));
    } finally {
      if (moved > 0) catalog.invalidate();
    }
    return moved;
  }
}
