package com.choculaterie;

import com.choculaterie.gui.SaveManagerScreen;
import com.choculaterie.mixin.SelectWorldScreenAccessor;
import com.choculaterie.mixin.WorldEntryAccessor;
import com.choculaterie.util.WatchManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public class SaveManagerClient implements ClientModInitializer {
    private static boolean wasDown = false;

    @Override
    public void onInitializeClient() {
        SaveManagerMod.LOGGER.info("Initializing Save Manager Client");

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof SelectWorldScreen))
                return;

            ScreenEvents.afterExtract(screen).register((s, context, mouseX, mouseY, delta) -> {
                WorldSelectionList levelList = ((SelectWorldScreenAccessor) s).getLevelList();
                if (levelList == null)
                    return;
                List<String> changed = WatchManager.getPendingNotifications();
                if (changed.isEmpty())
                    return;

                int listY = levelList.getY();
                int listBottom = levelList.getBottom();
                int iconX = levelList.getRowRight() - 14;
                var tr = client.font;
                var children = levelList.children();

                boolean isDown = GLFW.glfwGetMouseButton(
                        client.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
                boolean clicked = wasDown && !isDown;
                wasDown = isDown;

                String hoveredTooltipWorld = null;

                for (int i = 0; i < children.size(); i++) {
                    var entry = children.get(i);
                    String folderName = getFolderName(entry);
                    if (folderName == null || !changed.contains(folderName))
                        continue;

                    int entryTop = entry.getY();
                    int entryHeight = entry.getHeight();
                    if (entryTop + entryHeight < listY || entryTop > listBottom)
                        continue;

                    int iconY = entryTop + 2;
                    float pulse = (float) (0.6 + 0.4 * Math.sin(System.currentTimeMillis() / 350.0));
                    int alpha = (int) (pulse * 255);
                    int color = (alpha << 24) | 0x00FFFFFF;

                    context.text(tr, "\uD83D\uDCBE", iconX, iconY, color, true);

                    boolean inBounds = mouseX >= iconX - 1 && mouseX < iconX + 10
                            && mouseY >= iconY && mouseY < iconY + 10;

                    if (inBounds)
                        hoveredTooltipWorld = folderName;

                    if (clicked && inBounds) {
                        Minecraft.getInstance().setScreen(new SaveManagerScreen(s, folderName));
                        return;
                    }
                }

                if (hoveredTooltipWorld != null) {
                    context.setComponentTooltipForNextFrame(tr, List.of(
                            Component.literal("Marked as favorite"),
                            Component.literal("Click to upload to the cloud")), mouseX, mouseY);
                }
            });
        });
    }

    private static String getFolderName(Object entry) {
        try {
            return ((WorldEntryAccessor) (Object) entry).getLevel().getLevelId();
        } catch (Exception e) {
            return null;
        }
    }
}
