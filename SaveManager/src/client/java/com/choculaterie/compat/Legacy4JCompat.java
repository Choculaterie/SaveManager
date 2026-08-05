package com.choculaterie.compat;

import com.choculaterie.gui.SaveManagerScreen;
import com.choculaterie.widget.CustomButton;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class Legacy4JCompat {
    private static final String PLAY_GAME_SCREEN = "wily.legacy.client.screen.PlayGameScreen";

    private Legacy4JCompat() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!screen.getClass().getName().equals(PLAY_GAME_SCREEN)) return;

            CustomButton cloudButton = new CustomButton(6, 6, 20, 20, Component.literal("☁"), b -> {});
            boolean[] wasDown = {false};

            ScreenEvents.afterExtract(screen).register((s, context, mouseX, mouseY, delta) -> {
                cloudButton.extractRenderState(context, mouseX, mouseY, delta);

                boolean isDown = GLFW.glfwGetMouseButton(client.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
                boolean clicked = wasDown[0] && !isDown;
                wasDown[0] = isDown;

                boolean hovered = mouseX >= 6 && mouseX < 26 && mouseY >= 6 && mouseY < 26;
                if (clicked && hovered) {
                    client.setScreen(new SaveManagerScreen(s));
                }
            });
        });
    }
}
