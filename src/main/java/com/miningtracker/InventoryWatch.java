package com.miningtracker;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Items the server puts straight into the inventory (on 띵타이쿤, server minerals such as 토파즈 never touch the
 * ground): while recording, the inventory's count of each item is compared from tick to tick, and what grew counts
 * as picked up, unless something already counted explains it (a storage notice or a pickup of the same item).
 *
 * <p>Only the client's own copy of the inventory is read; nothing is sent to the server.
 *
 * <p>Not counted: anything while a screen is open (the player moving, buying or taking items) and a moment after;
 * growth in the same tick as something else went down (the server turned one item into another); an item that
 * went down and right after came back to what it was (the server undid a placement or a drop); what was just
 * taken out of the storage; the inventory being filled after joining, respawning or changing worlds.
 */
final class InventoryWatch {
	/** How long growth waits for a storage notice or a pickup to explain it. */
	static final int GRACE_TICKS = 30;
	/** After a screen closes, what was moved or bought while it was open may still be arriving from the server. */
	static final int QUIET_TICKS = 20;
	/** A new inventory (joined, respawned, changed worlds) is filled by the server over the next moment. */
	static final int NEW_INVENTORY_TICKS = 60;
	/** How soon an item that went down may come back to what it was without counting. */
	static final int GIVE_BACK_TICKS = 20;
	/** How long after "N개 인출" what comes into the inventory is what was taken out. */
	static final int WITHDRAW_TICKS = 45;
	/**
	 * How long something counted and the inventory growing still belong together. A pickup's amount is sometimes
	 * right only a little later (a stack whose new count had not been seen yet, made up by the next one).
	 */
	static final int MATCH_TICKS = 100;

	/** What the watch needs from the tracker. */
	interface Counter {
		/** Counts {@code amount} of {@code proto} as picked up (board, recording, pickup toast). */
		void count(ItemStack proto, int amount);

		/** A line for /광질 진단 최근. */
		void note(String text);
	}

	/** Some amount of an item, from a tick on. */
	private static final class Part {
		final long tick;
		int amount;

		Part(long tick, int amount) {
			this.tick = tick;
			this.amount = amount;
		}
	}

	/** An item went down from {@code from} at {@code tick}. */
	private record Dip(int from, long tick) {
	}

	private final Counter counter;
	/** The inventory last read: a new one (joined, respawned, changed worlds) is not compared with the old one. */
	private Inventory source;
	/** Each item's count at the last tick, or null when there is nothing to compare with yet. */
	private Map<String, Integer> last;
	private final Map<String, ItemStack> protos = new HashMap<>();
	/** Counted already (storage notice, pickup) and not seen in the inventory (yet, or ever: the storage took it). */
	private final Map<String, Deque<Part>> pending = new HashMap<>();
	/** Growth nothing has explained (yet): counts once it has waited GRACE_TICKS. */
	private final Map<String, Deque<Part>> growth = new HashMap<>();
	/** Growth counted here: when a notice or a pickup of it comes later, that is not counted again. */
	private final Map<String, Deque<Part>> credits = new HashMap<>();
	private final Map<String, Dip> dips = new HashMap<>();
	private long quietUntil = Long.MIN_VALUE;
	/** Why it is quiet now (for the notes). */
	private String quietWhy = "";
	/** Items just taken out of the storage ("N개 인출") that may still arrive, until {@link #withdrawUntil}. */
	private int withdrawLeft;
	private long withdrawUntil = Long.MIN_VALUE;

	/** Times growth was counted, and how many items. */
	long counted;
	long countedItems;
	/** Times growth was left out (a screen open, something else went down, it only came back, a new inventory...). */
	long skipped;
	/** Items that came in right after being taken out of the storage. */
	long withdrawnItems;

	InventoryWatch(Counter counter) {
		this.counter = counter;
	}

	/**
	 * Once a tick. {@code counting}: recording here; otherwise nothing is compared. {@code screenOpen}: the player
	 * has a screen (inventory, chest, shop, chat) open.
	 */
	void tick(Inventory inventory, long tick, boolean counting, boolean screenOpen) {
		if (screenOpen) {
			quiet(tick + QUIET_TICKS, "창이 열려 있었음 (상자·상점·채팅 등)");
		}
		if (inventory != source) {
			// joined, respawned or changed worlds: the server fills the new inventory now, which is not picking up
			source = inventory;
			last = null;
			quiet(tick + NEW_INVENTORY_TICKS, "접속·리스폰·채널 이동 직후");
		}
		if (inventory == null || !counting) {
			last = null;
		} else {
			Map<String, Integer> now = counts(inventory);
			if (last != null) {
				compare(last, now, tick);
			}
			last = now;
			protos.keySet().removeIf(key -> !now.containsKey(key) && !growth.containsKey(key));
		}
		settle(tick, false);
	}

	private void quiet(long until, String why) {
		if (until >= quietUntil) {
			quietUntil = until;
			quietWhy = why;
		}
	}

