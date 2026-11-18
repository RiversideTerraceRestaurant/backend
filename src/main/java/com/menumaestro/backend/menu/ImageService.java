package com.menumaestro.backend.menu;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.springframework.stereotype.Service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.menumaestro.backend.common.json.Json;
import com.menumaestro.backend.common.web.ApiException;
import com.menumaestro.backend.supabase.PostgrestResult;
import com.menumaestro.backend.supabase.SupabaseClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Menu image pipeline: decode (JPEG/PNG/WebP/GIF/BMP/TIFF, HEIC/AVIF via libheif when
 * installed), auto-orient from EXIF, produce a 1200px WebP (q80) and a 400px thumbnail
 * (q72), and upload both to the menu-images bucket.
 */
@Service
public class ImageService {
  public static final String BUCKET = "menu-images";
  private static final long MAX_PIXELS = 40_000_000L;
  private static final List<String> HEIF_BRANDS = List.of("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif", "avis");
  private static final String DECODE_ERROR = "Cannot decode this image or it exceeds 40 megapixels. Please try a smaller JPEG, PNG, WebP, AVIF or HEIC image.";

  static {
    ImageIO.setUseCache(false);
    ImageIO.scanForPlugins();
  }

  public record Encoded(byte[] full, byte[] thumbnail) {}

  /** Decodes and encodes both WebP renditions; throws the original 400 error on any decode failure. */
  public Encoded encode(byte[] bytes) {
    try {
      BufferedImage image = orient(decode(bytes), orientation(bytes));
      return new Encoded(webp(resizeInside(image, 1200), 0.80f), webp(resizeInside(image, 400), 0.72f));
    } catch (Exception | LinkageError error) {
      throw new ApiException(400, DECODE_ERROR);
    }
  }

  public ObjectNode uploadImages(SupabaseClient client, byte[] bytes) {
    Encoded encoded = encode(bytes);
    List<String> names = List.of(UUID.randomUUID() + ".webp", UUID.randomUUID() + "-thumb.webp");
    try {
      // Sequential uploads let us roll back a partial upload deterministically.
      client.upload(BUCKET, names.get(0), encoded.full(), "image/webp", "31536000").unwrap();
      client.upload(BUCKET, names.get(1), encoded.thumbnail(), "image/webp", "31536000").unwrap();
    } catch (RuntimeException error) {
      client.remove(BUCKET, names);
      throw error;
    }
    return Json.obj("imageUrl", client.publicUrl(BUCKET, names.get(0)), "thumbnailUrl", client.publicUrl(BUCKET, names.get(1)));
  }

  /** Re-encodes a stored image when it is not an optimized WebP with a thumbnail. Returns null when skipped. */
  public ObjectNode optimizeItem(SupabaseClient client, JsonNode item, String supabaseUrl) {
    String imageUrl = Json.str(item, "image_url");
    if (imageUrl == null || imageUrl.isEmpty()) return null;
    String prefix = supabaseUrl + "/storage/v1/object/public/" + BUCKET + "/";
    // Only read this project's own storage; never fetch arbitrary image URLs on the server.
    if (!imageUrl.startsWith(prefix)) return null;
    String path = decodeURIComponent(imageUrl.substring(prefix.length()).split("\\?", -1)[0]);
    byte[] blob = client.download(BUCKET, path);
    if (blob == null) throw ApiException.database();
    if (path.endsWith(".webp") && blob.length <= 400 * 1024 && Json.truthy(Json.get(item, "thumbnail_url"))) return null;
    ObjectNode images = uploadImages(client, blob);
    PostgrestResult result = client.from("menu_items")
      .update(Json.obj("image_url", images.get("imageUrl"), "thumbnail_url", images.get("thumbnailUrl")))
      .eq("id", Json.str(item, "id")).execute();
    result.unwrap();
    return images;
  }

