package com.miningtracker;

import com.miningtracker.mixin.BossHealthOverlayAccessor;
import com.miningtracker.mixin.GuiAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

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
 * <p>Items: counted from what the server says for sure, never guessed from items on the ground. The item
 * storage's notice above the hotbar ("[아이템 저장소] 다이아몬드 1개 수납! (270/30,000)") counts what it took, and
 * what comes straight into the inventory counts too (see {@link InventoryWatch}).
 *
 * <p>Where: 띵타이쿤 shows the channel ("야생 3", "마을") in a boss bar at the top; recordings start and count
 * only in 야생 there (see {@link Channel}). Elsewhere, the wild world by its name.
 */
public final class Tracker {
	public static final Tracker INSTANCE = new Tracker();

	private static final int WATCH_TICKS = 30;
	private static final int PRIMARY_CHECK_TICKS = 40;
	private static final int RESET_CONFIRM_TICKS = 60;

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
		public long neighborBlocks;
		public long restoredBlocks;
		/** Put back by the server, then broken by the server right after: counted again. */
		public long rebrokenBlocks;
		/** "[아이템 저장소] ... N개 수납!" notices counted, and how many items. */
		public long storageNotices;
		public long storageItems;
		/** The same notice again (the storage's count had not changed): not counted. */
		public long storageRepeats;
		/** How much more the storage's count went up than the notices said (missed notices, or put in by hand). */
		public long storageGap;
		/** Notices for a name no item was found for: counted as paper with that name. */
		public long storageUnknown;
		/** Notices while not recording here: not counted. */
		public long storageNotRecording;
		/** "N개 인출" notices. */
		public long withdrawals;
		/** Items filled in for missed notices (the storage's count went up more, nothing else explains it). */
		public long storageGapFilled;
		/** Notices read off the screen because the hook did not see them (counted ones). */
		public long storageFromScreen;
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
	private long resetArmedAt = Long.MIN_VALUE / 2;
	private boolean openSettingsNextTick;
	private boolean openEditorNextTick;
	private boolean openStatsNextTick;
	/** Items the server puts straight into the inventory (never on the ground). */
	private final InventoryWatch inventory = new InventoryWatch(new InventoryWatch.Counter() {
		@Override
		public void count(ItemStack proto, int amount) {
			Tracker.this.count(proto, amount);
			inventorySinceNotice.merge(proto.getHoverName().getString(), (long) amount, Long::sum);
		}

		@Override
		public void note(String text) {
			Tracker.this.note(text);
		}
	});
	/** The storage's count of an item in its last notice, and when it came. */
	private record Balance(long count, long tick) {
	}

	/** Each item's last notice (name|max): a notice with the same count is a repeat. */
	private final Map<String, Balance> storageBalances = new HashMap<>();
	/** Counted from the inventory since the item's last storage notice (by name): part of a jump is that. */
	private final Map<String, Long> inventorySinceNotice = new HashMap<>();
	/** How far apart two notices of an item may be for a jump between them to be filled in. */
	private static final int GAP_FILL_TICKS = 2400;
	/** When the running recording started, and the last time it was away (not 야생, between worlds). */
	private long recordingSince = Long.MAX_VALUE;
	private long lastAwayTick = Long.MIN_VALUE / 2;
	/** The last time a screen was open (the storage, a chest, the chat), and the last "N개 인출". */
	private long lastScreenTick = Long.MIN_VALUE / 2;
	private long lastWithdrawTick = Long.MIN_VALUE / 2;
	/** The last line the hook saw, and the last line read off the screen (the same one stays up ~3 s). */
	private Component lastHooked;
	private Component lastShown;
	/** The last notice that did not say the storage's count, and when (a repeat of it within 1.5 s is not counted). */
	private String lastNotice = "";
	private long lastNoticeAt;
	/** Items with each name picked up lately (as they left the ground): a storage notice only gives the name. */
	private final Map<String, ItemStack> seenByName = new HashMap<>();
	/** The game's items by their name in the game's language, made when first needed. */
	private Map<String, Item> itemsByName;
	/** What was decided lately and why, for /광질 진단 최근 (oldest first). */
	private final Deque<String> recent = new ArrayDeque<>();
	private static final int RECENT_KEEP = 60;
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
	/** The channel the boss bar shows ("야생 3"), "" when none; and when it was last read. */
	private String channel = "";
	private long channelSeenAt = Long.MIN_VALUE / 2;
	/** A channel read this long ago still counts (the boss bar may be gone for a moment between channels). */
	private static final int CHANNEL_KEEP_TICKS = 600;

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

