package com.choculaterie.util;

import com.google.gson.JsonObject;

public final class AccountState {

    private static volatile boolean known = false;
    private static volatile boolean premium = false;
    private static volatile String username = "";
    private static volatile boolean autoSync = false;
    private static volatile int versionsKept = 1;
    private static volatile String quotaFormatted = "";
    private static volatile String usedFormatted = "";

    private AccountState() {
    }

    public static void update(JsonObject quotaInfo) {
        if (quotaInfo == null)
            return;
        try {
            premium = quotaInfo.has("premium") && quotaInfo.get("premium").getAsBoolean();
            username = quotaInfo.has("username") && !quotaInfo.get("username").isJsonNull()
                    ? quotaInfo.get("username").getAsString()
                    : "";
            quotaFormatted = quotaInfo.has("quotaFormatted") ? quotaInfo.get("quotaFormatted").getAsString() : "";
            usedFormatted = quotaInfo.has("usedFormatted") ? quotaInfo.get("usedFormatted").getAsString() : "";

            JsonObject features = quotaInfo.has("features") && quotaInfo.get("features").isJsonObject()
                    ? quotaInfo.getAsJsonObject("features")
                    : null;
            if (features != null) {
                autoSync = features.has("autoSync") && features.get("autoSync").getAsBoolean();
                versionsKept = features.has("versionsKept") ? features.get("versionsKept").getAsInt() : 1;
            }
            known = true;
        } catch (Exception ignored) {
        }
    }

    public static void clear() {
        known = false;
        premium = false;
        username = "";
        autoSync = false;
        versionsKept = 1;
        quotaFormatted = "";
        usedFormatted = "";
    }

    public static boolean isKnown() {
        return known;
    }

    public static boolean isPremium() {
        return premium;
    }

    public static String username() {
        return username;
    }

    public static boolean hasAutoSync() {
        return autoSync;
    }

    public static int versionsKept() {
        return versionsKept;
    }

    public static String quotaFormatted() {
        return quotaFormatted;
    }

    public static String usedFormatted() {
        return usedFormatted;
    }
}
