package com.choculaterie;

import com.choculaterie.gui.SaveManagerScreen;
import com.choculaterie.mixin.EntryListWidgetAccessor;
import com.choculaterie.mixin.SelectWorldScreenAccessor;
import com.choculaterie.mixin.WorldEntryAccessor;
import com.choculaterie.util.WatchManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.client.gui.tooltip.HoveredTooltipPositioner;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public class SaveManagerClient implements ClientModInitializer {
    private static boolean wasDown = false;

    @Override
    public void onInitializeClient() {
        SaveManagerMod.LOGGER.info("Initializing Save Manager Client");

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof SelectWorldScreen)) return;

            ScreenEvents.afterRender(screen).register((s, context, mouseX, mouseY, delta) -> {
                WorldListWidget levelList = ((SelectWorldScreenAccessor) s).getLevelList();
                if (levelList == null) return;
                List<String> changed = WatchManager.getPendingNotifications();
                if (changed.isEmpty()) return;

                int itemHeight = ((EntryListWidgetAccessor)(Object) levelList).getItemHeight();
                int listY = levelList.getY();
                int listBottom = levelList.getBottom();
                int scrollY = (int) levelList.getScrollY();
                int iconX = levelList.getRowRight() - 14;
                var tr = client.textRenderer;
                var children = levelList.children();

                boolean isDown = GLFW.glfwGetMouseButton(
                        client.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
                boolean clicked = wasDown && !isDown;
                wasDown = isDown;

                String hoveredTooltipWorld = null;

                for (int i = 0; i < children.size(); i++) {
                    String folderName = getFolderName(children.get(i));
                    if (folderName == null || !changed.contains(folderName)) continue;

                    int entryTop = listY + 4 + i * itemHeight - scrollY;
                    if (entryTop + itemHeight < listY || entryTop > listBottom) continue;

                    int iconY = entryTop + 2;
                    float pulse = (float)(0.6 + 0.4 * Math.sin(System.currentTimeMillis() / 350.0));
                    int alpha = (int)(pulse * 255);
                    int color = (alpha << 24) | 0x00FFFFFF;

                    context.drawText(tr, "\uD83D\uDCBE", iconX, iconY, color, true);

                    boolean inBounds = mouseX >= iconX - 1 && mouseX < iconX + 10
                            && mouseY >= iconY && mouseY < iconY + 10;

                    if (inBounds) hoveredTooltipWorld = folderName;

                    if (clicked && inBounds) {
                        MinecraftClient.getInstance().setScreen(new SaveManagerScreen(s, folderName));
                        return;
                    }
                }

                if (hoveredTooltipWorld != null) {
                    context.drawTooltipImmediately(tr, List.of(
                            TooltipComponent.of(Text.literal("Marked as favorite").asOrderedText()),
                            TooltipComponent.of(Text.literal("Click to upload to the cloud").asOrderedText())
                    ), mouseX, mouseY, HoveredTooltipPositioner.INSTANCE, null);
                }
            });
        });
    }

    private static String getFolderName(Object entry) {
        try {
            return ((WorldEntryAccessor)(Object) entry).getLevel().getName();
        } catch (Exception e) {
            return null;
        }
    }
}
