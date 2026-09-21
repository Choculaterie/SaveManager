package com.choculaterie.gui;

import com.choculaterie.vanilib.gui.widget.CustomButton;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public class CloudButton extends CustomButton {
    private static final Component CLOUD = Component.literal("\u2601");
    private static final Component UPLOAD = Component.literal("\u2191");
    private static final Component DOWNLOAD = Component.literal("\u2193");

    public CloudButton(int x, int y, int width, int height, Button.OnPress onPress) {
        super(x, y, width, height, CLOUD, onPress);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        boolean uploading = SaveManagerScreen.isUploading();
        boolean downloading = SaveManagerScreen.isDownloading();
        setMessage(uploading ? UPLOAD : downloading ? DOWNLOAD : CLOUD);
        super.extractContents(context, mouseX, mouseY, delta);

        if (uploading || downloading) {
            double frac = SaveManagerScreen.transferProgress();
            if (frac > 0)
                context.fill(getX(), getY(), getX() + (int) (getWidth() * frac), getY() + getHeight(), 0x40FFFFFF);
        }
    }
}
