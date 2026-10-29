package com.miningtracker;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** Key bindings. They show up under "광질 측정" in 설정 → 조작 → 키 설정 and can be changed there. */
public final class Keys {
	private static final String CATEGORY = "key.categories.mining-tracker";

	public static KeyMapping toggleHud;
	public static KeyMapping pause;
	public static KeyMapping reset;
	public static KeyMapping edit;
	public static KeyMapping stats;

	private Keys() {
	}

	static void register() {
		toggleHud = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.mining-tracker.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F6, CATEGORY));
		pause = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.mining-tracker.pause", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, CATEGORY));
		reset = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.mining-tracker.reset", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, CATEGORY));
		edit = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.mining-tracker.edit", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, CATEGORY));
		stats = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.mining-tracker.stats", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F10, CATEGORY));
	}
}
