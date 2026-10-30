package com.miningtracker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Finished recordings for the statistics screen (광질 통계), saved to {@code config/mining-tracker-history.json}.
 * They stay on this computer; nothing is sent anywhere.
 */
public final class History {
	/**
	 * How a server's own item looks: such items are a plain item (often paper) that the server's resource pack
	 * draws differently, chosen by these two parts of the item. Kept so the statistics can draw the right icon.
	 */
	public static final class Look {
		/** minecraft:item_model, when it is not the plain item's own. */
		public String model;
		/** minecraft:custom_model_data, when the item has it. */
		public List<Float> floats;
		public List<Boolean> flags;
		public List<String> strings;
		public List<Integer> colors;

		/** The look of {@code stack}, or null when it looks like the plain item. */
		static Look of(ItemStack stack) {
			try {
				Look look = new Look();
				ResourceLocation model = stack.get(DataComponents.ITEM_MODEL);
				if (model != null && !model.equals(new ItemStack(stack.getItem()).get(DataComponents.ITEM_MODEL))) {
					look.model = model.toString();
				}
				CustomModelData data = stack.get(DataComponents.CUSTOM_MODEL_DATA);
				if (data != null && !data.equals(CustomModelData.EMPTY)) {
					look.floats = new ArrayList<>(data.floats());
					look.flags = new ArrayList<>(data.flags());
					look.strings = new ArrayList<>(data.strings());
					look.colors = new ArrayList<>(data.colors());
				}
				return look.model == null && look.floats == null ? null : look;
			} catch (RuntimeException e) {
				return null;
			}
		}

		void applyTo(ItemStack stack) {
			ResourceLocation rl = model == null ? null : ResourceLocation.tryParse(model);
			if (rl != null) {
				stack.set(DataComponents.ITEM_MODEL, rl);
			}
			if (floats != null || flags != null || strings != null || colors != null) {
				stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(orEmpty(floats), orEmpty(flags),
						orEmpty(strings), orEmpty(colors)));
			}
		}

		private static <T> List<T> orEmpty(List<T> list) {
			return list == null ? List.of() : List.copyOf(list);
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Look l && Objects.equals(model, l.model) && Objects.equals(floats, l.floats)
					&& Objects.equals(flags, l.flags) && Objects.equals(strings, l.strings) && Objects.equals(colors, l.colors);
		}

