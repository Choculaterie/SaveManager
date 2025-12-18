package com.choculaterie.mixin;

import com.choculaterie.gui.CloudSaveManagerScreen;
import com.choculaterie.gui.UploadManagerScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SelectWorldScreen.class)
public abstract class SinglePlayerScreenMixin extends Screen {
    protected SinglePlayerScreenMixin(Text title, Screen parent) { super(title); }

    // Two small buttons at fixed top-left position
    @Unique private ButtonWidget uploadListBtn;   // opens UploadManagerScreen
    @Unique private ButtonWidget cloudSavesBtn;   // opens CloudSaveManagerScreen

    @Unique private static final int ICON_SIZE = 20;
    @Unique private static final int GAP = 4;
    @Unique private static final int LEFT_MARGIN = 6;
    @Unique private static final int TOP_MARGIN = 6;

    @Inject(method = "init", at = @At("TAIL"), remap = false)
    private void savemanager$init(CallbackInfo ci) {
        // Place two fixed-position buttons in the top-left corner
        int x = LEFT_MARGIN;
        int y = TOP_MARGIN;

        this.uploadListBtn = ButtonWidget.builder(
                Text.literal("\uD83D\uDCBE"),
                b -> this.client.setScreen(new UploadManagerScreen((Screen)(Object)this))
        ).dimensions(x, y, 20, ICON_SIZE).build();
        this.addDrawableChild(this.uploadListBtn);

        int cloudX = x + 20 + GAP;
        this.cloudSavesBtn = ButtonWidget.builder(
                Text.literal("\uD83D\uDCC1"),
                b -> this.client.setScreen(new CloudSaveManagerScreen((Screen)(Object)this))
        ).dimensions(cloudX, y, 20, ICON_SIZE).build();
        this.addDrawableChild(this.cloudSavesBtn);
    }
}
