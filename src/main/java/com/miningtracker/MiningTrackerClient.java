package com.miningtracker;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MiningTrackerClient implements ClientModInitializer {
	public static final String MOD_ID = "mining-tracker";
	public static final Logger LOGGER = LoggerFactory.getLogger("광질 측정");

	@Override
	@SuppressWarnings("deprecation") // HudRenderCallback still works on 1.21.4 and is the simplest hook
	public void onInitializeClient() {
		ModConfig.load();
		History.load();
		Keys.register();

		Tracker tracker = Tracker.INSTANCE;
		ClientTickEvents.END_CLIENT_TICK.register(tracker::onEndTick);
		ClientPlayerBlockBreakEvents.AFTER.register(tracker::onBlockBroken);
		ClientEntityEvents.ENTITY_UNLOAD.register(tracker::onEntityUnload);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> tracker.onDisconnect());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> tracker.onClientStopping());
		HudRenderCallback.EVENT.register(HudRenderer::onHudRender);
		ClientCommandRegistrationCallback.EVENT.register(Commands::register);

		LOGGER.info("광질 측정 모드가 켜졌어요");
	}
}
