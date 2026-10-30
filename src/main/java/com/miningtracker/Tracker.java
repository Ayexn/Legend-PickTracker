package com.miningtracker;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
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
 * Counts mined blocks and picked-up items while a recording (광질 기록) is running.
 *
 * <p>Automatic mode: a recording starts when the player breaks stone or ore with a pickaxe in the
 * wild world (야생), and ends after a set time without mining. Manual mode: F7 starts and ends it.
 *
 * <p>Blocks: the block the player breaks is counted right away (and taken back if the server puts it
 * back, unless the server breaks it again itself right after); solid blocks right next to it that
 * disappear within a moment are counted too, which covers skills that break 3x3 at once.
 *
 * <p>Items: a pickup is counted when the server tells the client this player picked the item up. If a
 * server plugin removes the item without that message (for example when it goes straight into the item
 * storage), an item that vanishes next to the player is counted instead, unless it merged into another
 * item, was picked up by someone closer, burned, or had been lying in pickup range for a long time
 * (then it was cleaned up, not picked up). Merges are told apart by amount: an item that grew nearby
 * explains at most as many vanished items as it grew by, and whatever is left was picked up.
 *
 * <p>Magnet pets pull items in from up to 5 blocks, and the game may show such an item where it was rather than
 * where the pet took it. So an item that vanishes within that range counts as well, merges taken out the same
 * way. One that had been lying there a long time counts only once items keep coming in from a distance (a pet
 * at work); without a pet it was cleaned up. Merges are matched on the side of whichever player is closer, so
 * another player mining next to us neither takes our merges nor gives us theirs.
 */
public final class Tracker {
	public static final Tracker INSTANCE = new Tracker();

	private static final int WATCH_TICKS = 30;
	private static final int PRIMARY_CHECK_TICKS = 40;
	private static final int RESET_CONFIRM_TICKS = 60;

	// ---- pickup detection when the server sends no pickup message ----
	/** An item that was next to the player this recently still counts (the server is a little behind). */
	private static final int NEAR_RECENT_TICKS = 10;
	/** Items younger than this (just dropped) count from a bit further away, where positions can differ. */
	private static final int FRESH_TICKS = 200;
	/**
	 * In pickup range this long without being picked up: the server would not take it (full inventory),
	 * so when it vanishes it was cleaned up. Long enough to ride out server lag spikes.
	 */
	private static final int PICKUP_RANGE_LYING_TICKS = 600;
	/** Next to the player this long (but not in pickup range): lying around, not being picked up. */
	private static final int NEAR_LYING_TICKS = 300;
	/** Came into pickup range this recently: a real pickup even if it lay nearby for a long time. */
	private static final int JUST_REACHED_TICKS = 40;
	/**
	 * A merge shows up as the other item growing after this one vanished: the server sends the removal right
	 * away and the new count on its next tick. Look for that growth from a tick before to a few ticks after.
	 */
	private static final int MERGE_WINDOW_BEFORE = 1;
	private static final int MERGE_WINDOW_AFTER = 5;
	/** Wait this long before deciding, so a merge has shown up as another item growing. */
	private static final int RESOLVE_DELAY_TICKS = MERGE_WINDOW_AFTER + 1;
	/**
	 * When an item of ours vanished next to a merge, wait until every item that vanished around the same time
	 * without being ours has claimed its merge, so the merge is not taken for ours.
	 */
	private static final int SETTLE_TICKS = RESOLVE_DELAY_TICKS + MERGE_WINDOW_BEFORE + MERGE_WINDOW_AFTER;
	/**
	 * How far apart items can merge, for items that vanished by the player. Vanilla merges items within 0.75
	 * blocks and servers can set it higher (spigot.yml merge-radius); also, the game can show an item behind
	 * where the server has it (a magnet pet moving it). Merges are matched by amount, so looking further does not
	 * make pickups disappear.
	 */
	private static final double MERGE_REACH = 10.0;
	/** The same for items that vanished away from the player (not ours), so they do not take our merges. */
	private static final double FAR_MERGE_REACH = 4.5;
	private static final double MERGE_REACH_Y = 3.0;
	/** Magnet pets pull items in from 5 blocks: pickups can vanish this far from the player (with a margin). */
	private static final double MAGNET_REACH = 5.5;
	private static final double MAGNET_REACH_Y = 3.0;
	/** An item that was within that range this recently still counts as pulled in when it vanishes. */
	private static final int MAGNET_RECENT_TICKS = 30;
	/** Within that range this long without coming to the player: not being pulled in, so not picked up. */
	private static final int MAGNET_LYING_TICKS = 100;
	/** A magnet pet is taken to be at work after this many items came in from a distance, at different moments... */
	private static final int MAGNET_CONFIRM = 3;
	/** ... within this time ... */
	private static final int MAGNET_WINDOW_TICKS = 600;
	/** ... and is still taken to be at work this long after the last one. */
	private static final int MAGNET_KEEP_TICKS = 2400;
	/** Items are followed this far from the player, so the item another one merged into is seen too. */
	private static final double ITEM_TRACK_RANGE = 16.0;
	/** Count increases are kept this long for matching. */
	private static final int GROWTH_KEEP_TICKS = 40;
	/** A removal this soon after a pickup message belongs to that pickup. */
	private static final int TAKEN_RECENT_TICKS = 20;
	/** A pickup message this soon after the item vanished still counts (some servers send it late). */
	private static final int LATE_TAKE_TICKS = 10;
	/** How far the merge matching looks for a better arrangement (items visited). */
	private static final int MATCH_SEARCH_LIMIT = 40;
	/** Short breaks while mining (up to 10 seconds) still count as mining time. */
	private static final int ACTIVE_GRACE_TICKS = 200;
	private static final int SUMMARY_BLOCKS = 5;
	private static final int SUMMARY_ITEMS = 8;

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
		public long count;