	/**
	 * The wild (야생): a 야생 channel when the boss bar shows a channel, otherwise (other servers) a world whose name
	 * is one of the wild worlds.
	 */
	public static boolean isWildWorld(ClientLevel level) {
		return INSTANCE.isWild(level);
	}

	/** {@link #isWildWorld}, by this tracker's channel. */
	public boolean isWild(ClientLevel level) {
		String here = channel();
		return !here.isEmpty() ? Channel.isWild(here) : ModConfig.get().isWildDimension(dimensionId(level));
	}

	/** The channel the boss bar shows ("야생 3"), or "" when none was seen lately. */
	public String channel() {
		return tick - channelSeenAt <= CHANNEL_KEEP_TICKS ? channel : "";
	}

	/** Where automatic recording may start: only the wild (a 야생 channel, or without one the wild world). */
	public static boolean isAutoPlace(ClientLevel level) {
		return INSTANCE.autoHere(level);
	}

	private boolean autoHere(ClientLevel level) {
		return isWild(level);
	}

	private boolean countsHere(ClientLevel level) {
		return recording && (manualSession || !ModConfig.get().autoMode || autoHere(level));
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
		recordingSince = tick;
		manualSession = manual;
		lastMineTick = tick;
		session = new SessionLog();
		primaryChecks.clear();
		watches.clear();
	}

