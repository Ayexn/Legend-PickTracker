package com.miningtracker;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

/** A colour for each mineral, for its bar, slot and chart part. */
final class Palette {
	static final int OTHER = 0xFF808A9A;

	private static final Map<String, Integer> COLORS = Map.ofEntries(
			Map.entry("redstone", 0xFFE23434),
			Map.entry("raw_iron", 0xFFD8B296),
			Map.entry("iron_ingot", 0xFFD6D6D6),
			Map.entry("iron_nugget", 0xFFD6D6D6),
			Map.entry("lapis_lazuli", 0xFF406EDE),
			Map.entry("raw_copper", 0xFFE68056),
			Map.entry("copper_ingot", 0xFFE68056),
			Map.entry("coal", 0xFF707078),
			Map.entry("charcoal", 0xFF707078),
			Map.entry("diamond", 0xFF5CE6DE),
			Map.entry("emerald", 0xFF4FD16A),
			Map.entry("raw_gold", 0xFFF2C94C),
			Map.entry("gold_ingot", 0xFFF2C94C),
			Map.entry("gold_nugget", 0xFFF2C94C),
			Map.entry("quartz", 0xFFE8E2D8),
			Map.entry("amethyst_shard", 0xFFA87CE0),
			Map.entry("ancient_debris", 0xFF8A6A5A),
			Map.entry("netherite_scrap", 0xFF8A6A5A),
			Map.entry("glowstone_dust", 0xFFF5D76E),
			Map.entry("flint", 0xFF5A5A5A));

	private Palette() {
	}

	static int of(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return OTHER;
		}
		Integer known = COLORS.get(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
		return known != null ? known : fromName(stack.getHoverName().getString());
	}

	/** Server items with their own name: a steady colour picked from the name. */
	static int fromName(String name) {
		float hue = (name.hashCode() & 0xFFFF) / 65536f;
		return hsv(hue, 0.55f, 0.92f);
	}

	private static int hsv(float h, float s, float v) {
		float r;
		float g;
		float b;
		int i = (int) (h * 6) % 6;
		float f = h * 6 - (int) (h * 6);
		float p = v * (1 - s);
		float q = v * (1 - f * s);
		float t = v * (1 - (1 - f) * s);
		switch (i) {
			case 0 -> { r = v; g = t; b = p; }
			case 1 -> { r = q; g = v; b = p; }
			case 2 -> { r = p; g = v; b = t; }
			case 3 -> { r = p; g = q; b = v; }
			case 4 -> { r = t; g = p; b = v; }
			default -> { r = v; g = p; b = q; }
		}
		return 0xFF000000 | (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
	}
}
