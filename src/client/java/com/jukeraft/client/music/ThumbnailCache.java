package com.jukeraft.client.music;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ThumbnailCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("jukeraft-thumbnails");

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "jukeraft-thumbnail-loader");
        thread.setDaemon(true);
        return thread;
    });

    private static final ConcurrentHashMap<String, Identifier> READY = new ConcurrentHashMap<>();
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

    private ThumbnailCache() {
    }

    public static Identifier get(String url) {
        if (url == null || url.isBlank() || FAILED.contains(url)) {
            return null;
        }
        Identifier cached = READY.get(url);
        if (cached != null) {
            return cached;
        }
        if (IN_FLIGHT.add(url)) {
            EXECUTOR.submit(() -> load(url));
        }
        return null;
    }

    private static void load(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (compatible; Jukeraft)")
                    .GET().build();
            HttpResponse<byte[]> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                LOGGER.warn("Thumbnail fetch failed ({}): {}", response.statusCode(), url);
                FAILED.add(url);
                IN_FLIGHT.remove(url);
                return;
            }

            NativeImage image = decode(response.body());
            if (image == null) {
                LOGGER.warn("Thumbnail decode failed (unrecognized image format): {}", url);
                FAILED.add(url);
                IN_FLIGHT.remove(url);
                return;
            }

            Minecraft.getInstance().execute(() -> {
                try {
                    DynamicTexture texture = new DynamicTexture(() -> "jukeraft thumbnail", image);
                    Identifier id = Identifier.fromNamespaceAndPath(
                            "jukeraft", "thumb/" + Integer.toHexString(url.hashCode()));
                    Minecraft.getInstance().getTextureManager().register(id, texture);
                    READY.put(url, id);
                } finally {
                    IN_FLIGHT.remove(url);
                }
            });
        } catch (Exception e) {
            LOGGER.warn("Thumbnail load failed: {} ({})", url, e.toString());
            FAILED.add(url);
            IN_FLIGHT.remove(url);
        }
    }

    private static NativeImage decode(byte[] bytes) throws java.io.IOException {
        BufferedImage buffered = ImageIO.read(new ByteArrayInputStream(bytes));
        if (buffered == null) {
            return null;
        }
        int width = buffered.getWidth();
        int height = buffered.getHeight();
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = buffered.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;

                int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                image.setPixelABGR(x, y, abgr);
            }
        }
        return image;
    }
}