  private static String decodeURIComponent(String value) {
    try { return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8); }
    catch (IllegalArgumentException error) { throw new IllegalArgumentException("URI malformed"); }
  }

  // ---------- decoding ----------
  private BufferedImage decode(byte[] bytes) throws Exception {
    String brand = bytes.length >= 12 ? new String(bytes, 8, 4, StandardCharsets.ISO_8859_1) : "";
    if (HEIF_BRANDS.contains(brand)) return decodeWithLibheif(bytes);
    try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new IOException("Unsupported image");
      ImageReader reader = readers.next();
      try {
        reader.setInput(input, true, true);
        long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
        if (pixels > MAX_PIXELS) throw new IOException("Image is too large");
        return reader.read(0);
      } finally {
        reader.dispose();
      }
    }
  }

  /** HEIC/AVIF need libheif's command-line decoder (heif-dec or heif-convert) on the server. */
  private BufferedImage decodeWithLibheif(byte[] bytes) throws Exception {
    Path directory = Files.createTempDirectory("heif");
    try {
      Path source = directory.resolve("input.heic");
      Path target = directory.resolve("output.png");
      Files.write(source, bytes);
      for (String tool : List.of("heif-dec", "heif-convert")) {
        try {
          Process process = new ProcessBuilder(tool, source.toString(), target.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
          if (process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0 && Files.exists(target)) {
            byte[] png = Files.readAllBytes(target);
            BufferedImage image = decode(png);
            if ((long) image.getWidth() * image.getHeight() > MAX_PIXELS) throw new IOException("Image is too large");
            return image;
          }
          process.destroyForcibly();
        } catch (IOException missing) { /* Try the next tool. */ }
      }
      throw new IOException("HEIF decoder unavailable");
    } finally {
      try (var files = Files.list(directory)) { files.forEach(file -> file.toFile().delete()); }
      Files.deleteIfExists(directory);
    }
  }

  private static int orientation(byte[] bytes) {
    try {
      Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));
      ExifIFD0Directory exif = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
      if (exif != null && exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) return exif.getInt(ExifIFD0Directory.TAG_ORIENTATION);
    } catch (Exception | LinkageError ignored) { /* No EXIF. */ }
    return 1;
  }

  /** Applies EXIF orientation like sharp().rotate(). */
  static BufferedImage orient(BufferedImage source, int orientation) {
    BufferedImage image = toArgb(source);
    if (orientation <= 1 || orientation > 8) return image;
    int width = image.getWidth();
    int height = image.getHeight();
    boolean swap = orientation >= 5;
    int outWidth = swap ? height : width;
    BufferedImage result = new BufferedImage(outWidth, swap ? width : height, BufferedImage.TYPE_INT_ARGB);
    int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
    int[] out = new int[pixels.length];
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        int targetX;
        int targetY;
        switch (orientation) {
          case 2 -> { targetX = width - 1 - x; targetY = y; }
          case 3 -> { targetX = width - 1 - x; targetY = height - 1 - y; }
          case 4 -> { targetX = x; targetY = height - 1 - y; }
          case 5 -> { targetX = y; targetY = x; }
          case 6 -> { targetX = height - 1 - y; targetY = x; }
          case 7 -> { targetX = height - 1 - y; targetY = width - 1 - x; }
          default -> { targetX = y; targetY = width - 1 - x; }
        }
        out[targetY * outWidth + targetX] = pixels[y * width + x];
      }
    }
    result.setRGB(0, 0, outWidth, swap ? width : height, out, 0, outWidth);
    return result;
  }

  private static BufferedImage toArgb(BufferedImage image) {
    if (image.getType() == BufferedImage.TYPE_INT_ARGB) return image;
    BufferedImage converted = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = converted.createGraphics();
    graphics.setComposite(AlphaComposite.Src);
    graphics.drawImage(image, 0, 0, null);
    graphics.dispose();
    return converted;
  }

  /** fit: inside, withoutEnlargement. Multi-step downscaling keeps quality close to Lanczos. */
  static BufferedImage resizeInside(BufferedImage image, int box) {
    int width = image.getWidth();
    int height = image.getHeight();
    double scale = Math.min(1, Math.min((double) box / width, (double) box / height));
    int targetWidth = Math.max(1, (int) Math.round(width * scale));
    int targetHeight = Math.max(1, (int) Math.round(height * scale));
    BufferedImage current = image;
    while (current.getWidth() / 2 >= targetWidth && current.getHeight() / 2 >= targetHeight) {
      current = scaled(current, current.getWidth() / 2, current.getHeight() / 2, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }
    if (current.getWidth() != targetWidth || current.getHeight() != targetHeight) {
      current = scaled(current, targetWidth, targetHeight, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }
    return current;
  }

  private static BufferedImage scaled(BufferedImage image, int width, int height, Object interpolation) {
    BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = result.createGraphics();
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    graphics.setComposite(AlphaComposite.Src);
    graphics.drawImage(image, 0, 0, width, height, null);
    graphics.dispose();
    return result;
  }

  private static byte[] webp(BufferedImage image, float quality) throws IOException {
    Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType("image/webp");
    if (!writers.hasNext()) throw new IOException("WebP encoder unavailable");
    ImageWriter writer = writers.next();
    try {
      ImageWriteParam param = writer.getDefaultWriteParam();
      param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      String[] types = param.getCompressionTypes();
      for (String type : types) if (type.toLowerCase().contains("lossy")) { param.setCompressionType(type); break; }
      param.setCompressionQuality(quality);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(output)) {
        writer.setOutput(stream);
        writer.write(null, new IIOImage(image, null, null), param);
      }
      return output.toByteArray();
    } finally {
      writer.dispose();
    }
  }
}
