package com.miningtracker;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** The "아이템 +N (총 N개)" pickup notifications. Newest first. */
public final class ToastManager {
	/** Ticks a toast stays fully visible after its last update (20 ticks = 1 second). */
	public static final int LIFETIME = 70;
	public static final int SLIDE_IN = 4;
	public static final int SLIDE_OUT = 6;
	public static final int MAX = 5;

	public static final class Toast {
		public final String key;
		public final ItemStack icon;
		public final String name;
		public int amount;
		/** How many of this item the recording has so far. */
		public long total;
		public long born;
		public long updated;

		Toast(String key, ItemStack icon, String name, int amount, long total, long tick) {
			this.key = key;
			this.icon = icon;
			this.name = name;
			this.amount = amount;
			this.total = total;
			this.born = tick;
			this.updated = tick;
		}

		/** 0 = fully slid out to the right, 1 = fully shown. */
		public float visibility(long tick, float partialTick) {
			float age = (tick - born) + partialTick;
			float in = Math.min(1f, Math.max(0f, age / SLIDE_IN));
			float sinceUpdate = (tick - updated) + partialTick;
			float out = sinceUpdate <= LIFETIME ? 1f : Math.max(0f, 1f - (sinceUpdate - LIFETIME) / SLIDE_OUT);
			return Math.min(in, out);
		}
	}

	private final List<Toast> toasts = new ArrayList<>();

	public List<Toast> list() {
		return toasts;
	}

	public void push(String key, ItemStack icon, String name, int amount, long total, long tick) {
		for (Iterator<Toast> it = toasts.iterator(); it.hasNext(); ) {
			Toast t = it.next();
			if (t.key.equals(key) && tick - t.updated <= LIFETIME) {
				it.remove();
				t.amount += amount;
				t.total = total;
				t.updated = tick;
				toasts.add(0, t);
				return;
			}
		}
		toasts.add(0, new Toast(key, icon, name, amount, total, tick));
		while (toasts.size() > MAX) {
			toasts.remove(toasts.size() - 1);
		}
	}

	public void tick(long tick) {
		toasts.removeIf(t -> tick - t.updated > LIFETIME + SLIDE_OUT);
	}

	public void clear() {
		toasts.clear();
	}
}
