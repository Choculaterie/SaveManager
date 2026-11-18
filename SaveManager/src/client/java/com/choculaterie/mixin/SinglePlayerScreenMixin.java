package com.choculaterie.mixin;

import com.choculaterie.gui.SaveManagerScreen;
import com.choculaterie.util.AccountState;
import com.choculaterie.vanilib.gui.theme.UITheme;
import com.choculaterie.vanilib.util.WatchManager;
import com.choculaterie.gui.CloudButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Mixin(SelectWorldScreen.class)
public abstract class SinglePlayerScreenMixin extends Screen {
    @Unique
    private CloudButton savemanager$cloud;

    protected SinglePlayerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"), remap = false)
    private void savemanager$init(CallbackInfo ci) {
        savemanager$cloud = addRenderableWidget(new CloudButton(6, 6, 20, 20,
                b -> this.minecraft.gui.setScreen(new SaveManagerScreen((Screen) (Object) this))));
        savemanager$place();

        Minecraft mc = Minecraft.getInstance();
        Path savesDir = mc.gameDirectory.toPath().resolve("saves");

        CompletableFuture.runAsync(() -> {
            if (AccountState.hasAutoSync()) {
                WatchManager.setPendingNotifications(List.of());
                return;
            }
            List<String> changed = WatchManager.getChangedWorlds(savesDir);
            WatchManager.setPendingNotifications(changed);
        });
    }

    @Inject(method = "repositionElements", at = @At("TAIL"), remap = false)
    private void savemanager$reposition(CallbackInfo ci) {
        savemanager$place();
    }

    @Unique
    private void savemanager$place() {
        if (savemanager$cloud == null)
            return;
        Button play = ((SelectWorldScreenAccessor) this).getPlayWorldButton();
        int x = play == null ? -1 : play.getX() - savemanager$cloud.getWidth() - UITheme.Dimensions.BUTTON_GAP;
        if (play == null || x < UITheme.Dimensions.PADDING_SMALL)
            savemanager$cloud.setPosition(6, 6);
        else
            savemanager$cloud.setPosition(x, play.getY());
    }
}
