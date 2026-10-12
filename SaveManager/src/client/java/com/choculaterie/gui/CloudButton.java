package com.choculaterie.gui;

import com.choculaterie.vanilib.gui.theme.UIDraw;
import com.choculaterie.vanilib.gui.theme.UIIcon;
import com.choculaterie.vanilib.gui.theme.UITheme;
import com.choculaterie.vanilib.gui.widget.CustomButton;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public class CloudButton extends CustomButton {
    public CloudButton(int x, int y, int width, int height, Button.OnPress onPress) {
        super(x, y, width, height, Component.empty(), onPress);
        setIcon(UIIcon.CLOUD);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        boolean uploading = SaveManagerScreen.isUploading();
        boolean downloading = SaveManagerScreen.isDownloading();
        setIcon(uploading ? UIIcon.UPLOAD : downloading ? UIIcon.DOWNLOAD : UIIcon.CLOUD);
        super.extractContents(context, mouseX, mouseY, delta);

        if (uploading || downloading) {
            double frac = SaveManagerScreen.transferProgress();
            if (frac > 0)
                context.fill(getX() + 1, getY() + 1, getX() + 1 + (int) ((getWidth() - 2) * frac), getY() + getHeight() - 1,
                        UIDraw.withAlpha(UITheme.Colors.ACCENT, 0x60));
        }
    }
}
