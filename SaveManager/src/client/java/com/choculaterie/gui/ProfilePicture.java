package com.choculaterie.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class ProfilePicture {
    private static final int MAX_SIZE = 512;
    private static final Map<String, Identifier> TEXTURES = new ConcurrentHashMap<>();
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private ProfilePicture() {}

    public static Identifier get(String url, int pixelSize) {
        if (url == null || url.isBlank()) return null;
        int size = Math.max(16, Math.min(MAX_SIZE, pixelSize));
        String key = url + "#" + size;
        Identifier texture = TEXTURES.get(key);
        if (texture != null || FAILED.contains(url) || !PENDING.add(key)) return texture;
        CompletableFuture.supplyAsync(() -> fetchSquarePng(url, size)).whenComplete((png, err) -> {
            if (png == null) {
                FAILED.add(url);
                PENDING.remove(key);
                return;
            }
            Minecraft client = Minecraft.getInstance();
            client.execute(() -> {
                try {
                    NativeImage image = NativeImage.read(png);
                    String name = "pfp_" + UUID.randomUUID().toString().replace("-", "");
                    DynamicTexture dynamic = new DynamicTexture(() -> "savemanager:" + name, image);
                    Identifier id = Identifier.parse("savemanager:" + name);
                    client.getTextureManager().register(id, dynamic);
                    dynamic.upload();
                    Identifier previous = TEXTURES.put(key, id);
                    if (previous != null) client.getTextureManager().release(previous);
                } catch (Exception e) {
                    FAILED.add(url);
                } finally {
                    PENDING.remove(key);
                }
            });
        });
        return null;
    }

    public static boolean isLoading(String url) {
        if (url == null) return false;
        for (String key : PENDING) if (key.startsWith(url + "#")) return true;
        return false;
    }

    private static byte[] fetchSquarePng(String url, int size) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "SaveManager")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) return null;
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(response.body()));
            if (source == null) return null;
            int side = Math.min(source.getWidth(), source.getHeight());
            BufferedImage cropped = source.getSubimage((source.getWidth() - side) / 2, (source.getHeight() - side) / 2, side, side);
            BufferedImage square = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = square.createGraphics();
            if (side > size) {
                g.drawImage(cropped.getScaledInstance(size, size, Image.SCALE_AREA_AVERAGING), 0, 0, null);
            } else {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(cropped, 0, 0, size, size, null);
            }
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(square, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
