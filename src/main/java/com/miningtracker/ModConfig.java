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
	public static final float DEFAULT_BAR_Y = 4f;
	public static final float MIN_SCALE = 0.3f;
	public static final float MAX_SCALE = 2.0f;
	/** Choices offered in the settings screen for "광질 끝 판정". */
	public static final int[] END_AFTER_CHOICES = {30, 60, 180, 300};

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

	/** Worlds where automatic recording may start: the server's 야생 (wild) world and its nether. */
	private static final List<String> DEFAULT_WILD_DIMENSIONS = List.of("minecraft:overworld", "minecraft:the_nether");

	// ---- recording ----
	/** true = 자동 (starts when mining in the wild, ends after a quiet minute), false = 수동 (F7). */
	public boolean autoMode = true;
	/** Seconds without mining after which an automatic recording ends. */
	public int endAfterSeconds = 60;
	/** true = 이어서 (keep adding to the last record), false = 0부터 (start every recording from zero). */
	public boolean continueOnRestart = false;
	/** Post a summary to the chat (only visible to you) when a recording ends. */
	public boolean chatSummary = true;
	public List<String> wildDimensions = new ArrayList<>(DEFAULT_WILD_DIMENSIONS);
	/** Places (world + spawn point) where automatic recording was switched off, e.g. 세레니티. */
	public List<String> excludedPlaces = new ArrayList<>();

	// ---- display ----
	/** 집계판 모양: detail (자세히), slim (슬림), icons (아이콘), bar (한 줄). */
	public String layout = HudLayout.SLIM.id;
	/** 테마: mint (민트), blue (파랑), purple (보라), orange (주황), white (흰색), clear (투명). */
	public String theme = Theme.MINT.id;
	public boolean hudVisible = true;
	public boolean toastsEnabled = true;
	public boolean toastOthers = false;
	public float panelX = DEFAULT_PANEL_X;
	public float panelY = DEFAULT_PANEL_Y;
	public float panelScale = DEFAULT_SCALE;
	public float toastRight = DEFAULT_TOAST_RIGHT;
	public float toastY = DEFAULT_TOAST_Y;
	public float toastScale = DEFAULT_SCALE;
	/** Middle of the one-line board in GUI pixels; -1 = middle of the screen. */
	public float barCenterX = -1f;
	public float barY = DEFAULT_BAR_Y;
	public float barScale = DEFAULT_SCALE;
	public int maxBlockRows = 5;
	public int maxItemRows = 8;
	/**
	 * 광질 통계 → 광물별 → 고르기: the minerals the table shows ("item id|name", as the history keeps them); the
	 * others are added up in one "그 외" line. null = all of them (also ones picked up for the first time).
	 */
	public List<String> statsKinds = null;
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

	public HudLayout hudLayout() {
		return HudLayout.byId(layout);
	}

	public Theme currentTheme() {
		return Theme.byId(theme);
	}

	public boolean isWildDimension(String dimensionId) {
		return wildDimensions.contains(dimensionId);
	}

	public boolean isExcluded(String place) {
		return excludedPlaces.contains(place);
	}

	public void setExcluded(String place, boolean excluded) {
		excludedPlaces.remove(place);
		if (excluded) {
			excludedPlaces.add(place);
		}
	}

	public void resetLayout() {
		panelX = DEFAULT_PANEL_X;
		panelY = DEFAULT_PANEL_Y;
		panelScale = DEFAULT_SCALE;
		toastRight = DEFAULT_TOAST_RIGHT;
		toastY = DEFAULT_TOAST_Y;
		toastScale = DEFAULT_SCALE;
		barCenterX = -1f;
		barY = DEFAULT_BAR_Y;
		barScale = DEFAULT_SCALE;
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
		barScale = clampScale(barScale);
		if (Float.isNaN(barCenterX) || Float.isNaN(barY)) {
			barCenterX = -1f;
			barY = DEFAULT_BAR_Y;
		}
		layout = HudLayout.byId(layout).id;
		theme = Theme.byId(theme).id;
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
		endAfterSeconds = Math.max(10, Math.min(1800, endAfterSeconds));
		if (statsKinds != null) {
			statsKinds = new ArrayList<>(statsKinds);
			statsKinds.removeIf(k -> k == null);
		}
		if (otherItems == null) {
			otherItems = new ArrayList<>(DEFAULT_OTHER_ITEMS);
		}
		if (wildDimensions == null) {
			wildDimensions = new ArrayList<>(DEFAULT_WILD_DIMENSIONS);
		}
		if (excludedPlaces == null) {
			excludedPlaces = new ArrayList<>();
		}
		otherItemSet = null;
	}
}