		ItemTally(String key, ItemStack proto, boolean other) {
			this.key = key;
			this.name = proto.getHoverName().getString();
			this.icon = proto.copyWithCount(1);
			this.other = other;
		}
	}

	/** Counters for /광질 진단, to see which detection paths the server triggers. */
	public static final class Stats {
		public long packetPickups;
		/** Pickup messages that came after the item had already vanished (part of packetPickups). */
		public long latePacketPickups;
		public long fallbackPickups;
		public long mergeSkips;
		public long cleanupSkips;
		public long longReachSkips;
		/** Vanished within 2.5 blocks but was neither next to the player nor freshly dropped. */
		public long notNearSkips;
		public long otherPlayerSkips;
		public long lavaSkips;
		public long neighborBlocks;
		public long restoredBlocks;
		/** Put back by the server, then broken by the server right after: counted again. */
		public long rebrokenBlocks;
		/** Vanished within magnet range, away from the player, and counted (a magnet pet pulled them in). */
		public long magnetPickups;
		/** Old items that vanished within magnet range while no pet was seen at work: not counted. */
		public long magnetUnconfirmed;
		/**
		 * When an item grew (something merged into it), how far away the closest same item that vanished then
		 * was: up to 1 block, 1-2, 2-3, more. Shows how far this server merges items (vanilla: under 1 block).
		 */
		public final long[] mergeDistance = new long[4];
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
		/** A block we broke that the server put back: if the server breaks it right after, it counts again. */
		final boolean restored;
		long expires;
		long goneAt = -1;

		NeighborWatch(BlockPos pos, BlockState state, long expires, boolean restored) {
			this.pos = pos;
			this.state = state;
			this.expires = expires;
			this.restored = restored;
		}
	}

	/** What we know about an item entity near the player (kept a few seconds after it vanishes). */
	private static final class TrackedItem {
		final int id;
		ItemStack proto = ItemStack.EMPTY;
		int count;
		Vec3 pos = Vec3.ZERO;
		long lastSeen;
		/** Last tick it was next to the player (near box). */
		long lastNear = Long.MIN_VALUE / 2;
		/** Continuously next to the player since this tick, or -1. */
		long nearSince = -1;
		/** Continuously in pickup range since this tick, or -1. */
		long pickupRangeSince = -1;
		/** Last tick it was within magnet range, and continuously since when (or -1). */
		long lastInMagnetRange = Long.MIN_VALUE / 2;
		long magnetRangeSince = -1;
		long removedAt = -1;

		TrackedItem(int id) {
			this.id = id;
		}

		/** Returns the increase if the count went up (something merged into it), else null. */
		Growth observeCount(int newCount, long now) {
			Growth grew = count > 0 && newCount > count ? new Growth(this, now, newCount - count) : null;
			count = newCount;
			return grew;
		}
	}

	/** A count increase of an item: something merged into it. */
	private static final class Growth {
		final TrackedItem item;
		final long tick;
		final int amount;
		/** Where the item was when it grew. */
		final Vec3 pos;
		/** It was closer to this player than to any other player then. */
		final boolean mine;
		/** The vanished items this increase explains, and how much of it each one uses. */
		final Map<Candidate, Integer> users = new LinkedHashMap<>();
		int used;

		Growth(TrackedItem item, long tick, int amount) {
			this.item = item;
			this.tick = tick;
			this.amount = amount;
			this.pos = item.pos;
			this.mine = closestToMe(item.pos);
		}

		int free() {
			return amount - used;
		}

		void use(Candidate c, int n) {
			users.merge(c, n, Integer::sum);
			used += n;
		}

		void release(Candidate c, int n) {
			int left = users.getOrDefault(c, 0) - n;
			if (left > 0) {
				users.put(c, left);
			} else {
				users.remove(c);
			}
			used -= n;
		}
	}

	/** An item that vanished next to the player without a pickup message, waiting to be judged. */
	private static final class Candidate {
		final int entityId;
		final ItemStack proto;
		final int count;
		final long tick;
		final Vec3 pos;
		/**
		 * false: an item that vanished nearby but was not picked up by us. It is never counted, it only claims
		 * the increase of the item it merged into, so that increase cannot be mistaken for another item's merge.
		 */
		final boolean counts;
		/** Vanished within magnet range, away from the player. */
		boolean byMagnet;
		/** It had been on screen only a short while (just dropped, or just came into view). */
		boolean fresh;
		/** It was closer to this player than to any other player when it vanished. */
		boolean mine = true;

		Candidate(int entityId, ItemStack proto, int count, long tick, Vec3 pos, boolean counts) {
			this.entityId = entityId;
			this.proto = proto;
			this.count = count;
			this.tick = tick;
			this.pos = pos;
			this.counts = counts;
		}

		Candidate shadow() {
			Candidate s = new Candidate(entityId, proto, count, tick, pos, false);
			s.mine = mine;
			return s;
		}
	}

	/** What this recording added (for the statistics): kept apart from the board, which may continue from before. */
	private static final class SessionLog {
		final long start = System.currentTimeMillis();
		long ticks;
		long blocks;
		long other;
		final Map<String, History.Item> items = new LinkedHashMap<>();

		boolean isEmpty() {
			return blocks <= 0 && other <= 0 && items.isEmpty();
		}

		History.Session toSession() {
			History.Session s = new History.Session();
			s.start = start;
			s.seconds = ticks / 20;
			s.blocks = Math.max(0, blocks);
			s.other = other;
			s.items = new ArrayList<>(items.values());
			return s;
		}
	}

	public final Stats stats = new Stats();
	public final ToastManager toasts = new ToastManager();

	// ---- recording state ----
	private long tick;
	private boolean recording;
	private boolean manualSession;
	private long lastMineTick;
	private long activeTicks;
	private final Map<Block, BlockTally> blocks = new HashMap<>();
	private final Map<String, ItemTally> items = new HashMap<>();
	private List<BlockTally> sortedBlocks = new ArrayList<>();
	private List<ItemTally> sortedItems = new ArrayList<>();
	private boolean dirty;
	private SessionLog session;

	// ---- bookkeeping ----
	private ClientLevel lastLevel;
	private final List<PrimaryCheck> primaryChecks = new ArrayList<>();
	private final Map<BlockPos, NeighborWatch> watches = new HashMap<>();
	private final Map<Integer, TrackedItem> tracked = new HashMap<>();
	private final List<Growth> recentGrowth = new ArrayList<>();
	/** Items that vanished nearby lately (for the merge distance in /광질 진단). */
	private final List<Candidate> recentVanished = new ArrayList<>();
	private final Map<Integer, Long> takenByPacket = new HashMap<>();
	private final List<Candidate> candidates = new ArrayList<>();
	private final Set<Long> cleanupTicks = new HashSet<>();
	private int discardsThisTick;
	private int farDiscardsThisTick;
	private long resetArmedAt = Long.MIN_VALUE / 2;
	private boolean openSettingsNextTick;
	private boolean openEditorNextTick;
	private boolean openStatsNextTick;
	/** Old items pulled in from a distance before a magnet pet was seen at work: counted once it is. */
	private final List<Waiting> magnetWaiting = new ArrayList<>();
	/** When items came in from a distance lately (one entry per tick). */
	private final ArrayDeque<Long> magnetTimes = new ArrayDeque<>();
	/** A magnet pet is taken to be at work until this tick. */
	private long magnetUntil = Long.MIN_VALUE / 2;

	/** A pickup waiting for a magnet pet to be seen at work. */
	private record Waiting(ItemStack proto, int amount, long tick) {
	}

	private Tracker() {
	}

	// ------------------------------------------------------------------ getters

	public boolean isRecording() {
		return recording;
	}

	/** Started with F7 or /광질 시작 (rather than automatically). */
	public boolean isManualSession() {
		return manualSession;
	}

	/** Automatic recording, but nothing mined for a little while (shown as "쉬는 중"). */
	public boolean isResting() {
		return recording && ModConfig.get().autoMode && tick - lastMineTick > ACTIVE_GRACE_TICKS;
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

	// ------------------------------------------------------------------ places

	public static String dimensionId(ClientLevel level) {
		return level.dimension().location().toString();
	}

	/** World name plus its spawn point: tells apart servers that all call their world "overworld". */
	public static String placeKey(ClientLevel level) {
		BlockPos spawn = level.getLevelData().getSpawnPos(); // null until the server has sent it
		String where = spawn == null ? "?" : spawn.getX() + "," + spawn.getY() + "," + spawn.getZ();
		return dimensionId(level) + "@" + where;
	}

	public static boolean isWildWorld(ClientLevel level) {
		return ModConfig.get().isWildDimension(dimensionId(level));
	}

	/** Where automatic recording may start: the wild world, unless switched off for this place. */
	public static boolean isAutoPlace(ClientLevel level) {
		return isWildWorld(level) && !ModConfig.get().isExcluded(placeKey(level));
	}

	private boolean countsHere(ClientLevel level) {
		return recording && (manualSession || !ModConfig.get().autoMode || isAutoPlace(level));
	}

	// ------------------------------------------------------------------ recording control

	public void startRecording(boolean manual) {
		if (recording) {
			return;
		}
		if (!ModConfig.get().continueOnRestart) {
			clearTallies();
		}
		recording = true;
		manualSession = manual;
		lastMineTick = tick;
		session = new SessionLog();
		primaryChecks.clear();
		watches.clear();
		candidates.clear();
	}

	public void stopRecording(Minecraft mc, boolean summary) {
		if (!recording) {
			return;
		}
		recording = false;
		saveSession();
		primaryChecks.clear();
		watches.clear();
		candidates.clear();
		if (summary && ModConfig.get().chatSummary) {
			postSummary(mc);
		}
	}

	/** Adds this recording to the saved history (광질 통계), if anything was counted. */
	private void saveSession() {
		if (session != null && !session.isEmpty()) {
			History.add(session.toSession());
		}
		session = null;
	}

	/** The game is closing: keep what the running recording has so far. */
	public void onClientStopping() {
		if (recording) {
			saveSession();
		}
	}

	/**
	 * Automatic recording was just switched off for the place the player is in (e.g. 세레니티): an
	 * automatic recording that is running ends now instead of a minute later.
	 */
	public void onPlaceSwitchedOff(Minecraft mc) {
		if (recording && !manualSession && ModConfig.get().autoMode) {
			stopRecording(mc, true);
		}
	}

	/**
	 * Clears the counts but keeps recording if a recording is running. What was mined until now stays in the
	 * statistics (as its own recording); only the board starts over.
	 */
	public void clearTallies() {
		blocks.clear();
		items.clear();
		toasts.clear();
		magnetWaiting.clear();
		activeTicks = 0;
		if (recording) {
			saveSession();
			session = new SessionLog();
		}
		stats.packetPickups = 0;
		stats.latePacketPickups = 0;
		stats.fallbackPickups = 0;
		stats.mergeSkips = 0;
		stats.cleanupSkips = 0;
		stats.longReachSkips = 0;
		stats.notNearSkips = 0;
		stats.otherPlayerSkips = 0;
		stats.lavaSkips = 0;
		stats.neighborBlocks = 0;
		stats.restoredBlocks = 0;
		stats.rebrokenBlocks = 0;
		java.util.Arrays.fill(stats.mergeDistance, 0);
		dirty = true;
	}

	public void requestSettings() {
		openSettingsNextTick = true;
	}

	public void requestEditor() {
		openEditorNextTick = true;
	}

	public void requestStats() {
		openStatsNextTick = true;
	}

	static void notify(Minecraft mc, String message) {
		mc.gui.setOverlayMessage(Component.literal(message), false);
	}

	static String formatSeconds(int seconds) {
		return seconds % 60 == 0 ? (seconds / 60) + "분" : seconds + "초";
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
		if (openSettingsNextTick) {
			openSettingsNextTick = false;
			if (mc.screen == null) {
				mc.setScreen(new SettingsScreen(null));
			}
		}
		if (openEditorNextTick) {
			openEditorNextTick = false;
			if (mc.screen == null) {
				mc.setScreen(new EditHudScreen(null));
			}
		}
		if (openStatsNextTick) {
			openStatsNextTick = false;
			if (mc.screen == null) {
				mc.setScreen(new StatsScreen(null));
			}
		}

		ModConfig cfg = ModConfig.get();
		if (recording) {
			if (cfg.autoMode && tick - lastMineTick > cfg.endAfterSeconds * 20L) {
				stopRecording(mc, true);
				notify(mc, "광질 측정: " + formatSeconds(cfg.endAfterSeconds) + " 동안 안 캐서 기록을 끝냈어요");
			} else if (mc.level != null && !mc.isPaused()
					&& (!cfg.autoMode || tick - lastMineTick <= ACTIVE_GRACE_TICKS)) {
				activeTicks++;
				if (session != null) {
					session.ticks++;
				}
			}
		}

		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		if (level == null || player == null) {
			if (lastLevel != null) {
				resolveCandidates(true);
				clearWorldState();
				lastLevel = null;
			}
			return;
		}
		if (level != lastLevel) {
			resolveCandidates(true); // picked up right before a teleport: still count it
			clearWorldState();
			lastLevel = level;
		}

		checkPrimaries(level);
		checkWatches(level, player);
		trackItems(level, player);
		resolveCandidates(false);
		toasts.tick(tick);
		takenByPacket.values().removeIf(t -> tick - t > 100);
		measureMergeDistance();
		recentGrowth.removeIf(g -> tick - g.tick > GROWTH_KEEP_TICKS);
		magnetWaiting.removeIf(w -> {
			boolean expired = tick - w.tick() > MAGNET_WINDOW_TICKS;
			if (expired) {
				stats.magnetUnconfirmed++;
			}
			return expired;
		});
		recentVanished.removeIf(v -> tick - v.tick > GROWTH_KEEP_TICKS);
		cleanupTicks.removeIf(t -> tick - t > 20);
	}

	public void onDisconnect() {
		resolveCandidates(true);
		// leaving the server ends the recording, so it is saved to the statistics
		stopRecording(Minecraft.getInstance(), false);
		clearWorldState();
		lastLevel = null;
	}

	private void clearWorldState() {
		primaryChecks.clear();
		watches.clear();
		tracked.clear();
		recentGrowth.clear();
		recentVanished.clear();
		takenByPacket.clear();
		candidates.clear();
	}

	private void handleKeys(Minecraft mc) {
		while (Keys.toggleHud.consumeClick()) {
			ModConfig cfg = ModConfig.get();
			cfg.hudVisible = !cfg.hudVisible;
			ModConfig.save();
			notify(mc, cfg.hudVisible ? "광질 측정: 집계판을 켰어요" : "광질 측정: 집계판을 숨겼어요");
		}
		while (Keys.pause.consumeClick()) {
			if (recording) {
				stopRecording(mc, true);
				notify(mc, "광질 측정: 기록을 끝냈어요");
			} else {
				startRecording(true);
				ModConfig cfg = ModConfig.get();
				notify(mc, cfg.autoMode
						? "광질 측정: 기록을 시작했어요 (" + formatSeconds(cfg.endAfterSeconds) + " 동안 안 캐면 끝나요)"
						: "광질 측정: 기록을 시작했어요 (F7을 다시 누르면 끝나요)");
			}
		}
		while (Keys.reset.consumeClick()) {
			if (tick - resetArmedAt <= RESET_CONFIRM_TICKS) {
				clearTallies();
				resetArmedAt = Long.MIN_VALUE / 2;
				notify(mc, "광질 측정: 집계판을 초기화했어요 (지금까지 캔 건 광질 통계에 남아요)");
			} else {
				resetArmedAt = tick;
				notify(mc, "광질 측정: 3초 안에 한 번 더 누르면 집계판을 초기화해요");
			}
		}
		while (Keys.edit.consumeClick()) {
			if (mc.screen == null) {
				mc.setScreen(new SettingsScreen(null));
			}
		}
		while (Keys.stats.consumeClick()) {
			if (mc.screen == null) {
				mc.setScreen(new StatsScreen(null));
			}
		}
	}

	// ------------------------------------------------------------------ blocks

	/** Called right after the client breaks a block (Fabric's ClientPlayerBlockBreakEvents.AFTER). */
	public void onBlockBroken(ClientLevel level, LocalPlayer player, BlockPos pos, BlockState state) {
		if (state.isAir()) {
			return;
		}
		boolean pickaxe = isPickaxe(player.getMainHandItem(), state);
		if (!recording) {
			if (!ModConfig.get().autoMode || !pickaxe || !isMiningBlock(state) || !isAutoPlace(level)) {
				return;
			}
			startRecording(false);
		}
		if (!countsHere(level)) {
			return;
		}
		if (pickaxe) {
			lastMineTick = tick;
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
					watches.put(n, new NeighborWatch(n, ns, tick + WATCH_TICKS, false));
				}
			}
		}
	}

	private static boolean isPickaxe(ItemStack held, BlockState state) {
		if (held.isEmpty()) {
			return false;
		}
		return held.is(ItemTags.PICKAXES)
				|| (state.is(BlockTags.MINEABLE_WITH_PICKAXE) && held.isCorrectToolForDrops(state))
				// server tools made from other items still carry the word in their name
				|| held.getHoverName().getString().contains("곡괭이");
	}

	/** Natural stone and ores: the blocks that start an automatic recording. */
	private static boolean isMiningBlock(BlockState state) {
		return state.is(BlockTags.BASE_STONE_OVERWORLD)
				|| state.is(BlockTags.BASE_STONE_NETHER)
				|| state.is(BlockTags.COAL_ORES)
				|| state.is(BlockTags.IRON_ORES)
				|| state.is(BlockTags.COPPER_ORES)
				|| state.is(BlockTags.GOLD_ORES)
				|| state.is(BlockTags.REDSTONE_ORES)
				|| state.is(BlockTags.LAPIS_ORES)
				|| state.is(BlockTags.DIAMOND_ORES)
				|| state.is(BlockTags.EMERALD_ORES)
				|| state.is(Blocks.NETHER_QUARTZ_ORE)
				|| state.is(Blocks.ANCIENT_DEBRIS)
				|| state.is(Blocks.CALCITE)
				|| state.is(Blocks.DRIPSTONE_BLOCK)
				|| state.is(Blocks.SMOOTH_BASALT)
				|| state.is(Blocks.MAGMA_BLOCK)
				|| state.is(Blocks.RAW_IRON_BLOCK)
				|| state.is(Blocks.RAW_COPPER_BLOCK)
				|| state.is(Blocks.RAW_GOLD_BLOCK)
				|| state.is(Blocks.AMETHYST_BLOCK);
	}

	/**
	 * The server can refuse a break (protected area etc.) and put the block back: undo the count. Some servers
	 * put it back and then break it themselves a moment later, so the block is watched and counted again if
	 * it disappears without the player mining it again.
	 */
	private void checkPrimaries(ClientLevel level) {
		for (Iterator<PrimaryCheck> it = primaryChecks.iterator(); it.hasNext(); ) {
			PrimaryCheck c = it.next();
			if (level.getBlockState(c.pos) == c.state) {
				addBlock(c.state.getBlock(), -1);
				stats.restoredBlocks++;
				it.remove();
				watches.putIfAbsent(c.pos, new NeighborWatch(c.pos, c.state, tick + WATCH_TICKS, true));
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
						countWatched(w);
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
			if (!gone || !recording) {
				it.remove();
				continue;
			}
			if (w.state.getBlock() instanceof FallingBlock) {
				w.goneAt = tick;
				continue;
			}
			countWatched(w);
			it.remove();
		}
	}

	private void countWatched(NeighborWatch w) {
		addBlock(w.state.getBlock(), 1);
		if (w.restored) {
			stats.rebrokenBlocks++;
		} else {
			stats.neighborBlocks++;
		}
	}

	private void addBlock(Block block, int delta) {
		if (session != null) {
			session.blocks += delta;
		}
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

	private void observe(TrackedItem info, int count, long when) {
		Growth grew = info.observeCount(count, when);
		if (grew != null) {
			recentGrowth.add(grew);
		}
	}

	/**
	 * Packets are handled before the end of the tick, where {@link #trackItems} reads the counts as tick + 1;
	 * a count seen in a packet is stamped the same way, so both line up when matching merges.
	 */
	private long packetTick() {
		return tick + 1;
	}

	/** Called (on the main thread) when the server says an entity picked up an item. */
	public void onTakeItemPacket(int itemEntityId, int collectorId, int amount) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}
		Entity entity = mc.level.getEntity(itemEntityId);
		TrackedItem info = tracked.get(itemEntityId);
		ItemStack proto;
		boolean late = false;
		if (entity instanceof ItemEntity itemEntity) {
			ItemStack stack = itemEntity.getItem();
			if (info != null && !stack.isEmpty()) {
				observe(info, stack.getCount(), packetTick()); // before the game shrinks it
			}
			proto = !stack.isEmpty() ? stack.copyWithCount(1) : info != null ? info.proto : ItemStack.EMPTY;
		} else if (entity == null && info != null && info.removedAt >= 0 && tick - info.removedAt <= LATE_TAKE_TICKS) {
			proto = info.proto; // the server removed the item first and said who took it afterwards
			late = true;
		} else {
			return;
		}
		takenByPacket.put(itemEntityId, tick);
		if (collectorId != mc.player.getId() || !countsHere(mc.level) || proto.isEmpty()) {
			return;
		}
		stats.packetPickups++;
		if (late) {
			stats.latePacketPickups++;
		}
		collect(proto, amount);
	}

	/** Called when an entity leaves the client world (Fabric's ClientEntityEvents.ENTITY_UNLOAD). */
	public void onEntityUnload(Entity entity, ClientLevel level) {
		if (!(entity instanceof ItemEntity item)) {
			return;
		}
		TrackedItem info = tracked.get(item.getId());
		ItemStack stack = item.getItem();
		if (info != null) {
			info.removedAt = tick;
			if (!stack.isEmpty()) {
				observe(info, stack.getCount(), packetTick());
			}
		}
		if (item.getRemovalReason() != Entity.RemovalReason.DISCARDED) {
			return;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || stack.isEmpty()) {
			return; // emptied by a pickup message (counted there if it was ours)
		}
		Long takenAt = takenByPacket.get(item.getId());
		if (takenAt != null && tick - takenAt <= TAKEN_RECENT_TICKS) {
			return; // the end of a pickup the server told us about
		}
		double distSqr = item.distanceToSqr(player);
		// a floor cleanup takes items everywhere at once; another player's pickups (next to them) are not that
		if (!nearOtherPlayer(level, player, item)) {
			discardsThisTick++;
			if (distSqr > 64.0) {
				farDiscardsThisTick++;
			}
		}
		if (distSqr > ITEM_TRACK_RANGE * ITEM_TRACK_RANGE) {
			return;
		}
		if (!countsHere(level)) {
			return;
		}

		Candidate candidate = new Candidate(item.getId(), stack.copyWithCount(1), stack.getCount(), tick, item.position(), true);
		candidate.mine = closestToMe(item.position());
		recentVanished.add(candidate);
		AABB box = item.getBoundingBox();
		boolean nearNow = box.intersects(nearBox(player));
		boolean nearRecently = info != null && tick - info.lastNear <= NEAR_RECENT_TICKS;
		boolean close = box.intersects(closeBox(player));
		boolean fresh = item.getAge() <= FRESH_TICKS;
		boolean byMagnet = false;
		if (!nearNow && !nearRecently && !(fresh && close)) {
			boolean inMagnetRange = box.intersects(magnetBox(player))
					|| info != null && tick - info.lastInMagnetRange <= MAGNET_RECENT_TICKS;
			boolean lyingInRange = info != null && info.magnetRangeSince >= 0 && tick - info.magnetRangeSince > MAGNET_LYING_TICKS;
			if (!inMagnetRange || lyingInRange) {
				if (close) {
					stats.notNearSkips++;
				}
				candidates.add(candidate.shadow());
				return; // vanished somewhere else (merged with another item, or someone else took it)
			}
			byMagnet = true; // a magnet pet may have pulled it in
		}
		if (!byMagnet && info != null && lyingAround(info, box.intersects(pickupBox(player)))) {
			stats.longReachSkips++;
			candidates.add(candidate.shadow());
			return; // it lay next to the player without being picked up, then got cleaned up
		}
		if (item.isInLava() || item.isOnFire()) {
			stats.lavaSkips++;
			return;
		}
		if (otherPlayerTookIt(level, player, item) || byMagnet && otherPlayerCloser(level, player, item)) {
			stats.otherPlayerSkips++;
			candidates.add(candidate.shadow()); // it may have merged instead: let it claim that
			return;
		}
		candidate.byMagnet = byMagnet;
		candidate.fresh = fresh;
		candidates.add(candidate);
	}

	/** True if the spot is closer to this player than to every other player (always true when alone). */
	private static boolean closestToMe(Vec3 at) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return true;
		}
		double mine = mc.player.position().distanceToSqr(at);
		for (AbstractClientPlayer other : mc.level.players()) {
			if (other != mc.player && !other.isSpectator() && other.position().distanceToSqr(at) < mine) {
				return false;
			}
		}
		return true;
	}

	/** Another player is within magnet range of the item (it may have gone to them). */
	private static boolean nearOtherPlayer(ClientLevel level, LocalPlayer self, ItemEntity item) {
		double reach = MAGNET_REACH + 2.0;
		for (AbstractClientPlayer other : level.players()) {
			if (other != self && !other.isSpectator() && other.distanceToSqr(item) <= reach * reach) {
				return true;
			}
		}
		return false;
	}

	/** Another player is closer to the item and within magnet range of it. */
	private static boolean otherPlayerCloser(ClientLevel level, LocalPlayer self, ItemEntity item) {
		double mine = self.distanceToSqr(item);
		double reach = MAGNET_REACH + 1.0;
		for (AbstractClientPlayer other : level.players()) {
			if (other == self || other.isSpectator()) {
				continue;
			}
			double d = other.distanceToSqr(item);
			if (d < mine && d <= reach * reach) {
				return true;
			}
		}
		return false;
	}

	/** Where a magnet pet takes items from. */
	private static AABB magnetBox(Player player) {
		return player.getBoundingBox().inflate(MAGNET_REACH, MAGNET_REACH_Y, MAGNET_REACH);
	}

	/** Right next to the player. */
	private static AABB nearBox(Player player) {
		return player.getBoundingBox().inflate(1.5, 1.0, 1.5);
	}

	/** Where the server lets the player pick items up (1 block around the player), plus a hair. */
	private static AABB pickupBox(Player player) {
		return player.getBoundingBox().inflate(1.1, 0.6, 1.1);
	}

	/** A bit further out: freshly dropped items can be here on our screen while the server has them closer. */
	private static AABB closeBox(Player player) {
		return player.getBoundingBox().inflate(2.5, 1.5, 2.5);
	}

	/**
	 * True if the item had been lying by the player without being picked up: in pickup range for a long
	 * time, or right next to the player for a long time without just now coming into pickup range.
	 */
	private boolean lyingAround(TrackedItem info, boolean inPickupRangeNow) {
		if (info.pickupRangeSince >= 0 && tick - info.pickupRangeSince > PICKUP_RANGE_LYING_TICKS) {
			return true;
		}
		boolean justReached = info.pickupRangeSince >= 0
				? tick - info.pickupRangeSince <= JUST_REACHED_TICKS
				: inPickupRangeNow;
		return !justReached && info.nearSince >= 0 && tick - info.nearSince > NEAR_LYING_TICKS;
	}

	/** Another player is closer to the item and close enough to pick it up. */
	private static boolean otherPlayerTookIt(ClientLevel level, LocalPlayer self, ItemEntity item) {
		double mine = self.distanceToSqr(item);
		AABB box = item.getBoundingBox();
		for (AbstractClientPlayer other : level.players()) {
			if (other == self || other.isSpectator()) {
				continue;
			}
			if (other.distanceToSqr(item) < mine && pickupBox(other).intersects(box)) {
				return true;
			}
		}
		return false;
	}

	private void trackItems(ClientLevel level, LocalPlayer player) {
		AABB near = nearBox(player);
		AABB pickup = pickupBox(player);
		AABB magnet = magnetBox(player);
		for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(ITEM_TRACK_RANGE))) {
			TrackedItem t = tracked.computeIfAbsent(e.getId(), TrackedItem::new);
			ItemStack stack = e.getItem();
			t.pos = e.position(); // first, so a growth seen now is placed where the item is now
			if (!stack.isEmpty()) {
				t.proto = stack.copyWithCount(1);
				observe(t, stack.getCount(), tick);
			}
			t.lastSeen = tick;
			AABB box = e.getBoundingBox();
			if (box.intersects(near)) {
				t.lastNear = tick;
				if (t.nearSince < 0) {
					t.nearSince = tick;
				}
			} else {
				t.nearSince = -1;
			}
			if (box.intersects(pickup)) {
				if (t.pickupRangeSince < 0) {
					t.pickupRangeSince = tick;
				}
			} else {
				t.pickupRangeSince = -1;
			}
			if (box.intersects(magnet)) {
				t.lastInMagnetRange = tick;
				if (t.magnetRangeSince < 0) {
					t.magnetRangeSince = tick;
				}
			} else {
				t.magnetRangeSince = -1;
			}
		}
		tracked.values().removeIf(t -> tick - t.lastSeen > 60);
	}

	/**
	 * Judges the vanished items once a merge would have shown up. Items that were not ours claim their merges
	 * first, then items that vanished within magnet range (away from the player, so more often merges); one
	 * that vanished next to the player with a merge beside it waits a little longer for them, so a pickup is not
	 * taken for a merge that happened beside it. {@code force}: judge all of them now.
	 */
	private void resolveCandidates(boolean force) {
		for (Iterator<Candidate> it = candidates.iterator(); it.hasNext(); ) {
			Candidate c = it.next();
			if (!c.counts && (force || tick - c.tick >= RESOLVE_DELAY_TICKS)) {
				it.remove();
				explainByMerges(c); // only claims the increase it caused
			}
		}
		for (Iterator<Candidate> it = candidates.iterator(); it.hasNext(); ) {
			Candidate c = it.next();
			if (c.byMagnet && (force || tick - c.tick >= RESOLVE_DELAY_TICKS)) {
				it.remove();
				judge(c);
			}
		}
		for (Iterator<Candidate> it = candidates.iterator(); it.hasNext(); ) {
			Candidate c = it.next();
			long age = tick - c.tick;
			if (!force && (age < RESOLVE_DELAY_TICKS || age < SETTLE_TICKS && !mergeOptions(c).isEmpty())) {
				continue;
			}
			it.remove();
			judge(c);
		}
	}

	/** Counts what is left of a vanished item of ours once merges are taken out. */
	private void judge(Candidate c) {
		Long takenAt = takenByPacket.get(c.entityId);
		if (takenAt != null && takenAt >= c.tick - 1) {
			return; // a pickup message came after all and was counted there
		}
		if (cleanupTicks.contains(c.tick)) {
			stats.cleanupSkips++;
			return;
		}
		int merged = explainByMerges(c);
		if (merged >= c.count) {
			stats.mergeSkips++;
			return; // merged into a neighbouring item, not picked up
		}
		if (c.byMagnet) {
			magnetPickup(c, c.count - merged);
			return;
		}
		stats.fallbackPickups++;
		collect(c.proto, c.count - merged);
	}

	/**
	 * An item pulled in from a distance, merges taken out. Next to merges it counts (the merges around it only
	 * add up that way), and so does a fresh one (nothing else takes those). An old item on its own counts only
	 * while a magnet pet is known to be at work: without one it was cleaned up or despawned.
	 */
	private void magnetPickup(Candidate c, int amount) {
		if (c.fresh || !mergeOptions(c).isEmpty()) {
			magnetSeen(c.tick);
			stats.magnetPickups++;
			collect(c.proto, amount);
		} else if (c.tick <= magnetUntil) {
			stats.magnetPickups++;
			collect(c.proto, amount);
		} else {
			magnetWaiting.add(new Waiting(c.proto, amount, c.tick));
		}
	}

	/** An item came in from a distance: after a few of those, a magnet pet is at work. */
	private void magnetSeen(long when) {
		if (magnetTimes.isEmpty() || magnetTimes.peekLast() != when) {
			magnetTimes.addLast(when);
		}
		while (!magnetTimes.isEmpty() && when - magnetTimes.peekFirst() > MAGNET_WINDOW_TICKS) {
			magnetTimes.pollFirst();
		}
		if (magnetTimes.size() >= MAGNET_CONFIRM) {
			magnetUntil = Math.max(magnetUntil, when + MAGNET_KEEP_TICKS);
			for (Waiting w : magnetWaiting) {
				stats.magnetPickups++;
				collect(w.proto(), w.amount());
			}
			magnetWaiting.clear();
		}
	}

	/** For /광질 진단: how far from a growing item the closest same item that vanished just before it was. */
	private void measureMergeDistance() {
		for (Growth g : recentGrowth) {
			if (tick - g.tick != MERGE_WINDOW_AFTER) {
				continue; // once, after every removal that could belong to it has been seen
			}
			double best = Double.MAX_VALUE;
			for (Candidate v : recentVanished) {
				if (v.entityId != g.item.id && v.tick >= g.tick - MERGE_WINDOW_AFTER && v.tick <= g.tick + MERGE_WINDOW_BEFORE
						&& ItemStack.isSameItemSameComponents(v.proto, g.item.proto)) {
					best = Math.min(best, Math.max(Math.abs(v.pos.x - g.pos.x), Math.abs(v.pos.z - g.pos.z)));
				}
			}
			if (best < Double.MAX_VALUE) {
				stats.mergeDistance[Math.min(3, (int) best)]++;
			}
		}
	}

	/**
	 * Increases of same items right next to a vanished item, around the time it vanished: where it could have
	 * merged into. The earliest comes first (then the closest item): taking the earliest one that is still free
	 * leaves the later ones to items that vanished later, so no increase is left over while an item that merged
	 * goes unexplained.
	 */
	private List<Growth> mergeOptions(Candidate c) {
		long from = c.tick - MERGE_WINDOW_BEFORE;
		long to = c.tick + MERGE_WINDOW_AFTER;
		List<Growth> found = new ArrayList<>();
		for (Growth g : recentGrowth) {
			TrackedItem other = g.item;
			if (g.tick < from || g.tick > to || other.id == c.entityId) {
				continue;
			}
			if (g.mine != c.mine) {
				continue; // merging items are right next to each other: on the same player's side
			}
			double reach = c.counts ? MERGE_REACH : FAR_MERGE_REACH;
			if (Math.abs(g.pos.x - c.pos.x) > reach || Math.abs(g.pos.z - c.pos.z) > reach
					|| Math.abs(g.pos.y - c.pos.y) > MERGE_REACH_Y || !ItemStack.isSameItemSameComponents(other.proto, c.proto)) {
				continue;
			}
			found.add(g);
		}
		found.sort(Comparator.comparingLong((Growth g) -> g.tick).thenComparingDouble(g -> g.pos.distanceToSqr(c.pos)));
		return found;
	}

	/**
	 * How much of a vanished item went into same items right next to it. Merging moves items without changing
	 * the total, so an increase can explain only as much as it added; what is left unexplained was picked up.
	 * When the increases nearby are already used by other vanished items, those are moved to other increases
	 * they could have gone into as well, so that as many merges as possible are explained. (The items that move
	 * keep the amount they had, so nothing that was already counted changes.)
	 */
	private int explainByMerges(Candidate c) {
		List<Growth> options = mergeOptions(c);
		if (options.isEmpty()) {
			return 0;
		}
		int need = c.count;
		while (need > 0) {
			// breadth-first search: c -> an increase -> an item using it -> another increase it could use -> ...
			Map<Growth, Candidate> reachedFrom = new HashMap<>();
			Map<Candidate, Growth> leaves = new HashMap<>();
			ArrayDeque<Candidate> queue = new ArrayDeque<>();
			queue.add(c);
			leaves.put(c, null);
			Growth end = null;
			int steps = 0;
			search:
			while (!queue.isEmpty() && steps++ < MATCH_SEARCH_LIMIT) {
				Candidate x = queue.poll();
				for (Growth g : mergeOptions(x)) {
					if (reachedFrom.containsKey(g)) {
						continue;
					}
					reachedFrom.put(g, x);
					if (g.free() > 0) {
						end = g;
						break search;
					}
					for (Candidate y : g.users.keySet()) {
						if (!leaves.containsKey(y)) {
							leaves.put(y, g);
							queue.add(y);
						}
					}
				}
			}
			if (end == null) {
				break;
			}
			// how much can go along the path
			int amount = Math.min(need, end.free());
			for (Growth g = end; reachedFrom.get(g) != c; ) {
				Candidate x = reachedFrom.get(g);
				Growth off = leaves.get(x);
				amount = Math.min(amount, off.users.get(x));
				g = off;
			}
			// move it
			Growth g = end;
			while (true) {
				Candidate x = reachedFrom.get(g);
				g.use(x, amount);
				if (x == c) {
					break;
				}
				Growth off = leaves.get(x);
				off.release(x, amount);
				g = off;
			}
			need -= amount;
		}
		return c.count - need;
	}

	private void collect(ItemStack proto, int amount) {
		if (amount <= 0 || !recording) {
			return;
		}
		String key = keyOf(proto);
		ItemTally t = items.get(key);
		if (t == null) {
			t = new ItemTally(key, proto, isOther(proto));
			items.put(key, t);
		}
		t.count += amount;
		dirty = true;
		if (session != null) {
			if (t.other) {
				session.other += amount;
			} else {
				History.Item it = session.items.get(key);
				if (it == null) {
					it = new History.Item();
					it.id = BuiltInRegistries.ITEM.getKey(proto.getItem()).toString();
					it.name = t.name;
					// a server's own item: keep how it looks, for its icon in the statistics
					it.look = History.Look.of(proto);
					History.rememberLook(key, it.look);
					session.items.put(key, it);
				}
				it.count += amount;
			}
		}
		if (!t.other || ModConfig.get().toastOthers) {
			toasts.push(key, t.icon, t.name, amount, t.count, tick);
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

	// ------------------------------------------------------------------ chat summary

	private void postSummary(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || isEmpty()) {
			return;
		}
		player.displayClientMessage(Component.literal("[광질 측정] ").withStyle(ChatFormatting.AQUA)
				.append(Component.literal("광질 기록 끝 · " + HudRenderer.formatTime(activeTicks)).withStyle(ChatFormatting.WHITE)), false);

		List<BlockTally> bl = sortedBlocks();
		if (!bl.isEmpty()) {
			long total = 0;
			for (BlockTally b : bl) {
				total += b.count;
			}
			StringBuilder line = new StringBuilder();
			int shown = Math.min(SUMMARY_BLOCKS, bl.size());
			for (int i = 0; i < shown; i++) {
				if (i > 0) {
					line.append(", ");
				}
				line.append(bl.get(i).name).append(' ').append(HudRenderer.fmt(bl.get(i).count));
			}
			if (bl.size() > shown) {
				long rest = 0;
				for (int i = shown; i < bl.size(); i++) {
					rest += bl.get(i).count;
				}
				line.append(", 외 ").append(bl.size() - shown).append("종 ").append(HudRenderer.fmt(rest));
			}
			player.displayClientMessage(Component.literal(" 캔 블록 " + HudRenderer.fmt(total) + "개: ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(line.toString()).withStyle(ChatFormatting.WHITE)), false);
		}

		List<ItemTally> minerals = new ArrayList<>();
		long othersCount = 0;
		int othersKinds = 0;
		for (ItemTally t : sortedItems()) {
			if (t.other) {
				othersCount += t.count;
				othersKinds++;
			} else {
				minerals.add(t);
			}
		}
		if (!minerals.isEmpty() || othersKinds > 0) {
			StringBuilder line = new StringBuilder();
			int shown = Math.min(SUMMARY_ITEMS, minerals.size());
			for (int i = 0; i < shown; i++) {
				if (i > 0) {
					line.append(", ");
				}
				line.append(minerals.get(i).name).append(' ').append(HudRenderer.fmt(minerals.get(i).count));
			}
			if (minerals.size() > shown) {
				long rest = 0;
				for (int i = shown; i < minerals.size(); i++) {
					rest += minerals.get(i).count;
				}
				line.append(", 외 ").append(minerals.size() - shown).append("종 ").append(HudRenderer.fmt(rest));
			}
			if (othersKinds > 0) {
				if (line.length() > 0) {
					line.append(" · ");
				}
				line.append("기타 ").append(othersKinds).append("종 ").append(HudRenderer.fmt(othersCount));
			}
			player.displayClientMessage(Component.literal(" 먹은 광물: ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(line.toString()).withStyle(ChatFormatting.WHITE)), false);
		}
	}

	// ------------------------------------------------------------------ sorting

	private void refreshSorted() {
		if (!dirty) {
			return;
		}
		List<BlockTally> b = new ArrayList<>(blocks.values());
		b.sort(Comparator.comparingLong((BlockTally t) -> t.count).reversed().thenComparing(t -> t.name));
		List<ItemTally> i = new ArrayList<>(items.values());
		i.sort(Comparator.comparingLong((ItemTally t) -> t.count).reversed().thenComparing(t -> t.name));
		sortedBlocks = b;
		sortedItems = i;
		dirty = false;
	}
}
