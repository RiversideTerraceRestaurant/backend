package com.menumaestro.backend.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import com.menumaestro.backend.common.web.ApiException;

class ImageServiceTest {
  private static byte[] png(int width, int height) throws Exception {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    var graphics = image.createGraphics();
    graphics.setColor(Color.RED);
    graphics.fillRect(0, 0, width / 2, height);
    graphics.dispose();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  @Test
  void resizesInsideTheBoxWithoutEnlargement() {
    assertEquals(1200, ImageService.resizeInside(new BufferedImage(4000, 3000, BufferedImage.TYPE_INT_ARGB), 1200).getWidth());
    assertEquals(900, ImageService.resizeInside(new BufferedImage(4000, 3000, BufferedImage.TYPE_INT_ARGB), 1200).getHeight());
    assertEquals(300, ImageService.resizeInside(new BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB), 1200).getWidth());
  }

  @Test
  void appliesExifOrientation() {
    BufferedImage image = new BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB);
    image.setRGB(0, 0, 0xFFFF0000);
    BufferedImage rotated = ImageService.orient(image, 6);
    assertEquals(2, rotated.getWidth());
    assertEquals(4, rotated.getHeight());
    assertEquals(0xFFFF0000, rotated.getRGB(1, 0));
  }

  @Test
  void rejectsUndecodableUploads() {
    ApiException error = assertThrows(ApiException.class, () -> new ImageService().encode("not an image".getBytes()));
    assertEquals(400, error.status());
  }

  @Test
  @EnabledOnOs(value = OS.LINUX, architectures = {"amd64", "x86_64"})
  void encodesWebpRenditions() throws Exception {
    ImageService.Encoded encoded = new ImageService().encode(png(2400, 1200));
    BufferedImage full = ImageIO.read(new ByteArrayInputStream(encoded.full()));
    BufferedImage thumbnail = ImageIO.read(new ByteArrayInputStream(encoded.thumbnail()));
    assertEquals("RIFF", new String(encoded.full(), 0, 4));
    assertEquals(1200, full.getWidth());
    assertEquals(600, full.getHeight());
    assertEquals(400, thumbnail.getWidth());
    assertEquals(200, thumbnail.getHeight());
  }
}
