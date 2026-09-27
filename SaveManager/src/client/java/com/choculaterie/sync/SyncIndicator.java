package com.choculaterie.sync;

import com.choculaterie.SaveManagerMod;
import com.choculaterie.util.ConfigManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;

public final class SyncIndicator {

    private static final Identifier ID = Identifier.fromNamespaceAndPath("savemanager", "sync_indicator");
    private static final Component SYNCING_TEXT = Component.literal("Syncing world to Choculaterie");

    private static final float FADE_SPEED_FACTOR = 0.2F;
    private static final int PADDING_RIGHT = 5;
    private static final int PADDING_BOTTOM = 5;
    private static final int LINE_HEIGHT = 9;

    private static float indicatorValue;
    private static float lastIndicatorValue;
    private static boolean loggedRegistration;

    private SyncIndicator() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        if (!loggedRegistration) {
            loggedRegistration = true;
            SaveManagerMod.LOGGER.info("[SM] sync indicator registered (enabled={})",
                    ConfigManager.isSyncIndicatorEnabled());
        }

        HudElementRegistry.addLast(ID, (context, tickCounter) -> {
            if (!ConfigManager.isSyncIndicatorEnabled())
                return;
            if (indicatorValue <= 0.0F && lastIndicatorValue <= 0.0F)
                return;

            int alpha = Mth.floor(255.0F * Mth.clamp(
                    Mth.lerp(tickCounter.getRealtimeDeltaTicks(), lastIndicatorValue, indicatorValue),
                    0.0F, 1.0F));
            if (alpha <= 0)
                return;

            Minecraft mc = Minecraft.getInstance();
            Font font = mc.font;
            if (font == null)
                return;

            int width = font.width(SYNCING_TEXT);
            int color = ARGB.color(alpha, -1);
            int x = context.guiWidth() - width - PADDING_RIGHT;
            int y = context.guiHeight() - LINE_HEIGHT - PADDING_BOTTOM;

            context.nextStratum();
            context.textWithBackdrop(font, SYNCING_TEXT, x, y, width, color);
        });
    }

    private static void tick() {
        boolean syncing = AutoSync.syncingWorld() != null;
        lastIndicatorValue = indicatorValue;
        indicatorValue = Mth.lerp(FADE_SPEED_FACTOR, indicatorValue, syncing ? 1.0F : 0.0F);
    }
}
