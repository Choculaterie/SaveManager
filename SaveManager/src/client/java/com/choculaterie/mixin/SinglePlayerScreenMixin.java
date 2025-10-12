package com.choculaterie.mixin;

import com.choculaterie.SaveManagerMod;
import com.choculaterie.gui.AccountLinkingScreen;
import com.choculaterie.network.NetworkManager;
import com.choculaterie.util.SelectedWorld;
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
import net.minecraft.client.gui.screen.ConfirmScreen;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Mixin(SelectWorldScreen.class)
public abstract class SinglePlayerScreenMixin extends Screen {
    protected SinglePlayerScreenMixin(Text title) { super(title); }

    // Vanilla search field; we anchor our buttons next to it (same as your icon).
    @Shadow protected TextFieldWidget searchBox;

    @Unique private ButtonWidget saveManagerLinkBtn; // small icon
    @Unique private ButtonWidget uploadSaveBtn;      // "Upload Save" action
    @Unique private ButtonWidget cloudSavesBtn;

    @Unique private static final int ICON_SIZE = 20;
    @Unique private static final int GAP = 4;

    @Unique private static final String CONFIG_FILE = "save-manager-settings.json";
    @Unique private static final String KEY_MATERIAL = "SaveManagerSecKey.v1";
    @Unique private static final byte[] AES_KEY = sm$deriveKey(KEY_MATERIAL);
    @Unique private static final int GCM_TAG_BITS = 128;

    @Unique private final NetworkManager savemanager$net = new NetworkManager();

    // Upload UI / progress state (mirrors CloudSaveManagerScreen ActiveOp shape)
    @Unique private static volatile boolean upActive = false;
    @Unique private static volatile boolean upZipping = false;
    @Unique private static volatile long upUploaded = 0L;
    @Unique private static volatile long upTotal = -1L;
    @Unique private static volatile long upStartNanos = 0L;
    @Unique private static volatile long upLastTickNanos = 0L;
    @Unique private static volatile long upLastBytes = 0L;
    @Unique private static volatile double upSpeedBps = 0.0;

