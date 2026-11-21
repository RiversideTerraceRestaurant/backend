package com.menumaestro.backend.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.menumaestro.backend.config.AppConfig;

class CloudinaryClientTest {
  @Test
  void signsSortedParametersWithTheSecret() {
    // Example from Cloudinary's "Generating authentication signatures" documentation.
    assertEquals("bfd09f95f331f558cbd1320e67aa8d488770583e",
      CloudinaryClient.sign(Map.of("timestamp", "1315060510", "public_id", "sample_image", "eager", "w_400,h_300,c_pad|w_260,h_200,c_crop"), "abcd"));
  }

  @Test
  void extractsPublicIdsOnlyForOwnRtrImages() {
    assertEquals("rtr/menu/abc", CloudinaryClient.publicIdFromUrl("https://res.cloudinary.com/demo/image/upload/v1712/rtr/menu/abc.webp", "demo"));
    assertEquals("rtr/tables/x-1", CloudinaryClient.publicIdFromUrl("https://res.cloudinary.com/demo/image/upload/rtr/tables/x-1.webp?x=1", "demo"));
    assertNull(CloudinaryClient.publicIdFromUrl("https://res.cloudinary.com/other/image/upload/v1/rtr/menu/abc.webp", "demo"));
    assertNull(CloudinaryClient.publicIdFromUrl("https://res.cloudinary.com/demo/image/upload/v1/samples/cat.jpg", "demo"));
    assertNull(CloudinaryClient.publicIdFromUrl("https://example.supabase.co/storage/v1/object/public/menu-images/a.webp", "demo"));
    assertNull(CloudinaryClient.publicIdFromUrl(null, "demo"));
  }

  @Test
  void readsCloudinaryUrlOrSeparateVariables() {
    AppConfig.Cloudinary fromUrl = AppConfig.cloudinary(key -> "CLOUDINARY_URL".equals(key) ? "cloudinary://123:s3cr3t@my-cloud" : null);
    assertEquals(new AppConfig.Cloudinary("my-cloud", "123", "s3cr3t"), fromUrl);
    assertTrue(fromUrl.configured());
    AppConfig.Cloudinary missing = AppConfig.cloudinary(key -> "CLOUDINARY_CLOUD_NAME".equals(key) ? "x" : null);
    assertFalse(missing.configured());
    assertFalse(new CloudinaryClient(missing).owns("https://res.cloudinary.com/x/image/upload/rtr/menu/a.webp"));
  }
}
