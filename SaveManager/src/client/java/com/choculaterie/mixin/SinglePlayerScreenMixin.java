package com.choculaterie.mixin;

import com.choculaterie.SaveManagerMod;
import com.choculaterie.gui.AccountLinkingScreen;
import com.choculaterie.network.NetworkManager;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Mixin(SelectWorldScreen.class)
public abstract class SinglePlayerScreenMixin extends Screen {
    protected SinglePlayerScreenMixin(Text title) { super(title); }

    // Vanilla search field; we anchor our buttons next to it (same as your icon).
    @Shadow private TextFieldWidget searchBox;

    @Unique private ButtonWidget saveManagerLinkBtn; // small icon
    @Unique private ButtonWidget uploadSaveBtn;      // "Upload Save" action
    @Unique private ButtonWidget cloudSavesBtn;

    @Unique private static final int ICON_SIZE = 20;
    @Unique private static final int GAP = 4;

    @Unique private static final String CONFIG_FILE = "save-manager-settings.json";
    @Unique private static final String KEY_MATERIAL = "SaveManagerSecKey.v1";
    @Unique private static final byte[] AES_KEY = sm$deriveKey(KEY_MATERIAL);
    @Unique private static final int GCM_TAG_BITS = 128;

    // Only inject into init; avoid render/tick mixin signature issues
    @Inject(method = "init", at = @At("RETURN"))
    private void savemanager$init(CallbackInfo ci) {
        // Small link button next to the search field (👁/💾 style anchor)
        this.saveManagerLinkBtn = ButtonWidget.builder(
                Text.literal("⚙"),
                b -> this.client.setScreen(new AccountLinkingScreen((Screen)(Object)this))
        ).dimensions(0, 0, ICON_SIZE, ICON_SIZE).build();
        this.addDrawableChild(this.saveManagerLinkBtn);

        // Upload button placed just to the right of the icon; enabled only with a selected world
        this.uploadSaveBtn = ButtonWidget.builder(
                Text.literal("\uD83D\uDCBE"),
                b -> sm$startUploadSelectedWorld()
        ).dimensions(0, 0, 20, ICON_SIZE).build();
        this.uploadSaveBtn.active = false;
        this.addDrawableChild(this.uploadSaveBtn);

        this.cloudSavesBtn = ButtonWidget.builder(
                        Text.literal("\uD83D\uDCC1"),
                        b -> this.client.setScreen(new com.choculaterie.gui.CloudSaveManagerScreen((Screen)(Object)this)))
                .dimensions(0, 0, 20, ICON_SIZE).build();
        this.addDrawableChild(this.cloudSavesBtn);

        // Per-frame lightweight updater: reposition buttons and toggle enabled state
        this.addDrawable((Drawable) this::savemanager$frameUpdate);
    }

    @Unique
    private void savemanager$frameUpdate(DrawContext ctx, int mouseX, int mouseY, float delta) {
        savemanager$repositionButtons();
        if (this.uploadSaveBtn != null) {
            this.uploadSaveBtn.active = sm$hasSelection();
        }
    }

