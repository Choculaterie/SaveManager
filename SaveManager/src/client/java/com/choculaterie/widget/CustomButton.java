package com.choculaterie.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;

public class CustomButton extends ButtonWidget {
    private static final int BUTTON_COLOR = 0xFF3A3A3A;
    private static final int BUTTON_HOVER_COLOR = 0xFF4A4A4A;
    private static final int BUTTON_DISABLED_COLOR = 0xFF2A2A2A;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int TEXT_DISABLED_COLOR = 0xFF888888;

    private boolean renderAsXIcon = false;
    private boolean renderAsDownloadIcon = false;
    private ToastManager toastManager = null;

    public CustomButton(int x, int y, int width, int height, net.minecraft.text.Text message, PressAction onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION_SUPPLIER);
    }

    public void setToastManager(ToastManager toastManager) {
        this.toastManager = toastManager;
    }

    public void setRenderAsXIcon(boolean renderAsXIcon) {
        this.renderAsXIcon = renderAsXIcon;
    }

    public void setRenderAsDownloadIcon(boolean renderAsDownloadIcon) {
        this.renderAsDownloadIcon = renderAsDownloadIcon;
    }

    @Override
    protected void drawIcon(DrawContext context, int mouseX, int mouseY, float delta) {
        boolean isHovered = mouseX >= this.getX() && mouseY >= this.getY() &&
                mouseX < this.getX() + this.getWidth() && mouseY < this.getY() + this.getHeight();

        // Block hover if a toast is covering the button
        if (isHovered && toastManager != null && toastManager.isMouseOverToast(mouseX, mouseY)) {
            isHovered = false;
        }

        int color = !this.active ? BUTTON_DISABLED_COLOR : isHovered ? BUTTON_HOVER_COLOR : BUTTON_COLOR;

        context.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), color);
        context.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + 1, 0xFF555555);
        context.fill(this.getX(), this.getY() + this.getHeight() - 1, this.getX() + this.getWidth(), this.getY() + this.getHeight(), 0xFF555555);
        context.fill(this.getX(), this.getY(), this.getX() + 1, this.getY() + this.getHeight(), 0xFF555555);
        context.fill(this.getX() + this.getWidth() - 1, this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), 0xFF555555);

        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int textColor = this.active ? TEXT_COLOR : TEXT_DISABLED_COLOR;
        String messageText = this.getMessage().getString();
        int yOffset = (messageText.equals("⚙") || messageText.equals("🔄")) ? 0 : 1;
        String displayText = renderAsXIcon ? "✕" : renderAsDownloadIcon ? "💾" : messageText;

        context.drawCenteredTextWithShadow(tr, displayText, this.getX() + this.getWidth() / 2,
                this.getY() + (this.getHeight() - 8) / 2 + yOffset, textColor);
    }
}
