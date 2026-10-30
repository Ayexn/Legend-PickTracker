package com.miningtracker.mixin;

import com.miningtracker.Tracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Everything shown above the hotbar goes through here (the server's action bar and overlay messages). The item
 * storage's "N개 수납" notices are read from it to count what the player picked up. Only reads; the message is
 * shown as usual.
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
	@Inject(method = "setOverlayMessage", at = @At("HEAD"))
	private void miningTracker$readNotice(Component message, boolean animateColor, CallbackInfo ci) {
		Tracker.INSTANCE.onOverlayMessage(message);
	}
}
