package com.choculaterie.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.Font;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FormatUtils {
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    private FormatUtils() {}

    public static String safe(String s) {
        return s == null ? "" : s;
    }

    public static String formatBytes(long n) {
        if (n < 1024) return n + " B";
        int u = -1;
        double d = n;
        String[] units = {"KB", "MB", "GB", "TB"};
        do { d /= 1024; u++; } while (d >= 1024 && u < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", d, units[u]);
    }

    public static String formatDuration(long seconds) {
        if (seconds <= 0) return "0s";
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        if (h > 0) return String.format("%dh %02dm", h, m);
        if (m > 0) return String.format("%dm %02ds", m, s);
        return String.format("%ds", s);
    }

    public static String shortDate(String iso) {
        if (iso == null) return "";
        int t = iso.indexOf('T');
        return t > 0 ? iso.substring(0, t) : iso;
    }

    public static String shortDateMillis(long epochMillis) {
        if (epochMillis <= 0) return "";
        return DATE_FMT.format(Instant.ofEpochMilli(epochMillis));
    }

    public static String sanitizeFolderName(String s) {
        if (s == null) return "";
        String clean = s.trim().replaceAll("[\\\\/:*?\"<>|]+", "_");
        return clean.isBlank() ? "" : clean;
    }

    public static String extractErrorMessage(Throwable err) {
        if (err == null) return "Unknown error";
        Throwable cause = err;
        while (cause.getCause() != null &&
               (cause instanceof java.util.concurrent.CompletionException ||
                cause instanceof java.util.concurrent.ExecutionException)) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = cause.getClass().getSimpleName();
        }
        msg = msg.replaceFirst("^Request failed: \\d+ - ", "");
        msg = msg.replaceFirst("^Upload failed: ", "");
        msg = msg.replaceFirst("^HTTP \\d+: ", "");
        if (msg.contains("{") && msg.contains("\"error\"")) {
            try {
                int start = msg.indexOf('{');
                int end = msg.lastIndexOf('}') + 1;
                if (start >= 0 && end > start) {
                    String jsonPart = msg.substring(start, end);
                    JsonObject json = new Gson().fromJson(jsonPart, JsonObject.class);
                    if (json != null && json.has("error")) {
                        return json.get("error").getAsString();
                    }
                }
            } catch (Exception ignored) {}
        }
        return msg;
    }

    public static List<String> wrapText(Font textRenderer, String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n")) {
            if (paragraph.isEmpty()) {
                lines.add("");
                continue;
            }
            StringBuilder currentLine = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String testLine = currentLine.isEmpty() ? word : currentLine + " " + word;
                if (textRenderer.width(testLine) <= maxWidth) {
                    if (!currentLine.isEmpty()) currentLine.append(" ");
                    currentLine.append(word);
                } else {
                    if (!currentLine.isEmpty()) lines.add(currentLine.toString());
                    currentLine = new StringBuilder(word);
                }
            }
            if (!currentLine.isEmpty()) lines.add(currentLine.toString());
        }
        return lines.isEmpty() ? List.of(text) : lines;
    }
}