    @Unique
    private static boolean sm$isUploading() { return upActive; }

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
                b -> savemanager$confirmThenUploadSelected()
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
            // Disable upload button while zipping/uploading
            this.uploadSaveBtn.active = sm$hasSelection() && !sm$isUploading();
        }

        // Draw overlay status when uploading/zipping
        if (sm$isUploading()) {
            int cx = this.width / 2;
            int baseY = this.height - 60;
            int statusY = baseY;
            if (upZipping) {
                // Draw "Zipping..." and spinner similar to CloudSaveManagerScreen
                ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Zipping..."), cx, statusY, 0xFFFFFFFF);

                int radius = 8;
                int dots = 12;
                int cx0 = this.width / 2;
                int cy0 = statusY + 24;
                long t = System.currentTimeMillis();
                int head = (int)((t / 100) % dots);
                for (int i = 0; i < dots; i++) {
                    double ang = (2 * Math.PI * i) / dots;
                    int dx = (int)Math.round(Math.cos(ang) * radius);
                    int dy = (int)Math.round(Math.sin(ang) * radius);
                    int x = cx0 + dx;
                    int y = cy0 + dy;
                    int dist = (i - head + dots) % dots;
                    int alpha = switch (dist) {
                        case 0 -> 0xFF;
                        case 1 -> 0xCC;
                        case 2 -> 0x99;
                        case 3 -> 0x66;
                        default -> 0x33;
                    };
                    int col = (alpha << 24) | 0xCCCCCC;
                    ctx.fill(x - 1, y - 1, x + 2, y + 2, col);
                }
            } else {
                // Upload progress bar phase
                long uploaded = upUploaded;
                long total = upTotal;
                double speed = upSpeedBps;

                if (total > 0 && uploaded >= 0) {
                    int barW = 360;
                    int barH = 8;
                    int bx = cx - (barW / 2);
                    int by = statusY + 14;

                    ctx.fill(bx, by, bx + barW, by + barH, 0xFF444444);
                    double frac = Math.min(1.0, (double) uploaded / total);
                    int filled = (int) (barW * frac);
                    ctx.fill(bx, by, bx + filled, by + barH, 0xFFCCCCCC);

                    int pct = (int) Math.min(100, Math.floor((uploaded * 100.0) / total));
                    ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(pct + "%"), cx, by - 10, 0xFFFFFFFF);

                    String info = formatBytes(uploaded) + " / " + formatBytes(total);
                    if (speed > 1) {
                        String sp = formatBytes((long) speed) + "/s";
                        long remaining = Math.max(0L, total - uploaded);
                        long etaSec = Math.max(0L, (long) Math.ceil(remaining / Math.max(1.0, speed)));
                        info += " • " + sp + " • ETA " + formatDurationShort(etaSec);
                    }
                    ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(info), cx, by + barH + 2, 0xFFCCCCCC);
                } else {
                    // Unknown total -> spinner with "Uploading..."
                    ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Uploading..."), cx, statusY, 0xFFFFFFFF);
                    int radius = 8;
                    int dots = 12;
                    int cx0 = this.width / 2;
                    int cy0 = statusY + 24;
                    long t = System.currentTimeMillis();
                    int head = (int)((t / 100) % dots);
                    for (int i = 0; i < dots; i++) {
                        double ang = (2 * Math.PI * i) / dots;
                        int dx = (int)Math.round(Math.cos(ang) * radius);
                        int dy = (int)Math.round(Math.sin(ang) * radius);
                        int x = cx0 + dx;
                        int y = cy0 + dy;
                        int dist = (i - head + dots) % dots;
                        int alpha = switch (dist) {
                            case 0 -> 0xFF;
                            case 1 -> 0xCC;
                            case 2 -> 0x99;
                            case 3 -> 0x66;
                            default -> 0x33;
                        };
                        int col = (alpha << 24) | 0xCCCCCC;
                        ctx.fill(x - 1, y - 1, x + 2, y + 2, col);
                    }
                }
            }
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

    @Unique
    private boolean sm$hasSelection() {
        return sm$getSelectedWorld() != null;
    }

    @Unique
    private SelectedWorld sm$getSelectedWorld() {
        try {
            WorldListWidget list = sm$getWorldList();
            if (list == null) return null;

            Object entry = sm$getSelectedEntry(list);
            if (entry == null) return null;

            String dirName = sm$getWorldDirNameFromEntry(entry);
            String display = sm$getWorldDisplayNameFromEntry(entry);
            if (display == null) display = "";

            // Resolve path using client runDirectory -> saves/<dirName>
            if (dirName != null && !dirName.isEmpty()) {
                Path candidate = this.client.runDirectory.toPath().resolve("saves").resolve(dirName);
                if (Files.exists(candidate)) {
                    return new SelectedWorld(candidate, display);
                }
            }

            // Fallback: try to find a folder in saves/ that matches the display name or sanitized variant
            try {
                Path saves = this.client.runDirectory.toPath().resolve("saves");
                if (Files.exists(saves) && Files.isDirectory(saves)) {
                    String displaySan = (display == null) ? "" : display.trim();
                    for (Path p : Files.newDirectoryStream(saves)) {
                        if (!Files.isDirectory(p)) continue;
                        String fn = p.getFileName().toString();
                        if (dirName != null && dirName.equals(fn)) return new SelectedWorld(p, display);
                        if (!displaySan.isEmpty() && (fn.equals(displaySan) || fn.equalsIgnoreCase(displaySan))) return new SelectedWorld(p, display);
                        // loose san: replace illegal chars
                        String loose = displaySan.replaceAll("[\\\\/:*?\"<>|]+", "_");
                        if (!loose.isEmpty() && fn.equals(loose)) return new SelectedWorld(p, display);
                    }
                }
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            SaveManagerMod.LOGGER.warn("(savemanager) sm$getSelectedWorld reflection error", t);
        }
        return null;
    }


    @Unique
    private WorldListWidget sm$getWorldList() {
        try {
            // First try declared fields on the concrete class and superclasses
            Class<?> c = this.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    Class<?> t = f.getType();
                    if (t == null) continue;
                    if (WorldListWidget.class.isAssignableFrom(t)) {
                        f.setAccessible(true);
                        Object val = f.get(this);
                        if (val != null) return (WorldListWidget) val;
                    }
                }
                c = c.getSuperclass();
            }

            // Also try SelectWorldScreen.class directly (mappings may differ)
            Class<?> sw = SelectWorldScreen.class;
            for (Field f : sw.getDeclaredFields()) {
                if (WorldListWidget.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object val = f.get((Object)this);
                    if (val != null) return (WorldListWidget) val;
                }
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
        if (target == null) return null;
        Class<?> c = target.getClass();

        // Exact-name search up the class hierarchy
        for (String n : names) {
            Class<?> cur = c;
            while (cur != null && cur != Object.class) {
                try {
                    Field f = cur.getDeclaredField(n);
                    f.setAccessible(true);
                    try {
                        Object v = f.get(target);
                        if (v != null) return v;
                    } catch (IllegalAccessException ignored) {
                        // if access still fails, continue searching
                    }
                } catch (NoSuchFieldException ignored) {
                    // try superclass
                }
                cur = cur.getSuperclass();
            }
        }

        // Looser name-contains search if exact names didn't match
        for (String n : names) {
            Class<?> cur = c;
            while (cur != null && cur != Object.class) {
                for (Field f : cur.getDeclaredFields()) {
                    try {
                        if (f.getName().toLowerCase().contains(n.toLowerCase())) {
                            f.setAccessible(true);
                            try {
                                Object v = f.get(target);
                                if (v != null) return v;
                            } catch (IllegalAccessException ignored) {
                                // continue trying other fields
                            }
                        }
                    } catch (Throwable ignored) {
                        // ignore other reflection issues and continue
                    }
                }
                cur = cur.getSuperclass();
            }
        }

        return null;
    }

    @Unique
    private void savemanager$uploadWithNameCheck(final Path zipFinal, final String origWorldName, final NetworkManager savemanager$net) {
        SaveManagerMod.LOGGER.info("(savemanager) Querying cloud save names for upload: {}", origWorldName);

        // Reusable upload action as a lambda so we can call it from multiple branches
        Runnable doUpload = () -> {
            try {
                // Transition out of zipping phase into uploading phase
                upZipping = false;
                upActive = true;
                try { this.client.execute(() -> {}); } catch (Throwable ignored) {}

                // Use the new overload with progress callback so we can update UI
                savemanager$net.uploadWorldSave(origWorldName, zipFinal, (sent, total) -> {
                    // Update upload state
                    upUploaded = Math.max(0L, sent);
                    if (total > 0) upTotal = total;

                    long now = System.nanoTime();
                    long dtNs = now - upLastTickNanos;
                    long dBytes = upUploaded - upLastBytes;
                    if (dtNs > 50_000_000L) {
                        double instBps = dBytes > 0 ? (dBytes * 1_000_000_000.0) / dtNs : 0.0;
                        double alpha = 0.2;
                        upSpeedBps = upSpeedBps <= 0 ? instBps : (alpha * instBps + (1 - alpha) * upSpeedBps);
                        upLastTickNanos = now;
                        upLastBytes = upUploaded;
                    }

                    // ensure UI updates on client thread
                    try { this.client.execute(() -> {}); } catch (Throwable ignored) {}
                }).whenComplete((json, uploadErr) -> {
                    try { Files.deleteIfExists(zipFinal); } catch (Throwable ignored) {}

                    // Clear upload state on client thread
                    this.client.execute(() -> {
                        upActive = false;
                        upZipping = false;
                        upUploaded = 0L;
                        upTotal = -1L;
                        upStartNanos = 0L;
                        upLastBytes = 0L;
                        upLastTickNanos = 0L;
                        upSpeedBps = 0.0;

                        if (uploadErr != null) {
                            SaveManagerMod.LOGGER.error("(savemanager) Upload failed for \"{}\"", origWorldName, uploadErr);
                            this.client.setScreen(new ConfirmScreen(b -> this.client.setScreen((SelectWorldScreen)(Object)this),
                                    Text.literal("Upload Failed"),
                                    Text.literal("Upload failed: " + safeErrMessage(uploadErr))));
                        } else {
                            SaveManagerMod.LOGGER.info("(savemanager) Upload succeeded for \"{}\"", origWorldName);
                            // No success prompt per request — return to world list
                            this.client.setScreen((SelectWorldScreen)(Object)this);
                        }
                    });
                });
            } catch (Throwable t) {
                try { Files.deleteIfExists(zipFinal); } catch (Throwable ignored) {}
                SaveManagerMod.LOGGER.error("(savemanager) Exception starting upload for \"{}\"", origWorldName, t);
                this.client.execute(() -> this.client.setScreen(new ConfirmScreen(b -> this.client.setScreen((SelectWorldScreen)(Object)this),
                        Text.literal("Upload Failed"),
                        Text.literal("Upload failed: " + safeErrMessage(t)))));
                // Ensure state cleared
                upActive = false;
                upZipping = false;
            }
        };

        // Query existing names
        savemanager$net.listWorldSaveNames().whenComplete((names, namesErr) -> {
            if (namesErr != null) {
                SaveManagerMod.LOGGER.warn("(savemanager) Failed to fetch name list; proceeding to upload for \"{}\": {}", origWorldName, safeErrMessage(namesErr));
                doUpload.run();
                return;
            }

            boolean exists = false;
            String sanitized = sanitizeFolderName(origWorldName);
            if (names != null) {
                for (String n : names) {
                    if (n == null) continue;
                    if (n.equalsIgnoreCase(origWorldName) || (!sanitized.isEmpty() && n.equalsIgnoreCase(sanitized))) {
                        exists = true;
                        break;
                    }
                }
            }

            if (!exists) {
                SaveManagerMod.LOGGER.info("(savemanager) Name not present in cloud; uploading \"{}\" immediately", origWorldName);
                doUpload.run();
                return;
            }

            // Name exists -> prompt user to confirm overwrite
            this.client.execute(() -> {
                Text title = Text.literal("Overwrite Cloud Save?");
                Text message = Text.literal("A save named \"" + origWorldName + "\" already exists in the cloud. Overwrite?");
                this.client.setScreen(new ConfirmScreen(confirmed -> {
                    if (!confirmed) {
                        // Cancelled: return to world list and clean up temp zip
                        try { Files.deleteIfExists(zipFinal); } catch (Throwable ignored) {}
                        // Clear upload state (user cancelled)
                        upActive = false;
                        upZipping = false;
                        this.client.setScreen((SelectWorldScreen)(Object)this);
                        return;
                    }
                    // Confirmed -> upload
                    doUpload.run();
                }, title, message));
            });
        });
    }

    @Unique
    private static String sanitizeFolderName(String s) {
        if (s == null) return "";
        String clean = s.trim().replaceAll("[\\\\/:*?\"<>|]+", "_");
        return clean.isBlank() ? "" : clean;
    }

    @Unique
    private static Object sm$invoke(Object target, String method) {
        if (target == null) return null;
        Class<?> c = target.getClass();
        while (c != null && c != Object.class) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod(method);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                return null;
            }
            c = c.getSuperclass();
        }
        // try common no-arg methods with different common names
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(method);
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

    @Unique
    private void savemanager$confirmThenUploadSelected() {
        try {
            SelectedWorld sel = sm$getSelectedWorld();
            if (sel == null) {
                SaveManagerMod.LOGGER.warn("(savemanager) Upload canceled: no world selected");
                return;
            }

            String apiKey = sm$loadApiKey(this.client);
            if (apiKey == null || apiKey.isBlank()) {
                SaveManagerMod.LOGGER.warn("(savemanager) Upload canceled: no API key configured");
                return;
            }
            savemanager$net.setApiKey(apiKey);

            String worldName = sel.getDisplayName();
            Path worldDir = sel.getDir();
            if (worldDir == null || !Files.exists(worldDir)) {
                SaveManagerMod.LOGGER.warn("(savemanager) Upload canceled: world directory not found: {}", sel);
                return;
            }

            // Mark zipping/uploading state BEFORE zipping so UI shows spinner
            upActive = true;
            upZipping = true;
            upUploaded = 0L;
            upTotal = -1L;
            upStartNanos = System.nanoTime();
            upLastTickNanos = upStartNanos;
            upLastBytes = 0L;
            upSpeedBps = 0.0;
            try { this.client.execute(() -> {}); } catch (Throwable ignored) {}

            // Zip the world
            Path zip = null;
            try {
                zip = sm$zipWorld(worldDir, worldName == null ? "world" : worldName.replaceAll("[\\\\/:*?\"<>|]+", "_"));
            } catch (Exception e) {
                SaveManagerMod.LOGGER.error("(savemanager) Failed zipping world", e);
                // Clear state and show error
                upActive = false;
                upZipping = false;
                this.client.execute(() -> this.client.setScreen(new ConfirmScreen(b -> this.client.setScreen((SelectWorldScreen)(Object)this),
                        Text.literal("Upload Failed"), Text.literal("Failed to prepare world zip"))));
                return;
            }

            // Upload asynchronously
            final Path zipFinal = zip;
            try {
                // Use the upload path that first queries cloud save names and prompts on conflicts
                savemanager$uploadWithNameCheck(zipFinal, worldName, savemanager$net);
            } catch (Throwable e) {
                try { Files.deleteIfExists(zipFinal); } catch (Throwable ignored) {}
                SaveManagerMod.LOGGER.error("(savemanager) Upload errored", e);
                upActive = false;
                upZipping = false;
                this.client.execute(() -> this.client.setScreen(new ConfirmScreen(b -> this.client.setScreen((SelectWorldScreen)(Object)this),
                        Text.literal("Upload Failed"), Text.literal("Upload error: " + safeErrMessage(e)))));
            }
        } catch (Throwable t) {
            SaveManagerMod.LOGGER.error("(savemanager) Unexpected error in upload flow", t);
            upActive = false;
            upZipping = false;
        }
    }

    @Unique
    private static String safeErrMessage(Throwable t) {
        if (t == null) return "Unknown";
        String m = t.getMessage();
        if (m == null || m.isBlank()) return t.getClass().getSimpleName();
        String one = m.split("\n", 2)[0];
        if (one.length() > 200) one = one.substring(0, 200) + "...";
        return one;
    }

    @Unique
    private static String savemanager$getString(JsonObject obj, String... names) {
        for (String n : names) {
            if (!obj.has(n)) continue;
            var e = obj.get(n);
            if (e == null || e.isJsonNull()) continue;
            if (e.isJsonPrimitive()) {
                var p = e.getAsJsonPrimitive();
                if (p.isString()) return p.getAsString();
                if (p.isNumber()) return String.valueOf(p.getAsLong());
                if (p.isBoolean()) return String.valueOf(p.getAsBoolean());
            } else if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                String s = savemanager$getString(o, "text");
                if (!s.isEmpty()) return s;
                s = savemanager$getString(o, "value");
                if (!s.isEmpty()) return s;
                s = savemanager$getString(o, "iso");
                if (!s.isEmpty()) return s;
            }
        }
        return "";
    }

    @Unique
    private static String formatDurationShort(long seconds) {
        if (seconds <= 0) return "0s";
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) return String.format("%dh %02dm", h, m);
        if (m > 0) return String.format("%dm %02ds", m, s);
        return String.format("%ds", s);
    }

    @Unique
    private static String formatBytes(long b) {
        if (b < 1024) return b + " B";
        int unit = 1024;
        int exp = (int) (Math.log(b) / Math.log(unit));
        String pre = "KMGTPE".charAt(Math.min(exp - 1, 5)) + "";
        return String.format("%.1f %sB", b / Math.pow(unit, exp), pre);
    }
}