    @Unique
    private void savemanager$repositionButtons() {
        // Default fallback
        int x = 6, y = 6, h = ICON_SIZE, w = 0;

        try {
            if (this.searchBox != null) {
                // Try getters first (present on most mappings)
                try { x = (int) this.searchBox.getClass().getMethod("getX").invoke(this.searchBox); } catch (Throwable ignored) {}
                try { y = (int) this.searchBox.getClass().getMethod("getY").invoke(this.searchBox); } catch (Throwable ignored) {}
                try { w = (int) this.searchBox.getClass().getMethod("getWidth").invoke(this.searchBox); } catch (Throwable ignored) {}
                try { h = (int) this.searchBox.getClass().getMethod("getHeight").invoke(this.searchBox); } catch (Throwable ignored) {}
                // Fallback to fields
                try { Field f = this.searchBox.getClass().getDeclaredField("x"); f.setAccessible(true); x = f.getInt(this.searchBox); } catch (Throwable ignored) {}
                try { Field f = this.searchBox.getClass().getDeclaredField("y"); f.setAccessible(true); y = f.getInt(this.searchBox); } catch (Throwable ignored) {}
                if (w == 0) { try { Field f = this.searchBox.getClass().getDeclaredField("width"); f.setAccessible(true); w = f.getInt(this.searchBox); } catch (Throwable ignored) {} }
                try { Field f = this.searchBox.getClass().getDeclaredField("height"); f.setAccessible(true); h = f.getInt(this.searchBox); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        // Place the small icon right-aligned to the search box with a small gap
        int iconX = x + w + GAP;
        int iconY = y + Math.max(0, (h - ICON_SIZE) / 2);

        if (this.saveManagerLinkBtn != null) {
            try { this.saveManagerLinkBtn.setPosition(iconX, iconY); } catch (Throwable ignored) {}
            this.saveManagerLinkBtn.visible = true;
            this.saveManagerLinkBtn.active = true;
        }

        // Place the "Upload Save" button to the right of the icon
        if (this.uploadSaveBtn != null) {
            int uploadX = iconX + ICON_SIZE + GAP;
            int uploadY = iconY;
            try { this.uploadSaveBtn.setPosition(uploadX, uploadY); } catch (Throwable ignored) {}
            this.uploadSaveBtn.visible = true;
        }

        if (this.cloudSavesBtn != null) {
            int cloudX = iconX + ICON_SIZE + GAP + 20 + GAP; // after Upload button
            int cloudY = iconY;
            try { this.cloudSavesBtn.setPosition(cloudX, cloudY); } catch (Throwable ignored) {}
            this.cloudSavesBtn.visible = true;
        }
    }

    // ==== Upload implementation ====

    @Unique
    private boolean sm$hasSelection() {
        WorldListWidget list = sm$getWorldList();
        if (list == null) return false;
        try { return WorldListWidget.class.getMethod("getSelectedOrNull").invoke(list) != null; } catch (Throwable ignored) {}
        try { return WorldListWidget.class.getMethod("getSelected").invoke(list) != null; } catch (Throwable ignored) {}
        return false;
    }

    @Unique
    private void sm$startUploadSelectedWorld() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;

        String apiKey = sm$loadApiKey(mc);
        if (apiKey == null || apiKey.isBlank()) {
            SaveManagerMod.LOGGER.warn("SaveManager: no API key configured");
            mc.execute(() -> mc.setScreen(new AccountLinkingScreen((Screen) (Object) this)));
            return;
        }

        WorldListWidget list = sm$getWorldList();
        if (list == null) return;

        Object entry = sm$getSelectedEntry(list);
        if (entry == null) return;

        String dirName = sm$getWorldDirNameFromEntry(entry);
        String worldName = sm$getWorldDisplayNameFromEntry(entry);
        if (dirName == null || dirName.isBlank()) {
            SaveManagerMod.LOGGER.warn("SaveManager: could not resolve world directory name");
            return;
        }

        final String uploadName = (worldName == null || worldName.isBlank()) ? dirName : worldName;
        final Path finalWorldDir = mc.runDirectory.toPath().resolve("saves").resolve(dirName);
        if (!Files.isDirectory(finalWorldDir)) {
            SaveManagerMod.LOGGER.warn("SaveManager: world dir does not exist: {}", finalWorldDir);
            return;
        }

        final NetworkManager nm = new NetworkManager();
        nm.setApiKey(apiKey);

        CompletableFuture.runAsync(() -> {
            Path zip = null;
            try {
                zip = sm$zipWorld(finalWorldDir, uploadName);
                SaveManagerMod.LOGGER.info("SaveManager: uploading {} ({} bytes)", uploadName, Files.size(zip));
                nm.uploadWorldSave(uploadName, zip).join();
                SaveManagerMod.LOGGER.info("SaveManager: upload completed");
            } catch (Exception e) {
                SaveManagerMod.LOGGER.error("SaveManager: upload failed", e);
            } finally {
                if (zip != null) {
                    try { Files.deleteIfExists(zip); } catch (Exception ignored) {}
                }
            }
        });
    }

    @Unique
    private WorldListWidget sm$getWorldList() {
        try {
            Class<?> c = this.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (!f.getType().getName().equals("net.minecraft.client.gui.screen.world.WorldListWidget")) continue;
                    f.setAccessible(true);
                    Object v = f.get(this);
                    if (v instanceof WorldListWidget wl) return wl;
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @Unique
    private Object sm$getSelectedEntry(WorldListWidget list) {
        try { return WorldListWidget.class.getMethod("getSelectedOrNull").invoke(list); } catch (Throwable ignored) {}
        try { return WorldListWidget.class.getMethod("getSelected").invoke(list); } catch (Throwable ignored) {}
        return null;
    }

    @Unique
    private String sm$getWorldDirNameFromEntry(Object entry) {
        Object summary = sm$getFieldByNames(entry, "level", "summary");
        if (summary == null) return null;
        String id = sm$invokeString(summary, "getLevelId");
        if (id == null) id = sm$invokeString(summary, "getFileName");
        if (id == null) id = sm$invokeString(summary, "getDirectoryName");
        if (id == null) id = sm$invokeString(summary, "getName");
        return id;
    }

    @Unique
    private String sm$getWorldDisplayNameFromEntry(Object entry) {
        Object summary = sm$getFieldByNames(entry, "level", "summary");
        if (summary == null) return null;
        String name = sm$invokeString(summary, "getName");
        if (name == null) name = sm$invokeString(summary, "getLevelName");
        return name;
    }

    @Unique
    private static Object sm$getFieldByNames(Object target, String... names) {
        for (String n : names) {
            try {
                Field f = target.getClass().getDeclaredField(n);
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    @Unique
    private static Object sm$invoke(Object target, String method) {
        try {
            var m = target.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable ignored) {}
        return null;
    }

    @Unique
    private static String sm$invokeString(Object target, String method) {
        Object v = sm$invoke(target, method);
        return v == null ? null : String.valueOf(v);
    }

    @Unique
    private static Path sm$zipWorld(Path worldDir, String worldName) throws Exception {
        Path zip = Files.createTempFile("savemanager-" + worldName + "-", ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip, StandardOpenOption.WRITE))) {
            final Path base = worldDir;
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        Path rel = base.relativize(file);
                        // Skip transient lock files if present
                        if ("session.lock".equalsIgnoreCase(rel.getFileName().toString())) return FileVisitResult.CONTINUE;
                        ZipEntry entry = new ZipEntry(rel.toString().replace('\\', '/'));
                        zos.putNextEntry(entry);
                        Files.copy(file, zos);
                        zos.closeEntry();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return zip;
    }

    @Unique
    private static byte[] sm$deriveKey(String material) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Unique
    private static String sm$loadApiKey(MinecraftClient client) {
        try {
            File configDir = new File(client.runDirectory, "config");
            File configFile = new File(configDir, CONFIG_FILE);
            if (!configFile.exists()) return null;

            try (FileReader reader = new FileReader(configFile)) {
                JsonObject json = new Gson().fromJson(reader, JsonObject.class);
                if (json == null) return null;
                if (json.has("encryptedApiToken")) {
                    return sm$decrypt(json.get("encryptedApiToken").getAsString());
                }
                if (json.has("apiToken")) {
                    return json.get("apiToken").getAsString();
                }
            }
        } catch (Exception e) {
            SaveManagerMod.LOGGER.error("SaveManager: failed to load API key", e);
        }
        return null;
    }

    @Unique
    private static String sm$decrypt(String base64) throws Exception {
        byte[] data = Base64.getDecoder().decode(base64);
        if (data.length < 12 + 16) throw new IllegalArgumentException("Invalid encrypted payload");
        byte[] iv = new byte[12];
        byte[] ct = new byte[data.length - 12];
        System.arraycopy(data, 0, iv, 0, 12);
        System.arraycopy(data, 12, ct, 0, ct.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec keySpec = new SecretKeySpec(AES_KEY, "AES");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] pt = cipher.doFinal(ct);
        return new String(pt, StandardCharsets.UTF_8);
    }
}