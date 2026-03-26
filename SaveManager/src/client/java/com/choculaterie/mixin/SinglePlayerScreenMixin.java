package com.choculaterie.mixin;

import com.choculaterie.gui.SaveManagerScreen;
import com.choculaterie.util.WatchManager;
import com.choculaterie.widget.CustomButton;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Mixin(SelectWorldScreen.class)
public abstract class SinglePlayerScreenMixin extends Screen {
    protected SinglePlayerScreenMixin(Text title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"), remap = false)
    private void savemanager$init(CallbackInfo ci) {
        addDrawableChild(new CustomButton(6, 6, 20, 20, Text.literal("\u2601"),
                b -> this.client.setScreen(new SaveManagerScreen((Screen)(Object)this))));

        MinecraftClient mc = MinecraftClient.getInstance();
        Path savesDir = mc.runDirectory.toPath().resolve("saves");

        CompletableFuture.runAsync(() -> {
            List<String> changed = WatchManager.getChangedWorlds(savesDir);
            WatchManager.setPendingNotifications(changed);
        });
    }
}
