package com.choculaterie.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class ConfirmPopup implements Drawable, Element {
    private static final int POPUP_WIDTH = 400;
    private static final int PADDING = 10;
    private static final int BUTTON_HEIGHT = 20;
    private static final int LINE_HEIGHT = 12;
    private static final int MAX_MESSAGE_HEIGHT = 300;

    private final String title;
    private final Runnable onConfirm;
    private final Runnable onCancel;
    private CustomButton confirmButton;
    private CustomButton cancelButton;
    private boolean wasEnterPressed = false;
    private boolean wasEscapePressed = false;
    private final int x;
    private final int y;
    private final int popupHeight;
    private final List<String> wrappedMessage;
    private final int actualMessageHeight;
    private final int visibleMessageHeight;
    private ScrollBar scrollBar;
    private double scrollOffset = 0;

    public ConfirmPopup(Screen parent, String title, String message, Runnable onConfirm, Runnable onCancel) {
        this(parent, title, message, onConfirm, onCancel, "Delete");
    }

    public ConfirmPopup(Screen parent, String title, String message, Runnable onConfirm, Runnable onCancel, String confirmButtonText) {
        this.title = title;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;

        MinecraftClient client = MinecraftClient.getInstance();
        this.wrappedMessage = wrapText(message, POPUP_WIDTH - PADDING * 2, client);

        int screenHeight = client.getWindow().getScaledHeight();
        int screenWidth = client.getWindow().getScaledWidth();
        int verticalMargin = 40;
        int popupChrome = PADDING + LINE_HEIGHT + PADDING + PADDING + BUTTON_HEIGHT + PADDING;
        int maxAvailableMessageHeight = screenHeight - (verticalMargin * 2) - popupChrome;
        int effectiveMaxHeight = Math.min(MAX_MESSAGE_HEIGHT, maxAvailableMessageHeight);

        this.actualMessageHeight = wrappedMessage.size() * LINE_HEIGHT;
        this.visibleMessageHeight = Math.min(actualMessageHeight, effectiveMaxHeight);
        this.popupHeight = PADDING + LINE_HEIGHT + PADDING + visibleMessageHeight + PADDING + BUTTON_HEIGHT + PADDING;

        this.x = (screenWidth - POPUP_WIDTH) / 2;
        this.y = (screenHeight - popupHeight) / 2;

        if (actualMessageHeight > visibleMessageHeight) {
            int messageAreaY = y + PADDING + LINE_HEIGHT + PADDING;
            int scrollBarX = x + POPUP_WIDTH - PADDING - 8;
            this.scrollBar = new ScrollBar(scrollBarX, messageAreaY, visibleMessageHeight);
            this.scrollBar.setScrollData(actualMessageHeight, visibleMessageHeight);
        }

        int buttonY = y + popupHeight - PADDING - BUTTON_HEIGHT;
        int buttonWidth = (POPUP_WIDTH - PADDING * 3) / 2;
        cancelButton = new CustomButton(x + PADDING, buttonY, buttonWidth, BUTTON_HEIGHT, Text.of("Cancel"), button -> onCancel.run());
        confirmButton = new CustomButton(x + POPUP_WIDTH - PADDING - buttonWidth, buttonY, buttonWidth, BUTTON_HEIGHT, Text.of(confirmButtonText), button -> onConfirm.run());
    }

    private List<String> wrapText(String text, int maxWidth, MinecraftClient client) {
        List<String> lines = new ArrayList<>();
        String[] paragraphs = text.split("\n");
        for (String paragraph : paragraphs) {
            if (paragraph.isEmpty()) {
                lines.add("");
                continue;
            }
            String[] words = paragraph.split(" ");
            StringBuilder currentLine = new StringBuilder();
            for (String word : words) {
                String testLine = currentLine.length() == 0 ? word : currentLine + " " + word;
                int width = client.textRenderer.getWidth(testLine);
                if (width <= maxWidth) {
                    if (currentLine.length() > 0) currentLine.append(" ");
                    currentLine.append(word);
                } else {
                    if (currentLine.length() > 0) lines.add(currentLine.toString());
                    currentLine = new StringBuilder(word);
                }
            }
            if (currentLine.length() > 0) lines.add(currentLine.toString());
        }
        return lines.isEmpty() ? List.of(text) : lines;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        MinecraftClient client = MinecraftClient.getInstance();
        long windowHandle = client.getWindow() != null ? client.getWindow().getHandle() : 0;

        if (windowHandle != 0) {
            boolean enterPressed = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_ENTER) == GLFW.GLFW_PRESS ||
                    GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_KP_ENTER) == GLFW.GLFW_PRESS;
            boolean escapePressed = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS;
            if (enterPressed && !wasEnterPressed) onConfirm.run();
            if (escapePressed && !wasEscapePressed) onCancel.run();
            wasEnterPressed = enterPressed;
            wasEscapePressed = escapePressed;
        }

        context.fill(0, 0, client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight(), 0x80000000);
        context.fill(x, y, x + POPUP_WIDTH, y + popupHeight, 0xFF2A2A2A);
        context.fill(x, y, x + POPUP_WIDTH, y + 1, 0xFF555555);
        context.fill(x, y + popupHeight - 1, x + POPUP_WIDTH, y + popupHeight, 0xFF555555);
        context.fill(x, y, x + 1, y + popupHeight, 0xFF555555);
        context.fill(x + POPUP_WIDTH - 1, y, x + POPUP_WIDTH, y + popupHeight, 0xFF555555);

        context.drawCenteredTextWithShadow(client.textRenderer, title, x + POPUP_WIDTH / 2, y + PADDING, 0xFFFFFFFF);

        int messageAreaY = y + PADDING + LINE_HEIGHT + PADDING;
        context.enableScissor(x + PADDING, messageAreaY, x + POPUP_WIDTH - PADDING, messageAreaY + visibleMessageHeight);
        int messageY = messageAreaY - (int) scrollOffset;
        for (String line : wrappedMessage) {
            if (messageY + LINE_HEIGHT >= messageAreaY && messageY < messageAreaY + visibleMessageHeight) {
                context.drawTextWithShadow(client.textRenderer, line, x + PADDING, messageY, 0xFFCCCCCC);
            }
            messageY += LINE_HEIGHT;
        }
        context.disableScissor();

        if (scrollBar != null && client.getWindow() != null) {
            scrollBar.setScrollPercentage(scrollOffset / Math.max(1, actualMessageHeight - visibleMessageHeight));
            boolean scrollChanged = scrollBar.updateAndRender(context, mouseX, mouseY, delta, client.getWindow().getHandle());
            if (scrollChanged || scrollBar.isDragging()) {
                double maxScroll = actualMessageHeight - visibleMessageHeight;
                scrollOffset = scrollBar.getScrollPercentage() * maxScroll;
            }
        }

        if (cancelButton != null) cancelButton.render(context, mouseX, mouseY, delta);
        if (confirmButton != null) confirmButton.render(context, mouseX, mouseY, delta);
    }

    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean consumed) {
        if (consumed) {
            return false;
        }
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();

        if (mouseX < x || mouseX > x + POPUP_WIDTH || mouseY < y || mouseY > y + popupHeight) {
            onCancel.run();
            return true;
        }
        if (cancelButton != null && mouseX >= cancelButton.getX() && mouseX < cancelButton.getX() + cancelButton.getWidth() &&
                mouseY >= cancelButton.getY() && mouseY < cancelButton.getY() + cancelButton.getHeight()) {
            onCancel.run();
            return true;
        }
        if (confirmButton != null && mouseX >= confirmButton.getX() && mouseX < confirmButton.getX() + confirmButton.getWidth() &&
                mouseY >= confirmButton.getY() && mouseY < confirmButton.getY() + confirmButton.getHeight()) {
            onConfirm.run();
            return true;
        }
        return true;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (scrollBar != null) {
            int messageAreaY = y + PADDING + LINE_HEIGHT + PADDING;
            if (mouseX >= x && mouseX < x + POPUP_WIDTH && mouseY >= messageAreaY && mouseY < messageAreaY + visibleMessageHeight) {
                double maxScroll = actualMessageHeight - visibleMessageHeight;
                scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - verticalAmount * LINE_HEIGHT));
                return true;
            }
        }
        return false;
    }

    @Override
    public void setFocused(boolean focused) {}

    @Override
    public boolean isFocused() {
        return false;
    }
}
