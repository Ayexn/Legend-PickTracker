package com.miningtracker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Settings saved to {@code config/mining-tracker.json}.
 * Positions are in GUI pixels (the same coordinates Minecraft uses for its own HUD).
 */
public final class ModConfig {
	public static final float DEFAULT_SCALE = 2f / 3f;
	public static final float DEFAULT_PANEL_X = 17f;
	public static final float DEFAULT_PANEL_Y = 117f;
	public static final float DEFAULT_TOAST_RIGHT = 4f;
	public static final float DEFAULT_TOAST_Y = 287f;
	public static final float MIN_SCALE = 0.3f;
	public static final float MAX_SCALE = 2.0f;

	/** Picked-up items that count as "기타" (filler) instead of 광물. Item ids. */
	private static final List<String> DEFAULT_OTHER_ITEMS = List.of(
			"minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:stone", "minecraft:deepslate",
			"minecraft:tuff", "minecraft:granite", "minecraft:diorite", "minecraft:andesite", "minecraft:calcite",
			"minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt", "minecraft:gravel", "minecraft:flint",
			"minecraft:sand", "minecraft:red_sand", "minecraft:sandstone", "minecraft:red_sandstone",
			"minecraft:netherrack", "minecraft:basalt", "minecraft:smooth_basalt", "minecraft:blackstone",
			"minecraft:soul_sand", "minecraft:soul_soil", "minecraft:magma_block", "minecraft:dripstone_block",
			"minecraft:pointed_dripstone", "minecraft:clay_ball", "minecraft:moss_block", "minecraft:moss_carpet",
			"minecraft:mud", "minecraft:glow_lichen", "minecraft:end_stone", "minecraft:torch", "minecraft:string",
			"minecraft:cobweb");

	public boolean hudVisible = true;
	public boolean toastsEnabled = true;
	public boolean toastOthers = false;
	public float panelX = DEFAULT_PANEL_X;
	public float panelY = DEFAULT_PANEL_Y;
	public float panelScale = DEFAULT_SCALE;
	public float toastRight = DEFAULT_TOAST_RIGHT;
	public float toastY = DEFAULT_TOAST_Y;
	public float toastScale = DEFAULT_SCALE;
	public int maxBlockRows = 5;
	public int maxItemRows = 8;
	public List<String> otherItems = new ArrayList<>(DEFAULT_OTHER_ITEMS);

	private transient Set<String> otherItemSet;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static ModConfig instance = new ModConfig();

	public static ModConfig get() {
		return instance;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("mining-tracker.json");
	}

	public static void load() {
		Path file = path();
		ModConfig loaded = null;
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				loaded = GSON.fromJson(reader, ModConfig.class);
			} catch (Exception e) {
				MiningTrackerClient.LOGGER.warn("설정 파일을 읽지 못해 기본값을 씁니다: {}", e.toString());
			}
		}
		instance = loaded != null ? loaded : new ModConfig();
		instance.sanitize();
		save();
	}

	public static void save() {
		try {
			Files.createDirectories(path().getParent());
			try (Writer writer = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
				GSON.toJson(instance, writer);
			}
		} catch (Exception e) {
			MiningTrackerClient.LOGGER.warn("설정 파일을 저장하지 못했습니다: {}", e.toString());
		}
	}

	public boolean isOtherItem(String itemId) {
		if (otherItemSet == null) {
			otherItemSet = new HashSet<>(otherItems);
		}
		return otherItemSet.contains(itemId);
	}

	public void resetLayout() {
		panelX = DEFAULT_PANEL_X;
		panelY = DEFAULT_PANEL_Y;
		panelScale = DEFAULT_SCALE;
		toastRight = DEFAULT_TOAST_RIGHT;
		toastY = DEFAULT_TOAST_Y;
		toastScale = DEFAULT_SCALE;
	}

	public static float clampScale(float scale) {
		if (Float.isNaN(scale)) {
			return DEFAULT_SCALE;
		}
		return Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
	}

	private void sanitize() {
		panelScale = clampScale(panelScale);
		toastScale = clampScale(toastScale);
		if (Float.isNaN(panelX) || Float.isNaN(panelY)) {
			panelX = DEFAULT_PANEL_X;
			panelY = DEFAULT_PANEL_Y;
		}
		if (Float.isNaN(toastRight) || Float.isNaN(toastY)) {
			toastRight = DEFAULT_TOAST_RIGHT;
			toastY = DEFAULT_TOAST_Y;
		}
		maxBlockRows = Math.max(1, Math.min(20, maxBlockRows));
		maxItemRows = Math.max(1, Math.min(20, maxItemRows));
		if (otherItems == null) {
			otherItems = new ArrayList<>(DEFAULT_OTHER_ITEMS);
		}
		otherItemSet = null;
	}
}
