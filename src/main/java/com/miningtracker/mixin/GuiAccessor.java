package com.miningtracker.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The line above the hotbar right now, as a backup for reading the storage notices (see Tracker). Only reads. */
@Mixin(Gui.class)
public interface GuiAccessor {
	@Accessor("overlayMessageString")
	Component miningTracker$overlayMessage();
}
