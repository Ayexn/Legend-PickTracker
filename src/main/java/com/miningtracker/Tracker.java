package com.miningtracker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Counts mined blocks and picked-up items for the current game session.
 *
 * <p>Blocks: the block the player breaks is counted right away (and taken back if the server
 * puts it back), and solid blocks right next to it that disappear within a moment are counted
 * too, which covers skills that break 3x3 at once.
 *
 * <p>Items: a pickup is counted when the server tells the client this player picked the item up.
 * If a server plugin removes the item without that message (for example when it goes straight
 * into a storage), an item that vanishes right next to the player is counted instead.
 * Whether it went to the inventory or the item storage is decided by checking how much the
 * inventory grew shortly after.
 */
public final class Tracker {
	public static final Tracker INSTANCE = new Tracker();

	private static final int WATCH_TICKS = 30;
	private static final int PRIMARY_CHECK_TICKS = 40;
	private static final int RESOLVE_QUIET_TICKS = 8;
	private static final int RESOLVE_MAX_TICKS = 20;
	private static final int SNAPSHOT_SIZE = 16;
	private static final int FALLBACK_MAX_REACH_TICKS = 60;
	private static final int RESET_CONFIRM_TICKS = 60;

	public static final class BlockTally {
		public final Block block;
		public final String name;
		public final ItemStack icon;
		public long count;

		BlockTally(Block block) {
			this.block = block;
			this.name = block.getName().getString();
			this.icon = new ItemStack(block.asItem());
		}
	}

	public static final class ItemTally {
		public final String key;
		public final String name;
		public final ItemStack icon;
		public final boolean other;
		public long storage;
		public long inventory;

		ItemTally(String key, ItemStack proto, boolean other) {
			this.key = key;
			this.name = proto.getHoverName().getString();
			this.icon = proto.copyWithCount(1);
			this.other = other;
		}

		public long total() {
			return storage + inventory;
		}
	}

	private static final class PrimaryCheck {
		final BlockPos pos;
		final BlockState state;
		final long tick;

		PrimaryCheck(BlockPos pos, BlockState state, long tick) {
			this.pos = pos;
			this.state = state;
			this.tick = tick;
		}
	}

	private static final class NeighborWatch {
		final BlockPos pos;
		final BlockState state;
		long expires;
		long goneAt = -1;

		NeighborWatch(BlockPos pos, BlockState state, long expires) {
			this.pos = pos;
			this.state = state;
			this.expires = expires;
		}
	}

	private static final class TrackedItem {
		ItemStack proto = ItemStack.EMPTY;
		int count;
		Vec3 pos = Vec3.ZERO;
		long lastSeen;
		long reachSince = -1;
	}

	private static final class Candidate {
		final ItemStack proto;
		final int count;
		final long tick;
		final Map<Integer, Integer> nearby;

		Candidate(ItemStack proto, int count, long tick, Map<Integer, Integer> nearby) {
			this.proto = proto;
			this.count = count;
			this.tick = tick;
			this.nearby = nearby;
		}
	}

	private static final class Pending {
		final String key;
		final ItemStack proto;
		final long firstTick;
		final int baseline;
		long lastTick;
		int amount;

		Pending(String key, ItemStack proto, long firstTick, int baseline) {
			this.key = key;
			this.proto = proto;
			this.firstTick = firstTick;
			this.lastTick = firstTick;
			this.baseline = baseline;
		}
	}

	/** Counters for /광질 진단, to see which detection paths the server triggers. */
	public static final class Stats {
		public long packetPickups;
		public long fallbackPickups;
		public long mergeSkips;
		public long cleanupSkips;
		public long longReachSkips;
		public long neighborBlocks;
		public long restoredBlocks;
		public long toStorage;
		public long toInventory;
	}

	public final Stats stats = new Stats();

	// ---- session data shown on the HUD ----
	private boolean running = true;
	private long tick;
	private long activeTicks;
	private final Map<Block, BlockTally> blocks = new HashMap<>();
	private final Map<String, ItemTally> items = new HashMap<>();
	private List<BlockTally> sortedBlocks = new ArrayList<>();
	private List<ItemTally> sortedItems = new ArrayList<>();
	private boolean dirty;
	public final ToastManager toasts = new ToastManager();

