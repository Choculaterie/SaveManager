package com.choculaterie.mixin;

import net.minecraft.client.gui.widget.EntryListWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(EntryListWidget.class)
public interface EntryListWidgetAccessor {
    @Invoker("getRowLeft")
    int savemanager$getRowLeft();

    @Invoker("getRowWidth")
    int savemanager$getRowWidth();
}