	public void stopRecording(Minecraft mc, boolean summary) {
		if (!recording) {
			return;
		}
		// what came into the inventory and is still waiting to be counted belongs to this recording
		inventory.finish(tick);
		recording = false;
		saveSession();
		primaryChecks.clear();
		watches.clear();
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
			inventory.finish(tick);
			saveSession();
		}
	}

	/**
	 * Clears the counts but keeps recording if a recording is running. What was mined until now stays in the
	 * statistics (as its own recording); only the board starts over.
	 */
	public void clearTallies() {
		if (recording) {
			inventory.flush(tick); // what came into the inventory a moment ago belongs to what is cleared
		}
		blocks.clear();
		items.clear();
		toasts.clear();
		activeTicks = 0;
		if (recording) {
			saveSession();
			session = new SessionLog();
		}
		stats.neighborBlocks = 0;
		stats.restoredBlocks = 0;
		stats.rebrokenBlocks = 0;
		inventory.clearStats();
		stats.storageNotices = 0;
		stats.storageItems = 0;
		stats.storageRepeats = 0;
		stats.storageGap = 0;
		stats.storageUnknown = 0;
		stats.storageNotRecording = 0;
		stats.withdrawals = 0;
		stats.storageGapFilled = 0;
		stats.storageFromScreen = 0;
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
				notify(mc, "픽트래커: " + formatSeconds(cfg.endAfterSeconds) + " 동안 안 캐서 기록을 끝냈어요");
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
				clearWorldState();
				lastLevel = null;
			}
			inventory.forget();
			lastAwayTick = tick;
			return;
		}
		if (level != lastLevel) {
			clearWorldState();
			lastLevel = level;
		}
		if (tick % 20 == 0) {
			readChannel(mc);
		}

		if (mc.screen != null) {
			lastScreenTick = tick;
		}
		if (recording && !countsHere(level)) {
			lastAwayTick = tick;
		}
		readShownLine(mc);

		checkPrimaries(level);
		checkWatches(level, player);
		inventory.tick(player.getInventory(), tick, countsHere(level) && cfg.countInventory, mc.screen != null);
		toasts.tick(tick);
	}

	/**
	 * Reads the channel from the boss bar (once a second). A new channel (야생 1 → 야생 3) means the player moved:
	 * the inventory the server fills in then is not counted.
	 */
	private void readChannel(Minecraft mc) {
		String now = Channel.in(((BossHealthOverlayAccessor) mc.gui.getBossOverlay()).miningTracker$events().values());
		if (now.isEmpty()) {
			return;
		}
		String before = channel();
		channel = now;
		channelSeenAt = tick;
		if (!before.isEmpty() && !before.equals(now)) {
			note("채널 이동: " + before + " → " + now);
			inventory.newPlace(tick, "채널 이동 직후 (" + before + " → " + now + ")");
		}
	}

	public void onDisconnect() {
		// leaving the server ends the recording, so it is saved to the statistics
		stopRecording(Minecraft.getInstance(), false);
		inventory.reset();
		clearWorldState();
		lastLevel = null;
		channel = "";
	}

	private void clearWorldState() {
		primaryChecks.clear();
		watches.clear();
	}

	private void handleKeys(Minecraft mc) {
		while (Keys.toggleHud.consumeClick()) {
			ModConfig cfg = ModConfig.get();
			cfg.hudVisible = !cfg.hudVisible;
			ModConfig.save();
			notify(mc, cfg.hudVisible ? "픽트래커: 집계판을 켰어요" : "픽트래커: 집계판을 숨겼어요");
		}
		while (Keys.pause.consumeClick()) {
			if (recording) {
				stopRecording(mc, true);
				notify(mc, "픽트래커: 기록을 끝냈어요");
			} else {
				startRecording(true);
				ModConfig cfg = ModConfig.get();
				notify(mc, cfg.autoMode
						? "픽트래커: 기록을 시작했어요 (" + formatSeconds(cfg.endAfterSeconds) + " 동안 안 캐면 끝나요)"
						: "픽트래커: 기록을 시작했어요 (F7을 다시 누르면 끝나요)");
			}
		}
		while (Keys.reset.consumeClick()) {
			if (tick - resetArmedAt <= RESET_CONFIRM_TICKS) {
				clearTallies();
				resetArmedAt = Long.MIN_VALUE / 2;
				notify(mc, "픽트래커: 집계판을 초기화했어요 (지금까지 캔 건 광질 통계에 남아요)");
			} else {
				resetArmedAt = tick;
				notify(mc, "픽트래커: 3초 안에 한 번 더 누르면 집계판을 초기화해요");
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
		while (Keys.blocks != null && Keys.blocks.consumeClick()) {
			ModConfig cfg = ModConfig.get();
			cfg.mineralBlocks = !cfg.mineralBlocks;
			ModConfig.save();
			notify(mc, cfg.mineralBlocks ? "픽트래커: 먹은 광물을 블록으로 보여줘요 (9개 = 블록 1개)" : "픽트래커: 먹은 광물을 개수로 보여줘요");
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
			if (!ModConfig.get().autoMode || !pickaxe || !isMiningBlock(state) || !autoHere(level)) {
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

	/**
	 * Called when an entity leaves the client world (Fabric's ClientEntityEvents.ENTITY_UNLOAD). An item leaving
	 * next to the player is usually being picked up: its name is remembered, so a storage notice naming it is
	 * counted under that very item (server items keep their look). Nothing is counted here.
	 */
	public void onEntityUnload(Entity entity, ClientLevel level) {
		if (!(entity instanceof ItemEntity item) || !recording) {
			return;
		}
		ItemStack stack = item.getItem();
		LocalPlayer player = Minecraft.getInstance().player;
		if (!stack.isEmpty() && player != null && item.distanceToSqr(player) <= NAME_RANGE * NAME_RANGE) {
			seen(stack.copyWithCount(1));
		}
	}

	/** How near the player an item that leaves has its name remembered (a magnet pet reaches 5 blocks). */
	private static final double NAME_RANGE = 8.0;

	/**
	 * A pickup from the ground: counted (unless it showed up in the inventory first and was counted then), and
	 * remembered so it is not counted again when it shows up there.
	 */
	/** Counts what a notice says, except what came into the inventory first and was counted then; that part. */
	private int collect(ItemStack proto, int amount, String how) {
		String key = keyOf(proto);
		int already = inventory.alreadyCounted(key, amount);
		String name = proto.getHoverName().getString();
		if (already > 0) {
			note(name + " " + already + "개 · 안 셈: 인벤토리에 먼저 들어와서 이미 셈 (" + how + ")");
		}
		int left = amount - already;
		if (count(proto, left)) {
			inventory.pickedUp(key, left, tick);
			note(name + " +" + left + " · " + how);
		}
		return already;
	}

	// ------------------------------------------------------------------ the item storage's notices

	/**
	 * Every line shown above the hotbar comes here (see GuiMixin). "[아이템 저장소] 다이아몬드 1개 수납! (270/30,000)"
	 * counts as picked up; "... N개 인출" means what comes into the inventory next was in the storage.
	 */
	public void onOverlayMessage(Component message) {
		lastHooked = message;
		read(message, false);
	}

	/**
	 * Backup for the hook: the line above the hotbar right now. A line the hook did not see (another mod got in
	 * the way) is read here instead; one it saw, or one already read, is not read again.
	 */
	private void readShownLine(Minecraft mc) {
		Component shown = ((GuiAccessor) mc.gui).miningTracker$overlayMessage();
		if (shown == null || shown == lastHooked || shown == lastShown) {
			return;
		}
		lastShown = shown;
		read(shown, true);
	}

	private void read(Component message, boolean fromScreen) {
		try {
			if (message == null) {
				return;
			}
			String text = StorageNotice.clean(message.getString());
			if (!StorageNotice.isStorage(text)) {
				return;
			}
			int out = StorageNotice.takenOut(text);
			if (out > 0) {
				stats.withdrawals++;
				lastWithdrawTick = tick;
				inventory.withdrawn(out, tick);
				note("저장소에서 " + out + "개 인출 · 곧 인벤토리에 들어오는 건 안 셈");
				return;
			}
			StorageNotice.Stored stored = StorageNotice.stored(text);
			if (stored != null) {
				onStored(stored, text, fromScreen);
			}
		} catch (RuntimeException e) {
			MiningTrackerClient.LOGGER.warn("could not read a storage notice", e);
		}
	}

	private void onStored(StorageNotice.Stored s, String text, boolean fromScreen) {
		String what = s.name() + " " + s.amount() + "개";
		String read = fromScreen ? " · 화면 글자로 읽음" : "";
		ClientLevel level = Minecraft.getInstance().level;
		boolean counting = level != null && countsHere(level);
		long gap = 0;
		long gapFrom = 0;
		String gapWhy = null;
		long viaInventory = 0;
		if (s.balance() >= 0) {
			Balance before = storageBalances.put(s.name() + "|" + s.max(), new Balance(s.balance(), tick));
			if (before != null && before.count() == s.balance()) {
				stats.storageRepeats++;
				note(what + " · 안 셈: 같은 알림이 또 옴 (저장소 개수 " + s.balance() + " 그대로)" + read);
				return;
			}
			viaInventory = inventorySinceNotice.getOrDefault(s.name(), 0L);
			inventorySinceNotice.remove(s.name());
			if (before != null && s.balance() - before.count() > s.amount()) {
				gap = s.balance() - before.count() - s.amount();
				gapFrom = before.count();
				gapWhy = gapRefusal(before.tick());
			}
		} else {
			long now = System.currentTimeMillis();
			if (text.equals(lastNotice) && now - lastNoticeAt < 1500) {
				stats.storageRepeats++;
				note(what + " · 안 셈: 같은 알림이 1.5초 안에 또 옴");
				return;
			}
			lastNotice = text;
			lastNoticeAt = now;
		}
		if (!counting) {
			stats.storageNotRecording++;
			String where = channel().isEmpty() && level != null ? dimensionId(level) : channel();
			note(what + " · 안 셈: " + (!recording ? "기록 중이 아님" : "야생이 아닌 곳 (" + where + ")"));
			return;
		}
		stats.storageNotices++;
		stats.storageItems += s.amount();
		if (fromScreen) {
			stats.storageFromScreen++;
		}
		ItemStack proto = protoFor(s.name());
		int already = collect(proto, s.amount(), "저장소 알림" + (s.balance() >= 0 ? " (저장소 " + s.balance() + "개)" : "") + read);
		if (gap > 0) {
			fillGap(proto, s, gap, gapFrom, gapWhy, Math.max(0, viaInventory - already));
		}
	}

	/**
	 * The storage's count went up by {@code gap} more than the notice said. Filled in unless {@code why} says
	 * otherwise; what the inventory counted since (it may have gone into the storage quietly) is taken out first.
	 */
	private void fillGap(ItemStack proto, StorageNotice.Stored s, long gap, long from, String why, long fromInventory) {
		String head = s.name() + " · 저장소 " + from + " → " + s.balance() + "개인데 알림은 " + s.amount() + "개";
		long fill = gap - fromInventory;
		if (why == null && fill <= 0) {
			why = "인벤토리로 들어와서 이미 센 것";
		}
		if (why != null) {
			stats.storageGap += gap;
			note(head + ": 차이 " + gap + "개는 안 셈 (" + why + ")");
			return;
		}
		int amount = (int) Math.min(fill, Integer.MAX_VALUE);
		stats.storageGapFilled += amount;
		stats.storageGap += gap - amount;
		note(head + ": 놓친 알림 " + amount + "개를 채움" + (fromInventory > 0 ? " (인벤토리로 이미 센 " + fromInventory + "개 빼고)" : ""));
		if (count(proto, amount)) {
			inventory.pickedUp(keyOf(proto), amount, tick);
		}
	}

	/**
	 * Why a jump in the storage's count since the item's last notice (at {@code since}) is not filled in, or null
	 * to fill it: only while recording here the whole time, with no screen opened (putting things in by hand needs
	 * one) and nothing taken out, and not long after.
	 */
	private String gapRefusal(long since) {
		if (!recording || recordingSince >= since) {
			return "그 사이 기록이 새로 시작됨";
		}
		if (lastAwayTick >= since) {
			return "그 사이 야생을 벗어났거나 채널을 옮김";
		}
		if (lastScreenTick >= since) {
			return "그 사이 창을 열었음, 직접 넣었을 수 있음";
		}
		if (lastWithdrawTick >= since) {
			return "그 사이 인출이 있었음";
		}
		if (tick - since > GAP_FILL_TICKS) {
			return "알림 사이가 2분 넘게 벌어짐";
		}
		return null;
	}

	/**
	 * An item for a name in a storage notice: one seen with that name lately (server items keep their look), one
	 * the game calls that, or else paper with that name.
	 */
	private ItemStack protoFor(String name) {
		ItemStack seen = seenByName.get(name);
		if (seen == null) {
			seen = inventory.protoNamed(name);
		}
		if (seen != null) {
			return seen;
		}
		Item item = itemNamed(name);
		if (item != null) {
			return new ItemStack(item);
		}
		stats.storageUnknown++;
		note(name + " · 이 이름의 아이템을 못 찾아서 종이 아이콘으로 셈");
		ItemStack paper = new ItemStack(Items.PAPER);
		paper.set(DataComponents.CUSTOM_NAME, Component.literal(name));
		return paper;
	}

	private Item itemNamed(String name) {
		if (itemsByName == null) {
			itemsByName = new HashMap<>();
			for (Item item : BuiltInRegistries.ITEM) {
				itemsByName.putIfAbsent(new ItemStack(item).getHoverName().getString(), item);
			}
		}
		return itemsByName.get(name);
	}

	/** Remembers an item lying near the player by its name, for the storage notices. */
	private void seen(ItemStack proto) {
		if (seenByName.size() > 500) {
			seenByName.clear();
		}
		seenByName.put(proto.getHoverName().getString(), proto);
	}

	/** A line for /광질 진단 최근. */
	void note(String text) {
		recent.addLast(LocalTime.now().format(CLOCK) + " " + text);
		while (recent.size() > RECENT_KEEP) {
			recent.pollFirst();
		}
	}

	/** What was decided lately and why, oldest first. */
	List<String> recentNotes() {
		return new ArrayList<>(recent);
	}

	/** Adds picked-up items to the board, the recording and the pickup toasts. False when not recording. */
	private boolean count(ItemStack proto, int amount) {
		if (amount <= 0 || !recording) {
			return false;
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
		return true;
	}

	/** The inventory watch, for /광질 진단. */
	InventoryWatch inventoryWatch() {
		return inventory;
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
		player.displayClientMessage(Component.literal("[픽트래커] ").withStyle(ChatFormatting.AQUA)
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