	/** Counted already (a storage notice): when it shows up in the inventory, it is not counted again. */
	void pickedUp(String key, int amount, long tick) {
		int left = amount - take(growth, key, amount);
		if (left > 0) {
			pending.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(new Part(tick, left));
		}
	}

	/**
	 * Something was found to count (a storage notice or a pickup): how much of it was already counted here a moment
	 * ago (it showed up in the inventory first). That part is not counted again.
	 */
	int alreadyCounted(String key, int amount) {
		return take(credits, key, amount);
	}

	/** Growth of {@code key} nothing has explained yet. */
	int unexplained(String key) {
		Deque<Part> parts = growth.get(key);
		int sum = 0;
		if (parts != null) {
			for (Part p : parts) {
				sum += p.amount;
			}
		}
		return sum;
	}

	/** "N개 인출": what comes into the inventory now was in the storage, not picked up. */
	void withdrawn(int amount, long tick) {
		int left = amount;
		// it may have shown up just before the notice
		for (Iterator<Map.Entry<String, Deque<Part>>> it = growth.entrySet().iterator(); it.hasNext() && left > 0; ) {
			Map.Entry<String, Deque<Part>> e = it.next();
			int took = take(e.getValue(), left);
			if (took > 0) {
				left -= took;
				withdrawnItems += took;
				counter.note(nameOf(e.getKey()) + " " + took + "개 · 안 셈: 저장소에서 인출한 것");
			}
			if (e.getValue().isEmpty()) {
				it.remove();
			}
		}
		withdrawLeft = left;
		withdrawUntil = tick + WITHDRAW_TICKS;
	}

	/** An item with this name in the inventory lately, or null. */
	ItemStack protoNamed(String name) {
		for (ItemStack proto : protos.values()) {
			if (proto.getHoverName().getString().equals(name)) {
				return proto;
			}
		}
		return null;
	}

	/** The board is cleared: what grew and is still waiting counts now, for the recording so far. */
	void flush(long tick) {
		settle(tick, true);
	}

	/** The recording ends: what grew and is still waiting counts now. */
	void finish(long tick) {
		settle(tick, true);
		pending.clear();
		credits.clear();
		dips.clear();
		withdrawLeft = 0;
	}

	/** The player moved to another channel: what the server fills in now is not picked up. */
	void newPlace(long tick, String why) {
		last = null;
		quiet(tick + NEW_INVENTORY_TICKS, why);
	}

	/** The player is gone for now (between worlds): the next inventory is a new one. */
	void forget() {
		source = null;
		last = null;
	}

	/** Leaving the server: start over. */
	void reset() {
		forget();
		pending.clear();
		growth.clear();
		credits.clear();
		dips.clear();
		protos.clear();
		withdrawLeft = 0;
	}

	/** For clearing the board: the numbers in /광질 진단 start over too. */
	void clearStats() {
		counted = 0;
		countedItems = 0;
		skipped = 0;
		withdrawnItems = 0;
	}

	private void compare(Map<String, Integer> before, Map<String, Integer> now, long tick) {
		Map<String, Integer> grew = new HashMap<>();
		Map<String, Integer> fell = new HashMap<>();
		Set<String> keys = new HashSet<>(now.keySet());
		keys.addAll(before.keySet());
		for (String key : keys) {
			int change = now.getOrDefault(key, 0) - before.getOrDefault(key, 0);
			if (change > 0) {
				grew.put(key, change);
			} else if (change < 0) {
				fell.put(key, -change);
			}
		}
		renamed(grew, fell);
		for (String key : fell.keySet()) {
			Dip d = dips.get(key);
			if (d == null || tick - d.tick() > GIVE_BACK_TICKS) {
				dips.put(key, new Dip(before.getOrDefault(key, 0), tick));
			}
		}
		if (grew.isEmpty()) {
			return;
		}
		String why = before.isEmpty() ? "인벤토리가 새로 채워짐 (접속·리스폰·채널 이동)"
				: tick < quietUntil ? quietWhy
				: !fell.isEmpty() ? "같은 순간 줄어든 게 있어서 바뀐 것으로 봄 (줄어든 것: " + names(fell) + ")"
				: null;
		if (why != null) {
			skipped++;
			counter.note(names(grew) + " · 인벤토리에 들어왔지만 안 셈: " + why);
			return;
		}
		for (Map.Entry<String, Integer> e : grew.entrySet()) {
			String key = e.getKey();
			Dip d = dips.get(key);
			if (d != null && tick - d.tick() <= GIVE_BACK_TICKS && now.getOrDefault(key, 0) == d.from()) {
				dips.remove(key);
				skipped++;
				counter.note(nameOf(key) + " " + e.getValue() + "개 · 안 셈: 줄었다가 원래대로 돌아옴 (서버가 되돌린 것)");
				continue;
			}
			grew(key, e.getValue(), tick);
		}
	}

