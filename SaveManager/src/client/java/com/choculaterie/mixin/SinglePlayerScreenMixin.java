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
    protected SinglePlayerScreenMixin(Text title, Screen parent) { super(title);
        this.parent = parent;
    }

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

    @Unique private volatile boolean sm$pendingZip = false;
    @Unique private volatile boolean sm$zipStarted = false;
    @Unique private Path sm$pendingWorldDir = null;
    @Unique private String sm$pendingWorldName = null;

    @Unique
    private static boolean sm$isUploading() { return upActive; }

    // Bottom action buttons (collected/refreshed per-screen). We store prior visibility/active states
    @Unique private java.util.List<ButtonWidget> sm$bottomButtons = new java.util.ArrayList<>();
    @Unique private java.util.Map<ButtonWidget, Boolean> sm$bottomPrevVisible = new java.util.HashMap<>();
    @Unique private java.util.Map<ButtonWidget, Boolean> sm$bottomPrevActive = new java.util.HashMap<>();
    @Unique private boolean sm$bottomHidden = false;

    @Unique
    private static volatile String sm$apiKey = null;

    @Unique
    private Screen parent;

    @Unique
    private void sm$collectBottomButtons() {
        sm$bottomButtons.clear();

        // Consider anything whose bottom edge is within the last 20px, or top Y in last 70px
        final int topThresholdY = this.height - 70;
        final int bottomThresholdY = this.height - 20;

        try {
            // Scan common Screen lists across mappings
            String[] listFieldNames = new String[] {
                    "drawables", "children", "selectables", "drawableChildren",
                    "renderables", "buttons", "buttonList"
            };
            for (String fname : listFieldNames) {
                Class<?> c = this.getClass();
                while (c != null && c != Object.class) {
                    try {
                        Field f = c.getDeclaredField(fname);
                        f.setAccessible(true);
                        Object val = f.get(this);
                        if (val instanceof java.util.List<?> list) {
                            for (Object o : list) {
                                if (!(o instanceof ButtonWidget b)) continue;

                                // Position (y) and height
                                int by = -1, bh = -1;
                                try { by = (int) b.getClass().getMethod("getY").invoke(b); } catch (Throwable ignored) {}
                                if (by < 0) {
                                    try { Field fy = b.getClass().getDeclaredField("y"); fy.setAccessible(true); by = fy.getInt(b); } catch (Throwable ignored) {}
                                }
                                try { bh = (int) b.getClass().getMethod("getHeight").invoke(b); } catch (Throwable ignored) {}
                                if (bh < 0) {
                                    try { Field fh = b.getClass().getDeclaredField("height"); fh.setAccessible(true); bh = fh.getInt(b); } catch (Throwable ignored) {}
                                }
                                int bottom = (by >= 0 ? by : 0) + Math.max(0, bh);

                                // Label text (best effort)
                                String label = null;
                                try {
                                    Object msg = b.getClass().getMethod("getMessage").invoke(b);
                                    if (msg instanceof net.minecraft.text.Text t) label = t.getString();
                                    else if (msg != null) label = String.valueOf(msg);
                                } catch (Throwable ignored) {}

                                boolean matchesLabel = false;
                                if (label != null) {
                                    String lc = label.toLowerCase();
                                    // Ensure these are always treated as bottom actions
                                    if (lc.contains("create new world")
                                            || lc.contains("play selected world")
                                            || lc.equals("back")
                                            || lc.equals("done")
                                            || lc.equals("cancel")) {
                                        matchesLabel = true;
                                    }
                                }

                                boolean isBottomByPos = (by >= topThresholdY) || (bottom >= bottomThresholdY);
                                if (matchesLabel || isBottomByPos) {
                                    if (!sm$bottomButtons.contains(b)) sm$bottomButtons.add(b);
                                }
                            }
                        }
                    } catch (NoSuchFieldException ignored) {
                        // try superclass
                    }
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignored) {}
    }

    @Unique
    private void sm$hideBottomButtons() {
        if (sm$bottomHidden) return;
        sm$collectBottomButtons(); // refresh every time

        for (ButtonWidget b : sm$bottomButtons) {
            try {
                sm$bottomPrevVisible.put(b, b.visible);
                sm$bottomPrevActive.put(b, b.active);
                b.visible = false;
                b.active = false;
            } catch (Throwable ignored) {}
        }
        sm$bottomHidden = true;
    }

    @Unique
    private void sm$restoreBottomButtons() {
        if (!sm$bottomHidden) return;
        sm$collectBottomButtons(); // operate on current instances

        for (ButtonWidget b : sm$bottomButtons) {
            try {
                Boolean prevV = sm$bottomPrevVisible.get(b);
                Boolean prevA = sm$bottomPrevActive.get(b);
                b.visible = (prevV == null) ? true : prevV;
                b.active = (prevA == null) ? b.active : prevA;
            } catch (Throwable ignored) {}
        }
        sm$bottomPrevVisible.clear();
        sm$bottomPrevActive.clear();
        sm$bottomHidden = false;
    }


    @Inject(method = "init", at = @At("RETURN"))
    private void savemanager$init(CallbackInfo ci) {
        this.parent = (Screen)(Object)this;

        // Settings icon removed from SelectWorldScreen; it now lives in CloudSaveManagerScreen.

        this.uploadSaveBtn = ButtonWidget.builder(
                Text.literal("\uD83D\uDCBE"),
                b -> savemanager$confirmThenUploadSelected()
        ).dimensions(0, 0, 20, ICON_SIZE).build();
        this.uploadSaveBtn.active = false;
        this.addDrawableChild(this.uploadSaveBtn);

        this.cloudSavesBtn = ButtonWidget.builder(
                Text.literal("\uD83D\uDCC1"),
                b -> {
                    String apiKey = sm$loadApiKey(this.client);
                    if (apiKey == null || apiKey.isBlank()) {
                        Screen rootParent = sm$resolveWorldRootParent((Screen)(Object)this);
                        this.client.setScreen(new com.choculaterie.gui.AccountLinkingScreen(rootParent));
                    } else {
                        sm$apiKey = apiKey;
                        try { this.savemanager$net.setApiKey(apiKey); } catch (Throwable ignored) {}
                        this.client.setScreen(new com.choculaterie.gui.CloudSaveManagerScreen((Screen)(Object)this));
                    }
                }
        ).dimensions(0, 0, 20, ICON_SIZE).build();
        this.addDrawableChild(this.cloudSavesBtn);

        this.addDrawable((Drawable) this::savemanager$frameUpdate);
        try { savemanager$repositionButtons(); } catch (Throwable ignored) {}

        try {
            sm$collectBottomButtons();
            if (sm$isUploading()) {
                if (this.uploadSaveBtn != null) this.uploadSaveBtn.active = false;
                sm$hideBottomButtons();
            }
        } catch (Throwable ignored) {}
    }


    @Unique
    private void savemanager$frameUpdate(DrawContext ctx, int mouseX, int mouseY, float delta) {
        savemanager$repositionButtons();
        if (this.uploadSaveBtn != null) {
            this.uploadSaveBtn.active = sm$hasSelection() && !sm$isUploading();
        }

        if (sm$isUploading()) {
            sm$hideBottomButtons();
        } else {
            sm$restoreBottomButtons();
        }

        if (sm$isUploading()) {
            int cx = this.width / 2;
            int baseY = this.height - 50;
            int statusY = baseY;
            if (upZipping) {
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
                long uploaded = upUploaded;
                long total = upTotal;
                double speed = upSpeedBps;

                // Detect end-of-upload stall: >=99% and no progress callback for a while
                boolean knownTotals = total > 0 && uploaded >= 0;
                boolean nearlyDone = knownTotals && uploaded < total && uploaded >= Math.max(0L, (long)Math.floor(total * 0.99));
                long nowNs = System.nanoTime();
                long sinceLastProgressNs = (upLastTickNanos > 0L) ? (nowNs - upLastTickNanos) : Long.MAX_VALUE;
                boolean finishingStall = nearlyDone && sinceLastProgressNs > 1_500_000_000L; // ~1.5s

                if (finishingStall) {
                    // Show "Finishing..." with spinner (same style as zipping)
                    ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Finishing..."), cx, statusY, 0xFFFFFFFF);

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
                } else if (knownTotals) {
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

        // After first frame renders the spinner, kick off the zipping thread
        if (sm$pendingZip && !sm$zipStarted) {
            sm$zipStarted = true;
            final Path dir = sm$pendingWorldDir;
            final String wname = sm$pendingWorldName;
            new Thread(() -> {
                try {
                    Path zip = sm$zipWorld(dir, wname == null ? "world" : wname.replaceAll("[\\\\/:*?\"<>|]+", "_"));
                    sm$uploadZipDirect(zip, wname, savemanager$net);
                } catch (Exception e) {
                    SaveManagerMod.LOGGER.error("(savemanager) Failed zipping world", e);
                    upActive = false;
                    upZipping = false;
                    this.client.execute(() -> this.client.setScreen(new ConfirmScreen(b -> this.client.setScreen((SelectWorldScreen)(Object)this),
                            Text.literal("Upload Failed"), Text.literal("Failed to prepare world zip"))));
                } finally {
                    sm$pendingZip = false;
                    sm$pendingWorldDir = null;
                    sm$pendingWorldName = null;
                }
            }, "SaveManager-zip").start();
        }
    }

    @Unique
    private void sm$beginZipOnNextFrame(String worldName, Path worldDir) {
        // Ensure the uploading instance has the API key
        try { if (sm$apiKey != null) this.savemanager$net.setApiKey(sm$apiKey); } catch (Throwable ignored) {}

        upActive = true;
        upZipping = true;
        upUploaded = 0L;
        upTotal = -1L;
        upStartNanos = System.nanoTime();
        upLastTickNanos = upStartNanos;
        upLastBytes = 0L;
        upSpeedBps = 0.0;

        sm$pendingWorldName = worldName;
        sm$pendingWorldDir = worldDir;
        sm$pendingZip = true;
        sm$zipStarted = false;

        try { this.client.execute(() -> {}); } catch (Throwable ignored) {}
    }

    @Unique
    private void sm$uploadZipDirect(final Path zipFinal, final String origWorldName, final NetworkManager savemanager$net) {
        try {
            // Enter uploading state
            upZipping = false;
            upActive = true;
            upUploaded = 0L;
            upTotal = -1L;
            upStartNanos = System.nanoTime();
            upLastTickNanos = upStartNanos;
            upLastBytes = 0L;
            upSpeedBps = 0.0;

            try { this.client.execute(() -> {}); } catch (Throwable ignored) {}

            savemanager$net.uploadWorldSave(origWorldName, zipFinal, (sent, total) -> {
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

                try { this.client.execute(() -> {}); } catch (Throwable ignored) {}
            }).whenComplete((json, uploadErr) -> {
                try { Files.deleteIfExists(zipFinal); } catch (Throwable ignored) {}

                if (this.client == null) return;
                this.client.execute(() -> {
                    // Reset state
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
                        String raw = sm$rawFromThrowable(uploadErr);
                        sm$showErrorDialog(raw, "Upload failed");
                        return;
                    }

                    // Success: open a fresh SelectWorldScreen so list and icons fully reload
                    sm$openFreshSelectWorld();
                });
            });
        } catch (Throwable t) {
            try { Files.deleteIfExists(zipFinal); } catch (Throwable ignored) {}
            SaveManagerMod.LOGGER.error("(savemanager) Upload error for \"{}\"", origWorldName, t);
            if (this.client != null) {
                String raw = sm$rawFromThrowable(t);
                this.client.execute(() -> sm$showErrorDialog(raw, "Upload failed"));
            }
        }
    }

    @Unique
    private void savemanager$repositionButtons() {
        int x = 6, y = 6, h = ICON_SIZE, w = 0;
        try {
            if (this.searchBox != null) {
                try { x = (int) this.searchBox.getClass().getMethod("getX").invoke(this.searchBox); } catch (Throwable ignored) {}
                try { y = (int) this.searchBox.getClass().getMethod("getY").invoke(this.searchBox); } catch (Throwable ignored) {}
                try { w = (int) this.searchBox.getClass().getMethod("getWidth").invoke(this.searchBox); } catch (Throwable ignored) {}
                try { h = (int) this.searchBox.getClass().getMethod("getHeight").invoke(this.searchBox); } catch (Throwable ignored) {}
                try { Field f = this.searchBox.getClass().getDeclaredField("x"); f.setAccessible(true); x = f.getInt(this.searchBox); } catch (Throwable ignored) {}
                try { Field f = this.searchBox.getClass().getDeclaredField("y"); f.setAccessible(true); y = f.getInt(this.searchBox); } catch (Throwable ignored) {}
                if (w == 0) { try { Field f = this.searchBox.getClass().getDeclaredField("width"); f.setAccessible(true); w = f.getInt(this.searchBox); } catch (Throwable ignored) {} }
                try { Field f = this.searchBox.getClass().getDeclaredField("height"); f.setAccessible(true); h = f.getInt(this.searchBox); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        int iconY = y + Math.max(0, (h - ICON_SIZE) / 2);

        // Upload button directly to the right of the search field
        if (this.uploadSaveBtn != null) {
            int uploadX = x + w + GAP;
            try { this.uploadSaveBtn.setPosition(uploadX, iconY); } catch (Throwable ignored) {}
            this.uploadSaveBtn.visible = true;
        }

        // Cloud manager button follows the upload button
        if (this.cloudSavesBtn != null) {
            int cloudX = x + w + GAP + 20 + GAP;
            try { this.cloudSavesBtn.setPosition(cloudX, iconY); } catch (Throwable ignored) {}
            this.cloudSavesBtn.visible = true;
        }
    }

    @Unique
    private static boolean sm$isInvalidKey(String raw, String friendly) {
        String r = (raw == null ? "" : raw).toLowerCase(java.util.Locale.ROOT);
        if (friendly != null && friendly.equalsIgnoreCase("Invalid Save Manager API key")) return true;
        if (r.contains("401") && r.contains("invalid save manager api key")) return true;
        return false;
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
                if (this.client != null) {
                    this.client.execute(() -> {
                        Screen rootParent = sm$resolveWorldRootParent((Screen)(Object)this);
                        this.client.setScreen(new AccountLinkingScreen(rootParent));
                    });
                }
                return;
            }
            // Cache globally and set on this instance
            sm$apiKey = apiKey;
            savemanager$net.setApiKey(apiKey);

            final String worldName = sel.getDisplayName();
            final Path worldDir = sel.getDir();
            if (worldDir == null || !Files.exists(worldDir)) {
                SaveManagerMod.LOGGER.warn("(savemanager) Upload canceled: world directory not found: {}", sel);
                return;
            }
            final String dirKey = worldDir.getFileName() != null ? worldDir.getFileName().toString() : null;

            savemanager$net.listWorldSaveNames().whenComplete((names, namesErr) -> {
                // Start on whichever SelectWorldScreen is current, but re-apply API key to that instance
                Runnable beginOnCurrentScreen = () -> {
                    try {
                        Screen cur = this.client.currentScreen;
                        if (cur instanceof SelectWorldScreen) {
                            SinglePlayerScreenMixin mixin = (SinglePlayerScreenMixin) (Object) cur;
                            if (sm$apiKey != null) mixin.savemanager$net.setApiKey(sm$apiKey);
                            mixin.sm$beginZipOnNextFrame(worldName, worldDir);
                        } else {
                            if (sm$apiKey != null) this.savemanager$net.setApiKey(sm$apiKey);
                            sm$beginZipOnNextFrame(worldName, worldDir);
                        }
                    } catch (Throwable ignored) {}
                };

                if (namesErr != null) {
                    SaveManagerMod.LOGGER.warn("(savemanager) Failed to fetch name list; proceeding: {}", safeErrMessage(namesErr));
                    this.client.execute(beginOnCurrentScreen);
                    return;
                }

                boolean exists = false;
                String sanitized = sanitizeFolderName(worldName);
                if (names != null) {
                    for (String n : names) {
                        if (n == null) continue;
                        if (n.equalsIgnoreCase(worldName) || (!sanitized.isEmpty() && n.equalsIgnoreCase(sanitized))) {
                            exists = true;
                            break;
                        }
                    }
                }

                if (!exists) {
                    this.client.execute(beginOnCurrentScreen);
                    return;
                }

                this.client.execute(() -> {
                    Text title = Text.literal("Overwrite Cloud Save?");
                    Text message = Text.literal("A save named \"" + worldName + "\" already exists in the cloud. Overwrite?");
                    this.client.setScreen(new ConfirmScreen(confirmed -> {
                        sm$openFreshSelectWorld();

                        this.client.execute(() -> {
                            try {
                                Screen cur = this.client.currentScreen;
                                if (cur instanceof SelectWorldScreen) {
                                    SinglePlayerScreenMixin mixin = (SinglePlayerScreenMixin) (Object) cur;

                                    // Restore selection by folder name
                                    if (dirKey != null) {
                                        WorldListWidget list = mixin.sm$getWorldList();
                                        if (list != null) {
                                            java.util.List<?> entries = null;
                                            try { entries = (java.util.List<?>) list.getClass().getMethod("children").invoke(list); } catch (Throwable ignored) {}
                                            if (entries == null) {
                                                try {
                                                    Field f = list.getClass().getDeclaredField("children");
                                                    f.setAccessible(true);
                                                    Object v = f.get(list);
                                                    if (v instanceof java.util.List<?> l) entries = l;
                                                } catch (Throwable ignored) {}
                                            }
                                            if (entries != null) {
                                                Object match = null;
                                                for (Object e : entries) {
                                                    String dn = mixin.sm$getWorldDirNameFromEntry(e);
                                                    if (dirKey.equals(dn)) { match = e; break; }
                                                }
                                                if (match != null) {
                                                    try {
                                                        for (java.lang.reflect.Method m : list.getClass().getMethods()) {
                                                            if (m.getName().equals("setSelected") && m.getParameterCount() == 1) {
                                                                m.setAccessible(true);
                                                                m.invoke(list, match);
                                                                break;
                                                            }
                                                        }
                                                    } catch (Throwable ignored) {}
                                                }
                                            }
                                        }
                                    }

                                    if (confirmed) {
                                        // Re-apply API key on the fresh instance before starting
                                        if (sm$apiKey != null) mixin.savemanager$net.setApiKey(sm$apiKey);
                                        mixin.sm$beginZipOnNextFrame(worldName, worldDir);
                                    }
                                } else if (confirmed) {
                                    if (sm$apiKey != null) this.savemanager$net.setApiKey(sm$apiKey);
                                    sm$beginZipOnNextFrame(worldName, worldDir);
                                }
                            } catch (Throwable ignored) {}
                        });
                    }, title, message));
                });
            });
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

    @Unique
    private static Screen sm$resolveWorldRootParent(Screen start) {
        Screen p = start;
        int guard = 0;
        while (p instanceof SelectWorldScreen && guard++ < 8) {
            try {
                Field f = SelectWorldScreen.class.getDeclaredField("parent");
                f.setAccessible(true);
                Screen next = (Screen) f.get(p);
                if (next == null || next == p) break;
                p = next;
            } catch (Throwable ignored) {
                break;
            }
        }
        return p;
    }

    @Unique
    private void sm$openFreshSelectWorld() {
        if (this.client == null) return;
        Screen rootParent = sm$resolveWorldRootParent((Screen)(Object)this);
        this.client.setScreen(new SelectWorldScreen(rootParent));
    }

    @Unique
    private static String sm$rawFromThrowable(Throwable t) {
        if (t == null) return "";
        StringBuilder sb = new StringBuilder();
        int guard = 0;
        while (t != null && guard++ < 16) {
            String m = t.getMessage();
            if (m != null && !m.isBlank()) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(m);
            }
            t = t.getCause();
        }
        return sb.toString();
    }

    @Unique
    private static String sm$extractFriendly(String raw) {
        if (raw == null) raw = "";
        String friendly = "";

        // Try to extract {"error":"..."} text from any JSON found in the raw chain
        int start = raw.indexOf('{');
        while (start >= 0 && start < raw.length()) {
            try {
                String sub = raw.substring(start).trim();
                com.google.gson.JsonElement el = new com.google.gson.JsonParser().parse(sub);
                if (el.isJsonObject()) {
                    com.google.gson.JsonObject obj = el.getAsJsonObject();
                    if (obj.has("error") && obj.get("error").isJsonPrimitive()) {
                        friendly = obj.get("error").getAsString();
                        if (friendly != null && !friendly.isBlank()) return friendly;
                    }
                }
            } catch (Throwable ignored) {}
            start = raw.indexOf('{', start + 1);
        }

        // Known cases
        if (raw.contains("401") && raw.toLowerCase(java.util.Locale.ROOT).contains("invalid save manager api key")) {
            return "Invalid Save Manager API key";
        }
        if (raw.toLowerCase(java.util.Locale.ROOT).contains("exceed storage quota")) {
            int i = raw.indexOf('{');
            if (i >= 0) {
                try {
                    var obj = new com.google.gson.JsonParser().parse(raw.substring(i)).getAsJsonObject();
                    if (obj.has("error")) {
                        String s = obj.get("error").getAsString();
                        if (s != null && !s.isBlank()) return s;
                    }
                } catch (Throwable ignored) {}
            }
        }

        return friendly;
    }

    @Unique
    private void sm$showErrorDialog(String raw, String fallbackTitle) {
        String friendly = sm$extractFriendly(raw);
        Text title = Text.literal("Error");
        Text message = Text.literal((friendly == null || friendly.isBlank()) ? fallbackTitle : friendly);

        this.client.setScreen(new ConfirmScreen(confirmed -> {
            if (!confirmed) {
                try {
                    if (this.client != null && this.client.keyboard != null) {
                        this.client.keyboard.setClipboard(raw == null ? "" : raw);
                    }
                } catch (Throwable ignored) {}
                return;
            }
            if (sm$isInvalidKey(raw, friendly)) {
                Screen rootParent = sm$resolveWorldRootParent((Screen)(Object)this);
                this.client.setScreen(new com.choculaterie.gui.AccountLinkingScreen(rootParent));
                return;
            }
            sm$openFreshSelectWorld();
        }, title, message, Text.literal("OK"), Text.literal("Copy error")));
    }
}
