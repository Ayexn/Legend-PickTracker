package com.miningtracker.mixin;

import com.miningtracker.Tracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hears the server's "this entity picked up that item" message before the game removes the item. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "handleTakeItemEntity", at = @At("HEAD"))
	private void miningTracker$onTakeItem(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
		// the handler first runs on the network thread only to hand itself over to the main thread
		if (!Minecraft.getInstance().isSameThread()) {
			return;
		}
		Tracker.INSTANCE.onTakeItemPacket(packet.getItemId(), packet.getPlayerId(), packet.getAmount());
	}
}