	/**
	 * A stack that only changed its name (a tool that shows how much it mined, say) went down under the old name
	 * and grew under the new one in the same tick: neither counts.
	 */
	private static void renamed(Map<String, Integer> grew, Map<String, Integer> fell) {
		for (Iterator<Map.Entry<String, Integer>> f = fell.entrySet().iterator(); f.hasNext(); ) {
			Map.Entry<String, Integer> down = f.next();
			String id = idOf(down.getKey());
			for (Iterator<Map.Entry<String, Integer>> g = grew.entrySet().iterator(); g.hasNext(); ) {
				Map.Entry<String, Integer> up = g.next();
				if (up.getValue().equals(down.getValue()) && idOf(up.getKey()).equals(id)) {
					g.remove();
					f.remove();
					break;
				}
			}
		}
	}

	/** The item's id part of a key (id|name). */
	private static String idOf(String key) {
		int bar = key.indexOf('|');
		return bar < 0 ? key : key.substring(0, bar);
	}

	/** The item's name part of a key (id|name). */
	static String nameOf(String key) {
		int bar = key.indexOf('|');
		return bar < 0 ? key : key.substring(bar + 1);
	}

	/** "다이아몬드 2개, 석탄 1개". */
	private static String names(Map<String, Integer> amounts) {
		List<String> parts = new ArrayList<>();
		for (Map.Entry<String, Integer> e : amounts.entrySet()) {
			parts.add(nameOf(e.getKey()) + " " + e.getValue() + "개");
		}
		parts.sort(null);
		return String.join(", ", parts);
	}

	private void grew(String key, int amount, long tick) {
		int explained = take(pending, key, amount);
		if (explained > 0) {
			counter.note(nameOf(key) + " " + explained + "개 · 인벤토리에 들어옴, 이미 셈");
		}
		int left = amount - explained;
		if (left > 0 && withdrawLeft > 0 && tick <= withdrawUntil) {
			int out = Math.min(left, withdrawLeft);
			withdrawLeft -= out;
			withdrawnItems += out;
			left -= out;
			counter.note(nameOf(key) + " " + out + "개 · 안 셈: 저장소에서 인출한 것");
		}
		if (left > 0) {
			growth.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(new Part(tick, left));
		}
	}

	/** Counts growth that waited long enough (all of it when {@code all}); forgets old counts and dips. */
	private void settle(long tick, boolean all) {
		for (Iterator<Map.Entry<String, Deque<Part>>> it = growth.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<String, Deque<Part>> e = it.next();
			Deque<Part> parts = e.getValue();
			while (!parts.isEmpty() && (all || tick - parts.peekFirst().tick >= GRACE_TICKS)) {
				Part p = parts.pollFirst();
				ItemStack proto = protos.get(e.getKey());
				if (proto != null) {
					counted++;
					countedItems += p.amount;
					counter.count(proto, p.amount);
					counter.note(nameOf(e.getKey()) + " +" + p.amount + " · 인벤토리로 바로 들어옴");
					credits.computeIfAbsent(e.getKey(), k -> new ArrayDeque<>()).addLast(new Part(tick, p.amount));
				}
			}
			if (parts.isEmpty()) {
				it.remove();
			}
		}
		expire(pending, tick);
		expire(credits, tick);
		dips.values().removeIf(d -> tick - d.tick() > GIVE_BACK_TICKS);
		if (tick > withdrawUntil) {
			withdrawLeft = 0;
		}
	}

	private static void expire(Map<String, Deque<Part>> parts, long tick) {
		for (Iterator<Deque<Part>> it = parts.values().iterator(); it.hasNext(); ) {
			Deque<Part> p = it.next();
			while (!p.isEmpty() && tick - p.peekFirst().tick >= MATCH_TICKS) {
				p.pollFirst();
			}
			if (p.isEmpty()) {
				it.remove();
			}
		}
	}

	/** Takes up to {@code amount} of {@code key} out of {@code from}, oldest first; how much it took. */
	private static int take(Map<String, Deque<Part>> from, String key, int amount) {
		Deque<Part> parts = from.get(key);
		if (parts == null) {
			return 0;
		}
		int taken = take(parts, amount);
		if (parts.isEmpty()) {
			from.remove(key);
		}
		return taken;
	}

	private static int take(Deque<Part> parts, int amount) {
		int taken = 0;
		while (!parts.isEmpty() && taken < amount) {
			Part p = parts.peekFirst();
			int t = Math.min(p.amount, amount - taken);
			p.amount -= t;
			taken += t;
			if (p.amount == 0) {
				parts.pollFirst();
			}
		}
		return taken;
	}

	/** Each item's count over the whole inventory (the same item in several slots adds up). */
	private Map<String, Integer> counts(Inventory inventory) {
		Map<String, Integer> counts = new HashMap<>();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty()) {
				continue;
			}
			String key = Tracker.keyOf(stack);
			counts.merge(key, stack.getCount(), Integer::sum);
			protos.putIfAbsent(key, stack.copyWithCount(1));
		}
		return counts;
	}
}