	// ---- bookkeeping ----
	private ClientLevel lastLevel;
	private final List<PrimaryCheck> primaryChecks = new ArrayList<>();
	private final Map<BlockPos, NeighborWatch> watches = new HashMap<>();
	private final Map<Integer, TrackedItem> tracked = new HashMap<>();
	private final Map<Integer, Long> takenByPacket = new HashMap<>();
	private final List<Candidate> candidates = new ArrayList<>();
	private final Set<Long> cleanupTicks = new HashSet<>();
	private int discardsThisTick;
	private int farDiscardsThisTick;
	private final Map<String, Pending> pendings = new LinkedHashMap<>();
	private final Map<String, Long> lastResolved = new HashMap<>();
	private final List<Map<String, Integer>> snapshots = new ArrayList<>();
	private final long[] snapshotTicks = new long[SNAPSHOT_SIZE];
	private long resetArmedAt = Long.MIN_VALUE / 2;
	private boolean openEditorNextTick;

	private Tracker() {
		for (int i = 0; i < SNAPSHOT_SIZE; i++) {
			snapshots.add(null);
		}
	}

	// ------------------------------------------------------------------ getters

	public boolean isRunning() {
		return running;
	}

	public long tick() {
		return tick;
	}

	public long activeTicks() {
		return activeTicks;
	}

	public List<BlockTally> sortedBlocks() {
		refreshSorted();
		return sortedBlocks;
	}

	public List<ItemTally> sortedItems() {
		refreshSorted();
		return sortedItems;
	}

	public boolean isEmpty() {
		return blocks.isEmpty() && items.isEmpty();
	}

	// ------------------------------------------------------------------ controls

	public void setRunning(boolean running) {
		this.running = running;
	}

	public void reset() {
		blocks.clear();
		items.clear();
		pendings.clear();
		lastResolved.clear();
		candidates.clear();
		primaryChecks.clear();
		watches.clear();
		toasts.clear();
		activeTicks = 0;
		stats.packetPickups = 0;
		stats.fallbackPickups = 0;
		stats.mergeSkips = 0;
		stats.cleanupSkips = 0;
		stats.longReachSkips = 0;
		stats.neighborBlocks = 0;
		stats.restoredBlocks = 0;
		stats.toStorage = 0;
		stats.toInventory = 0;
		dirty = true;
	}

	public void requestEditor() {
		openEditorNextTick = true;
	}

	static void notify(Minecraft mc, String message) {
		mc.gui.setOverlayMessage(Component.literal(message), false);
	}

	// ------------------------------------------------------------------ tick

	public void onEndTick(Minecraft mc) {
		// items removed since the last tick: many at once, several far away = floor item cleanup
		if (discardsThisTick >= 8 && farDiscardsThisTick >= 5) {
			cleanupTicks.add(tick);
		}
		discardsThisTick = 0;
		farDiscardsThisTick = 0;
		tick++;

		handleKeys(mc);
		if (openEditorNextTick) {
			openEditorNextTick = false;
			if (mc.screen == null) {
				mc.setScreen(new EditHudScreen());
			}
		}

		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		if (level == null || player == null) {
			if (lastLevel != null) {
				clearWorldState();
				lastLevel = null;
			}
			return;
		}
		if (level != lastLevel) {
			clearWorldState();
			lastLevel = level;
		}
		if (running && !mc.isPaused()) {
			activeTicks++;
		}

		snapshotInventory(player);
		checkPrimaries(level);
		checkWatches(level, player);
		trackItems(level, player);
		resolveCandidates(level);
		resolvePendings(player, false);
		toasts.tick(tick);
		takenByPacket.values().removeIf(t -> tick - t > 100);
		cleanupTicks.removeIf(t -> tick - t > 20);
	}

	public void onDisconnect() {
		resolvePendings(null, true);
		clearWorldState();
		lastLevel = null;
	}

	private void clearWorldState() {
		primaryChecks.clear();
		watches.clear();
		tracked.clear();
		takenByPacket.clear();
		candidates.clear();
		for (int i = 0; i < SNAPSHOT_SIZE; i++) {
			snapshots.set(i, null);
		}
	}