		@Override
		public int hashCode() {
			return Objects.hash(model, floats, flags, strings, colors);
		}
	}

	/** One kind of mineral picked up in a recording. */
	public static final class Item {
		/** Item id, e.g. minecraft:diamond. */
		public String id;
		/** Name as shown in game (server items keep their own name). */
		public String name;
		public long count;
		/** How it looked when picked up (server items), or null. */
		public Look look;

		String key() {
			return id + "|" + name;
		}
	}

	/** One recording, from start to end. */
	public static final class Session {
		/** When it started (milliseconds since 1970). */
		public long start;
		/** Measured time in seconds (short breaks count, long ones do not). */
		public long seconds;
		public long blocks;
		/** "기타" items (cobblestone and the like). */
		public long other;
		public List<Item> items = new ArrayList<>();

		long minerals() {
			long n = 0;
			for (Item it : items) {
				n += it.count;
			}
			return n;
		}

		long count(String itemId) {
			long n = 0;
			for (Item it : items) {
				if (itemId.equals(it.id)) {
					n += it.count;
				}
			}
			return n;
		}

		LocalDate date() {
			return Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate();
		}
	}

	/** All recordings of one day, added up. */
	static final class Day {
		final LocalDate date;
		final List<Session> sessions = new ArrayList<>();
		long seconds;
		long blocks;
		long other;
		long minerals;
		/** Minerals by kind, most first after {@link #finish()}. */
		final List<Item> items = new ArrayList<>();

		Day(LocalDate date) {
			this.date = date;
		}

		long count(String itemId) {
			long n = 0;
			for (Session s : sessions) {
				n += s.count(itemId);
			}
			return n;
		}

		private void finish() {
			Map<String, Item> byKey = new LinkedHashMap<>();
			for (Session s : sessions) {
				seconds += s.seconds;
				blocks += s.blocks;
				other += s.other;
				for (Item it : s.items) {
					Item sum = byKey.computeIfAbsent(it.key(), k -> {
						Item copy = new Item();
						copy.id = it.id;
						copy.name = it.name;
						return copy;
					});
					sum.count += it.count;
					minerals += it.count;
				}
			}
			items.addAll(byKey.values());
			items.sort((a, b) -> Long.compare(b.count, a.count));
			sessions.sort((a, b) -> Long.compare(a.start, b.start));
		}
	}

	private static final class Data {
		int version = 1;
		List<Session> sessions = new ArrayList<>();
	}

	private static final Gson GSON = new GsonBuilder().create();
	/** Icons by item key (id|name). */
	private static final Map<String, ItemStack> ICONS = new HashMap<>();
	/** The last known look of each item key: from the records, the recording going on, and the inventory. */
	private static final Map<String, Look> LOOKS = new HashMap<>();
	private static List<Session> sessions = new ArrayList<>();
	private static List<Day> days;

	private History() {
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("mining-tracker-history.json");
	}

	public static void load() {
		sessions = new ArrayList<>();
		days = null;
		Path file = path();
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				Data data = GSON.fromJson(reader, Data.class);
				if (data != null && data.sessions != null) {
					sessions = new ArrayList<>(data.sessions);
					sessions.removeIf(s -> s == null || s.items == null);
					for (Session s : sessions) {
						s.items.removeIf(it -> it == null || it.id == null || it.name == null);
					}
				}
			} catch (Exception e) {
				MiningTrackerClient.LOGGER.warn("광질 기록 파일을 읽지 못했어요: {}", e.toString());
				// keep the unreadable file: the next save would write over it
				try {
					Files.copy(file, file.resolveSibling(file.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
				} catch (Exception ignored) {
					// nothing more to do
				}
			}
		}
		for (Session s : sessions) {
			learnLooks(s);
		}
		days = null;
	}

	private static void save() {
		try {
			Path file = path();
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.createDirectories(file.getParent());
			Data data = new Data();
			data.sessions = sessions;
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(data, writer);
			}
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			MiningTrackerClient.LOGGER.warn("광질 기록을 저장하지 못했어요: {}", e.toString());
		}
	}

	static void add(Session s) {
		learnLooks(s);
		sessions.add(s);
		days = null;
		save();
	}

	static void clear() {
		sessions.clear();
		days = null;
		save();
	}

	/** The recording saved last (방금 기록), or null. */
	static Session last() {
		return sessions.isEmpty() ? null : sessions.get(sessions.size() - 1);
	}

	/** Takes back the recording saved last (방금 기록 취소). */
	static void removeLast() {
		if (!sessions.isEmpty()) {
			sessions.remove(sessions.size() - 1);
			days = null;
			save();
		}
	}

	static boolean isEmpty() {
		return sessions.isEmpty();
	}

	/** Days with recordings, newest first. */
	static List<Day> days() {
		if (days == null) {
			Map<LocalDate, Day> byDate = new HashMap<>();
			for (Session s : sessions) {
				byDate.computeIfAbsent(s.date(), Day::new).sessions.add(s);
			}
			List<Day> list = new ArrayList<>(byDate.values());
			for (Day d : list) {
				d.finish();
			}
			list.sort((a, b) -> b.date.compareTo(a.date));
			days = Collections.unmodifiableList(list);
		}
		return days;
	}

	static Day day(LocalDate date) {
		for (Day d : days()) {
			if (d.date.equals(date)) {
				return d;
			}
		}
		return null;
	}

	/** The last day before {@code date} that has recordings, or null. */
	static Day before(LocalDate date) {
		for (Day d : days()) {
			if (d.date.isBefore(date)) {
				return d;
			}
		}
		return null;
	}

	/** The item's icon, drawn the way it looked when picked up (a server's own item shows its own look). */
	static ItemStack icon(Item it) {
		return ICONS.computeIfAbsent(it.key(), key -> {
			ResourceLocation rl = ResourceLocation.tryParse(it.id);
			net.minecraft.world.item.Item item = rl == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(rl);
			if (item == Items.AIR) {
				return ItemStack.EMPTY;
			}
			ItemStack stack = new ItemStack(item);
			Look look = it.look != null ? it.look : LOOKS.get(key);
			if (look != null) {
				look.applyTo(stack);
			}
			return stack;
		});
	}

	/** Keeps the look of an item seen now (picked up, or in the inventory), so older records draw it too. */
	static void rememberLook(String key, Look look) {
		if (look != null && !look.equals(LOOKS.get(key))) {
			LOOKS.put(key, look);
			ICONS.remove(key);
		}
	}

	private static void learnLooks(Session s) {
		for (Item it : s.items) {
			rememberLook(it.key(), it.look);
		}
	}

	/** The looks of what the player carries: old records of those items get their icon right away. */
	static void learnLooks(Player player) {
		if (player == null) {
			return;
		}
		Inventory inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack != null && !stack.isEmpty()) {
				rememberLook(Tracker.keyOf(stack), Look.of(stack));
			}
		}
	}
}