	private void handleKeys(Minecraft mc) {
		while (Keys.toggleHud.consumeClick()) {
			ModConfig cfg = ModConfig.get();
			cfg.hudVisible = !cfg.hudVisible;
			ModConfig.save();
			notify(mc, cfg.hudVisible ? "광질 측정: 집계판을 켰어요" : "광질 측정: 집계판을 숨겼어요");
		}
		while (Keys.pause.consumeClick()) {
			running = !running;
			notify(mc, running ? "광질 측정: 측정을 다시 시작했어요" : "광질 측정: 일시정지했어요");
		}
		while (Keys.reset.consumeClick()) {
			if (tick - resetArmedAt <= RESET_CONFIRM_TICKS) {
				reset();
				resetArmedAt = Long.MIN_VALUE / 2;
				notify(mc, "광질 측정: 기록을 초기화했어요");
			} else {
				resetArmedAt = tick;
				notify(mc, "광질 측정: 3초 안에 한 번 더 누르면 기록을 초기화해요");
			}
		}
		while (Keys.edit.consumeClick()) {
			if (mc.screen == null) {
				mc.setScreen(new EditHudScreen());
			}
		}
	}

	// ------------------------------------------------------------------ blocks

	/** Called right after the client breaks a block (Fabric's ClientPlayerBlockBreakEvents.AFTER). */
	public void onBlockBroken(ClientLevel level, LocalPlayer player, BlockPos pos, BlockState state) {
		if (!running || state.isAir()) {
			return;
		}
		BlockPos center = pos.immutable();
		watches.remove(center);
		addBlock(state.getBlock(), 1);
		primaryChecks.add(new PrimaryCheck(center, state, tick));

		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx == 0 && dy == 0 && dz == 0) {
						continue;
					}
					BlockPos n = center.offset(dx, dy, dz);
					NeighborWatch existing = watches.get(n);
					if (existing != null) {
						existing.expires = tick + WATCH_TICKS;
						continue;
					}
					BlockState ns = level.getBlockState(n);
					if (ns.isAir() || !ns.isCollisionShapeFullBlock(level, n)) {
						continue;
					}
					watches.put(n, new NeighborWatch(n, ns, tick + WATCH_TICKS));
				}
			}
		}
	}

	/** The server can refuse a break (protected area etc.) and put the block back: undo the count. */
	private void checkPrimaries(ClientLevel level) {
		for (Iterator<PrimaryCheck> it = primaryChecks.iterator(); it.hasNext(); ) {
			PrimaryCheck c = it.next();
			if (level.getBlockState(c.pos) == c.state) {
				addBlock(c.state.getBlock(), -1);
				stats.restoredBlocks++;
				it.remove();
			} else if (tick - c.tick > PRIMARY_CHECK_TICKS) {
				it.remove();
			}
		}
	}

	/** Solid blocks next to a broken block that vanish shortly after were broken with it (3x3 skills). */
	private void checkWatches(ClientLevel level, LocalPlayer player) {
		BlockPos playerPos = player.blockPosition();
		for (Iterator<NeighborWatch> it = watches.values().iterator(); it.hasNext(); ) {
			NeighborWatch w = it.next();
			if (w.pos.distManhattan(playerPos) > 32) {
				it.remove();
				continue;
			}
			if (w.goneAt >= 0) {
				// sand/gravel: only count it if it did not just fall down
				if (tick - w.goneAt >= 2) {
					boolean fell = !level.getEntitiesOfClass(FallingBlockEntity.class, new AABB(w.pos).inflate(1.0)).isEmpty();
					if (!fell) {
						addBlock(w.state.getBlock(), 1);
						stats.neighborBlocks++;
					}
					it.remove();
				}
				continue;
			}
			BlockState current = level.getBlockState(w.pos);
			if (current == w.state) {
				if (tick > w.expires) {
					it.remove();
				}
				continue;
			}
			boolean gone = current.isAir() || current.getBlock() instanceof LiquidBlock;
			if (!gone || !running) {
				it.remove();
				continue;
			}
			if (w.state.getBlock() instanceof FallingBlock) {
				w.goneAt = tick;
				continue;
			}
			addBlock(w.state.getBlock(), 1);
			stats.neighborBlocks++;
			it.remove();
		}
	}

	private void addBlock(Block block, int delta) {
		BlockTally t = blocks.get(block);
		if (t == null) {
			if (delta <= 0) {
				return;
			}
			t = new BlockTally(block);
			blocks.put(block, t);
		}
		t.count = Math.max(0, t.count + delta);
		if (t.count == 0) {
			blocks.remove(block);
		}
		dirty = true;
	}

	// ------------------------------------------------------------------ items

	/** Called (on the main thread) when the server says an entity picked up an item. */
	public void onTakeItemPacket(int itemEntityId, int collectorId, int amount) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}
		Entity entity = mc.level.getEntity(itemEntityId);
		if (!(entity instanceof ItemEntity itemEntity)) {
			return;
		}
		takenByPacket.put(itemEntityId, tick);
		if (collectorId != mc.player.getId() || !running) {
			return;
		}
		ItemStack stack = itemEntity.getItem();
		if (stack.isEmpty()) {
			return;
		}
		stats.packetPickups++;
		collect(stack.copyWithCount(1), amount);
	}

	/** Called when an entity leaves the client world (Fabric's ClientEntityEvents.ENTITY_UNLOAD). */
	public void onEntityUnload(Entity entity, ClientLevel level) {
		if (!(entity instanceof ItemEntity item)) {
			return;
		}
		TrackedItem info = tracked.remove(item.getId());
		if (item.getRemovalReason() != Entity.RemovalReason.DISCARDED) {
			return;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}
		ItemStack stack = item.getItem();
		if (stack.isEmpty() || takenByPacket.containsKey(item.getId())) {
			return; // emptied by a pickup message (already counted, or someone else's pickup)
		}
		discardsThisTick++;
		if (item.distanceToSqr(player) > 64.0) {
			farDiscardsThisTick++;
			return;
		}
		if (!running) {
			return;
		}
		if (!item.getBoundingBox().intersects(reachBox(player))) {
			return;
		}
		if (info != null && info.reachSince >= 0 && tick - info.reachSince > FALLBACK_MAX_REACH_TICKS) {
			stats.longReachSkips++;
			return; // it lay next to the player for a while (full inventory) and was cleaned up
		}
		// remember same items close by: if one of them grows, this one merged into it
		Map<Integer, Integer> nearby = new HashMap<>();
		for (Map.Entry<Integer, TrackedItem> e : tracked.entrySet()) {
			TrackedItem other = e.getValue();
			if (other.pos.distanceToSqr(item.position()) <= 2.25
					&& ItemStack.isSameItemSameComponents(other.proto, stack)) {
				nearby.put(e.getKey(), other.count);
			}
		}
		candidates.add(new Candidate(stack.copyWithCount(1), stack.getCount(), tick, nearby));
	}

	private static AABB reachBox(Player player) {
		return player.getBoundingBox().inflate(1.5, 1.0, 1.5);
	}

	private void trackItems(ClientLevel level, LocalPlayer player) {
		AABB reach = reachBox(player);
		for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(8.0))) {
			TrackedItem t = tracked.computeIfAbsent(e.getId(), id -> new TrackedItem());
			ItemStack stack = e.getItem();
			if (!stack.isEmpty()) {
				t.proto = stack.copyWithCount(1);
				t.count = stack.getCount();
			}
			t.pos = e.position();
			t.lastSeen = tick;
			if (e.getBoundingBox().intersects(reach)) {
				if (t.reachSince < 0) {
					t.reachSince = tick;
				}
			} else {
				t.reachSince = -1;
			}
		}
		tracked.values().removeIf(t -> tick - t.lastSeen > 40);
	}

	private void resolveCandidates(ClientLevel level) {
		for (Iterator<Candidate> it = candidates.iterator(); it.hasNext(); ) {
			Candidate c = it.next();
			if (tick - c.tick < 2) {
				continue;
			}
			it.remove();
			if (cleanupTicks.contains(c.tick)) {
				stats.cleanupSkips++;
				continue;
			}
			int grown = 0;
			for (Map.Entry<Integer, Integer> e : c.nearby.entrySet()) {
				Entity other = level.getEntity(e.getKey());
				if (other instanceof ItemEntity otherItem && !otherItem.isRemoved()) {
					grown += Math.max(0, otherItem.getItem().getCount() - e.getValue());
				}
			}
			if (grown >= c.count) {
				stats.mergeSkips++;
				continue; // merged into a neighbouring item, not picked up
			}
			stats.fallbackPickups++;
			collect(c.proto, c.count);
		}
	}

	private void collect(ItemStack proto, int amount) {
		if (amount <= 0) {
			return;
		}
		String key = keyOf(proto);
		Pending p = pendings.get(key);
		if (p == null) {
			long since = Math.max(tick - 2, lastResolved.getOrDefault(key, Long.MIN_VALUE));
			p = new Pending(key, proto, tick, countAt(since, key));
			pendings.put(key, p);
		}
		p.amount += amount;
		p.lastTick = tick;
	}

	/** Decide storage vs inventory from how much the inventory grew, then add to the tally. */
	private void resolvePendings(Player player, boolean force) {
		for (Iterator<Pending> it = pendings.values().iterator(); it.hasNext(); ) {
			Pending p = it.next();
			if (!force && tick - p.lastTick < RESOLVE_QUIET_TICKS && tick - p.firstTick < RESOLVE_MAX_TICKS) {
				continue;
			}
			it.remove();
			int inventory = 0;
			if (player != null) {
				int grew = countNow(player, p.key) - p.baseline;
				inventory = Math.max(0, Math.min(p.amount, grew));
			}
			int storage = p.amount - inventory;

			ItemTally t = items.get(p.key);
			if (t == null) {
				t = new ItemTally(p.key, p.proto, isOther(p.proto));
				items.put(p.key, t);
			}
			t.storage += storage;
			t.inventory += inventory;
			stats.toStorage += storage;
			stats.toInventory += inventory;
			dirty = true;
			lastResolved.put(p.key, tick);

			ModConfig cfg = ModConfig.get();
			if (!t.other || cfg.toastOthers) {
				if (storage > 0) {
					toasts.push(p.key, t.icon, t.name, true, storage, tick);
				}
				if (inventory > 0) {
					toasts.push(p.key, t.icon, t.name, false, inventory, tick);
				}
			}
		}
	}

	private static boolean isOther(ItemStack stack) {
		if (stack.has(DataComponents.CUSTOM_NAME)) {
			return false; // server items with their own name are never filler
		}
		return ModConfig.get().isOtherItem(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
	}

	static String keyOf(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()) + "|" + stack.getHoverName().getString();
	}

	// ------------------------------------------------------------------ inventory snapshots

	private void snapshotInventory(Player player) {
		Map<String, Integer> counts = new HashMap<>();
		Inventory inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty()) {
				counts.merge(keyOf(stack), stack.getCount(), Integer::sum);
			}
		}
		int index = (int) (tick % SNAPSHOT_SIZE);
		snapshots.set(index, counts);
		snapshotTicks[index] = tick;
	}

	/** Inventory count of {@code key} at the newest snapshot not later than {@code wantedTick}. */
	private int countAt(long wantedTick, String key) {
		Map<String, Integer> best = null;
		long bestTick = Long.MIN_VALUE;
		Map<String, Integer> oldest = null;
		long oldestTick = Long.MAX_VALUE;
		for (int i = 0; i < SNAPSHOT_SIZE; i++) {
			Map<String, Integer> snap = snapshots.get(i);
			if (snap == null) {
				continue;
			}
			long t = snapshotTicks[i];
			if (t <= wantedTick && t > bestTick) {
				best = snap;
				bestTick = t;
			}
			if (t < oldestTick) {
				oldest = snap;
				oldestTick = t;
			}
		}
		Map<String, Integer> use = best != null ? best : oldest;
		return use == null ? 0 : use.getOrDefault(key, 0);
	}

	private static int countNow(Player player, String key) {
		int total = 0;
		Inventory inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty() && keyOf(stack).equals(key)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	// ------------------------------------------------------------------ sorting

	private void refreshSorted() {
		if (!dirty) {
			return;
		}
		List<BlockTally> b = new ArrayList<>(blocks.values());
		b.sort(Comparator.comparingLong((BlockTally t) -> t.count).reversed().thenComparing(t -> t.name));
		List<ItemTally> i = new ArrayList<>(items.values());
		i.sort(Comparator.comparingLong(ItemTally::total).reversed().thenComparing(t -> t.name));
		sortedBlocks = b;
		sortedItems = i;
		dirty = false;
	}
}
