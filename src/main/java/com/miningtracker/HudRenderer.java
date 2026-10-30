package com.miningtracker;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws the tally board (집계판) in the chosen layout and the pickup toasts (획득 알림), in the chosen theme.
 *
 * <p>Layout numbers are in "panel pixels": everything is drawn inside a pose scaled by the configured
 * scale (default 2/3, which matches the size of the server's own HUD panels at GUI scale 3). Item icons
 * are the game's real item icons. The board is a dark card with the elapsed time in big numbers; each
 * mineral gets a bar in its own colour, as long as its share of the most picked-up mineral.
 */
public final class HudRenderer {
	static final int MIN_WIDTH = 172;
	static final int NAME_MAX = 124;
	/** Mineral rows (with a bar behind them) and block rows. */
	static final int ROW = 15;
	static final int BLOCK_ROW = 12;
	/** 슬림: where the minerals start, under the header and the big time. */
	static final int SLIM_TOP = 53;
	/** 아이콘: where the slots start. */
	static final int SLOTS_TOP = 51;
	static final int TOAST_HEIGHT = 14;
	static final int TOAST_GAP = 3;
	static final int SLOT = 20;
	static final int SLOT_GAP = 2;
	static final int SLOT_ROWS = 2;
	static final int BAR_HEIGHT = 14;
	static final int BAR_MAX_ITEMS = 8;
	/** The elapsed time is drawn twice as big; the numbers in the statistics boxes one and a half times. */
	static final float BIG = 2f;
	static final float MID = 1.5f;

	private static ItemStack headerIcon;

	private HudRenderer() {
	}

	/** One line of the board: icon, name and a count. {@code dots}: "외 N종" (no icon). */
	record Row(ItemStack icon, String name, long count, boolean dim, boolean dots) {
	}

	static final class Panel {
		String time = "00:00";
		boolean resting;
		long seconds;
		long totalBlocks;
		/** Icon of the most mined block (for the one-line board). */
		ItemStack blockIcon = ItemStack.EMPTY;
		final List<Row> blockRows = new ArrayList<>();
		Row blockMore;
		/** All minerals, most first. */
		final List<Row> minerals = new ArrayList<>();
		long totalMinerals;
		Row otherRow;
	}

	// ------------------------------------------------------------------ entry point

	public static void onHudRender(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.options.hideGui || mc.player == null || mc.level == null) {
			return;
		}
		if (mc.screen instanceof EditHudScreen) {
			return; // the editor draws its own preview
		}
		ModConfig cfg = ModConfig.get();
		Theme theme = cfg.currentTheme();
		Tracker tracker = Tracker.INSTANCE;
		if (cfg.hudVisible && tracker.isRecording()) {
			drawBoard(g, mc.font, fromTracker(tracker, cfg), cfg, theme);
		}
		if (cfg.toastsEnabled) {
			drawToasts(g, mc.font, tracker.toasts.list(), tracker.tick(), delta.getGameTimeDeltaPartialTick(false), cfg, theme);
		}
	}

	/** Draws the board in the chosen layout and returns where it ended up: {x, y, width, height} in GUI pixels. */
	static float[] drawBoard(GuiGraphics g, Font font, Panel p, ModConfig cfg, Theme theme) {
		return switch (cfg.hudLayout()) {
			case DETAIL -> drawDetail(g, font, p, cfg, theme);
			case SLIM -> drawSlim(g, font, p, cfg, theme);
			case ICONS -> drawIcons(g, font, p, cfg, theme);
			case BAR -> drawBar(g, font, p, cfg, theme);
		};
	}

	// ------------------------------------------------------------------ data

	static Panel fromTracker(Tracker tracker, ModConfig cfg) {
		Panel p = new Panel();
		p.time = formatTime(tracker.activeTicks());
		p.seconds = tracker.activeTicks() / 20;
		p.resting = tracker.isResting();

		List<Tracker.BlockTally> blocks = tracker.sortedBlocks();
		for (Tracker.BlockTally b : blocks) {
			p.totalBlocks += b.count;
		}
		if (!blocks.isEmpty()) {
			p.blockIcon = blocks.get(0).icon;
		}
		int shown = Math.min(blocks.size(), cfg.maxBlockRows);
		for (int i = 0; i < shown; i++) {
			Tracker.BlockTally b = blocks.get(i);
			p.blockRows.add(new Row(b.icon, b.name, b.count, false, false));
		}
		if (blocks.size() > shown) {
			long rest = 0;
			for (int i = shown; i < blocks.size(); i++) {
				rest += blocks.get(i).count;
			}
			p.blockMore = new Row(ItemStack.EMPTY, "외 " + (blocks.size() - shown) + "종", rest, true, true);
		}

		List<Tracker.ItemTally> others = new ArrayList<>();
		for (Tracker.ItemTally t : tracker.sortedItems()) {
			if (t.other) {
				others.add(t);
			} else {
				p.minerals.add(new Row(t.icon, t.name, t.count, false, false));
				p.totalMinerals += t.count;
			}
		}
		if (!others.isEmpty()) {
			long rest = 0;
			for (Tracker.ItemTally t : others) {
				rest += t.count;
			}
			p.otherRow = new Row(others.get(0).icon, "기타 " + others.size() + "종", rest, true, false);
		}
		return p;
	}

	/** Example numbers for the editor when nothing has been recorded yet. */
	static Panel samplePanel() {
		Panel p = new Panel();
		p.time = "12:34";
		p.seconds = 12 * 60 + 34;
		Block[] blocks = {Blocks.DEEPSLATE, Blocks.STONE, Blocks.TUFF, Blocks.DEEPSLATE_IRON_ORE, Blocks.DEEPSLATE_REDSTONE_ORE};
		long[] counts = {1046, 512, 88, 34, 21};
		for (int i = 0; i < blocks.length; i++) {
			p.blockRows.add(new Row(new ItemStack(blocks[i]), blocks[i].getName().getString(), counts[i], false, false));
		}
		p.blockIcon = new ItemStack(Blocks.DEEPSLATE);
		p.blockMore = new Row(ItemStack.EMPTY, "외 5종", 39, true, true);
		p.totalBlocks = 1740;
		ItemStack[] items = {new ItemStack(Items.REDSTONE), new ItemStack(Items.RAW_IRON), new ItemStack(Items.LAPIS_LAZULI),
				new ItemStack(Items.RAW_COPPER), new ItemStack(Items.COAL), new ItemStack(Items.DIAMOND)};
		long[] itemCounts = {112, 47, 38, 26, 13, 5};
		for (int i = 0; i < items.length; i++) {
			p.minerals.add(new Row(items[i], items[i].getHoverName().getString(), itemCounts[i], false, false));
			p.totalMinerals += itemCounts[i];
		}
		p.otherRow = new Row(new ItemStack(Items.COBBLED_DEEPSLATE), "기타 4종", 1208, true, false);
		return p;
	}

	static List<ToastManager.Toast> sampleToasts() {
		List<ToastManager.Toast> list = new ArrayList<>();
		ItemStack diamond = new ItemStack(Items.DIAMOND);
		ItemStack redstone = new ItemStack(Items.REDSTONE);
		ItemStack iron = new ItemStack(Items.RAW_IRON);
		list.add(new ToastManager.Toast("a", diamond, diamond.getHoverName().getString(), 2, 5, -1000));
		list.add(new ToastManager.Toast("b", redstone, redstone.getHoverName().getString(), 5, 112, -1000));
		list.add(new ToastManager.Toast("c", iron, iron.getHoverName().getString(), 1, 47, -1000));
		return list;
	}

	/** Minerals for the list layouts: the first {@code max}, "외 N종", then "기타". */
	static List<Row> mineralLines(Panel p, int max) {
		List<Row> lines = new ArrayList<>();
		int shown = Math.min(p.minerals.size(), max);
		lines.addAll(p.minerals.subList(0, shown));
		if (p.minerals.size() > shown) {
			long rest = 0;
			for (int i = shown; i < p.minerals.size(); i++) {
				rest += p.minerals.get(i).count();
			}
			lines.add(new Row(ItemStack.EMPTY, "외 " + (p.minerals.size() - shown) + "종", rest, true, true));
		}
		if (p.otherRow != null) {
			lines.add(p.otherRow);
		}
		return lines;
	}

	/** Minerals for the slot layout: as many as fit, the rest folded into one slot, "기타" last. */
	static List<Row> slotRows(Panel p, int capacity) {
		List<Row> slots = new ArrayList<>();
		int room = capacity - (p.otherRow != null ? 1 : 0);
		if (p.minerals.size() <= room) {
			slots.addAll(p.minerals);
		} else {
			int shown = Math.max(0, room - 1);
			slots.addAll(p.minerals.subList(0, shown));
			long rest = 0;
			for (int i = shown; i < p.minerals.size(); i++) {
				rest += p.minerals.get(i).count();
			}
			slots.add(new Row(ItemStack.EMPTY, "외", rest, true, true));
		}
		if (p.otherRow != null) {
			slots.add(p.otherRow);
		}
		return slots;
	}

	// ------------------------------------------------------------------ layouts

	/** 자세히: mined blocks by type, then minerals with their bars. */
	static float[] drawDetail(GuiGraphics g, Font font, Panel p, ModConfig cfg, Theme t) {
		List<Row> blockLines = new ArrayList<>(p.blockRows);
		if (p.blockMore != null) {
			blockLines.add(p.blockMore);
		}
		List<Row> itemLines = mineralLines(p, cfg.maxItemRows);
		int nameW = Math.max(font.width("아직 캔 블록이 없어요"), font.width("아직 먹은 광물이 없어요"));
		int countW = Math.max(font.width("개수"), font.width("획득"));
		for (List<Row> lines : List.of(blockLines, itemLines)) {
			for (Row r : lines) {
				nameW = Math.max(nameW, Math.min(NAME_MAX, font.width(r.name())));
				countW = Math.max(countW, font.width(fmt(r.count())));
			}
		}
		String blocks = fmt(p.totalBlocks);
		String minerals = fmt(p.totalMinerals);
		int w = Math.max(MIN_WIDTH, Math.max(20 + nameW + 8 + countW + 9,
				Math.max(headerWidth(font), heroWidth(font, p, "캔 블록", blocks, "먹은 광물", minerals))));
		int h = 87 + BLOCK_ROW * Math.max(1, blockLines.size()) + ROW * Math.max(1, itemLines.size());
		int right = w - 9;

		float[] rect = begin(g, cfg.panelX, cfg.panelY, cfg.panelScale, w, h);
		board(g, font, p, t, w, h);
		hero(g, font, p, t, w, "캔 블록", blocks, t.text, "먹은 광물", minerals, t.text);
		int y = 50;
		t.text(g, font, "캔 블록", 8, y, t.sub);
		t.textRight(g, font, "개수", right, y, t.dim);
		y += 13;
		if (blockLines.isEmpty()) {
			t.text(g, font, "아직 캔 블록이 없어요", 20, y, t.dim);
			y += BLOCK_ROW;
		}
		for (Row r : blockLines) {
			if (r.dots()) {
				dots(g, t, 10, y + 5);
			} else {
				icon(g, r.icon(), 9, y);
			}
			int color = r.dim() ? t.dim : t.text;
			t.text(g, font, ellipsize(font, r.name(), right - countW - 8 - 20), 20, y, color);
			t.textRight(g, font, fmt(r.count()), right, y, color);
			y += BLOCK_ROW;
		}
		g.fill(8, y + 2, w - 8, y + 3, t.sep);
		y += 7;
		t.text(g, font, "먹은 광물", 8, y, t.sub);
		t.textRight(g, font, "획득", right, y, t.dim);
		y += 15;
		if (itemLines.isEmpty()) {
			t.text(g, font, "아직 먹은 광물이 없어요", 20, y, t.dim);
		}
		long top = topCount(itemLines);
		for (Row r : itemLines) {
			mineralRow(g, font, t, r, top, w, right, right - countW - 8 - 20, y);
			y += ROW;
		}
		g.pose().popPose();
		return rect;
	}

	/** 슬림: the two totals beside the time, then the minerals. */
	static float[] drawSlim(GuiGraphics g, Font font, Panel p, ModConfig cfg, Theme t) {
		List<Row> lines = mineralLines(p, cfg.maxItemRows);
		String blocks = fmt(p.totalBlocks);
		String minerals = fmt(p.totalMinerals);
		int countW = 0;
		int nameW = font.width("아직 먹은 광물이 없어요");
		for (Row r : lines) {
			countW = Math.max(countW, font.width(fmt(r.count())));
			nameW = Math.max(nameW, Math.min(NAME_MAX, font.width(r.name())));
		}
		int w = Math.max(MIN_WIDTH, Math.max(20 + nameW + 8 + countW + 9,
				Math.max(headerWidth(font), heroWidth(font, p, "캔 블록", blocks, "먹은 광물", minerals))));
		int h = SLIM_TOP + ROW * Math.max(1, lines.size()) + 2;
		int countR = w - 9;

		float[] rect = begin(g, cfg.panelX, cfg.panelY, cfg.panelScale, w, h);
		board(g, font, p, t, w, h);
		hero(g, font, p, t, w, "캔 블록", blocks, t.text, "먹은 광물", minerals, t.text);
		int y = SLIM_TOP;
		if (lines.isEmpty()) {
			t.text(g, font, "아직 먹은 광물이 없어요", 20, y, t.dim);
		}
		long top = topCount(lines);
		for (Row r : lines) {
			mineralRow(g, font, t, r, top, w, countR, countR - countW - 8 - 20, y);
			y += ROW;
		}
		g.pose().popPose();
		return rect;
	}

	/** 아이콘: the two totals beside the time, then the minerals as tinted slots with their counts. */
	static float[] drawIcons(GuiGraphics g, Font font, Panel p, ModConfig cfg, Theme t) {
		String blocks = fmt(p.totalBlocks);
		String minerals = fmt(p.totalMinerals);
		int w = Math.max(MIN_WIDTH, Math.max(headerWidth(font), heroWidth(font, p, "캔 블록", blocks, "먹은 광물", minerals)));
		int perRow = Math.max(1, (w - 16 + SLOT_GAP) / (SLOT + SLOT_GAP));
		List<Row> slots = slotRows(p, perRow * SLOT_ROWS);
		int rows = Math.max(1, (slots.size() + perRow - 1) / perRow);
		int h = SLOTS_TOP + rows * (SLOT + SLOT_GAP) - SLOT_GAP + 8;

		float[] rect = begin(g, cfg.panelX, cfg.panelY, cfg.panelScale, w, h);
		board(g, font, p, t, w, h);
		hero(g, font, p, t, w, "캔 블록", blocks, t.text, "먹은 광물", minerals, t.text);
		if (slots.isEmpty()) {
			t.text(g, font, "아직 먹은 광물이 없어요", 8, SLOTS_TOP + 6, t.dim);
		}
		int inRow = Math.min(perRow, slots.size());
		int x0 = (w - (inRow * SLOT + (inRow - 1) * SLOT_GAP)) / 2;
		PoseStack pose = g.pose();
		for (int i = 0; i < slots.size(); i++) {
			Row r = slots.get(i);
			int sx = x0 + (i % perRow) * (SLOT + SLOT_GAP);
			int sy = SLOTS_TOP + (i / perRow) * (SLOT + SLOT_GAP);
			int tint = r.dim() || r.dots() ? Palette.OTHER : Palette.of(r.icon());
			Theme.rrect(g, sx, sy, SLOT, SLOT, Theme.withAlpha(tint, t.slotAlpha), 1);
			g.fill(sx + 1, sy + SLOT - 1, sx + SLOT - 1, sy + SLOT, Theme.withAlpha(tint, 200));
			if (r.dots()) {
				dots(g, t, sx + 6, sy + 8);
			} else if (!r.icon().isEmpty()) {
				g.renderItem(r.icon(), sx + 2, sy + 1);
			}
			// above the item, like the game's own stack counts
			pose.pushPose();
			pose.translate(0, 0, 200);
			String count = slotCount(r.count());
			g.drawString(font, count, sx + SLOT - 1 - font.width(count), sy + SLOT - 9, r.dim() ? 0xFFAAAAAA : 0xFFFFFFFF, true);
			pose.popPose();
		}
		pose.popPose();
		return rect;
	}

	/** 한 줄: everything on one line, centred at the top of the screen by default. */
	static float[] drawBar(GuiGraphics g, Font font, Panel p, ModConfig cfg, Theme t) {
		int w = bar(null, font, p, t);
		float scale = cfg.barScale;
		float center = cfg.barCenterX < 0 ? g.guiWidth() / 2f : cfg.barCenterX;
		float[] rect = begin(g, center - w * scale / 2f, cfg.barY, scale, w, BAR_HEIGHT);
		t.panel(g, 0, 0, w, BAR_HEIGHT, false);
		Theme.stripe(g, 0, 3, BAR_HEIGHT - 6, t.status(p.resting));
		bar(g, font, p, t);
		g.pose().popPose();
		return rect;
	}

	/** Lays out the one-line board and draws it when {@code g} is not null. Returns its width. */
	private static int bar(GuiGraphics g, Font font, Panel p, Theme t) {
		int x = 7;
		if (g != null) {
			icon(g, headerIcon(), x, 3);
		}
		x += 10;
		x = barText(g, font, t, p.time, x, p.resting ? t.dim : t.text) + 6;
		x = barSep(g, t, x);
		if (!p.blockIcon.isEmpty()) {
			if (g != null) {
				icon(g, p.blockIcon, x, 3);
			}
			x += 10;
		}
		x = barText(g, font, t, fmt(p.totalBlocks), x, t.text) + 6;
		x = barSep(g, t, x);
		int shown = Math.min(p.minerals.size(), BAR_MAX_ITEMS);
		for (int i = 0; i < shown; i++) {
			Row r = p.minerals.get(i);
			if (g != null) {
				icon(g, r.icon(), x, 3);
			}
			x += 10;
			x = barText(g, font, t, fmt(r.count()), x, t.text) + 6;
		}
		if (p.minerals.size() > shown) {
			x = barText(g, font, t, "+" + (p.minerals.size() - shown) + "종", x, t.dim) + 6;
		}
		if (p.minerals.isEmpty()) {
			x = barText(g, font, t, "아직 없음", x, t.dim) + 6;
		}
		if (p.otherRow != null) {
			x = barSep(g, t, x);
			x = barText(g, font, t, "기타", x, t.dim) + 4;
			x = barText(g, font, t, fmt(p.otherRow.count()), x, t.dim) + 6;
		}
		return x + 2;
	}

	private static int barText(GuiGraphics g, Font font, Theme t, String s, int x, int color) {
		if (g != null) {
			t.text(g, font, s, x, 3, color);
		}
		return x + font.width(s);
	}

	private static int barSep(GuiGraphics g, Theme t, int x) {
		if (g != null) {
			g.fill(x - 1, 3, x, BAR_HEIGHT - 3, t.sep);
		}
		return x + 7;
	}

	// ------------------------------------------------------------------ pieces

	/** Pushes the pose for a w x h panel at (px, py), kept on screen. Returns {x, y, width, height}. */
	private static float[] begin(GuiGraphics g, float px, float py, float scale, int w, int h) {
		float x = clamp(px, 0, Math.max(0, g.guiWidth() - w * scale));
		float y = clamp(py, 0, Math.max(0, g.guiHeight() - h * scale));
		PoseStack pose = g.pose();
		pose.pushPose();
		pose.translate(x, y, 0);
		pose.scale(scale, scale, 1f);
		return new float[]{x, y, w * scale, h * scale};
	}

	private static int headerWidth(Font font) {
		int pill = Math.max(font.width("측정 중"), font.width("쉬는 중")) + 15;
		return 19 + font.width("광질 측정") + 10 + pill + 7;
	}

	private static int heroLabelX(Font font, Panel p) {
		return Math.max(76, 8 + Theme.bigWidth(font, p.time, BIG) + 12);
	}

	private static int heroWidth(Font font, Panel p, String l1, String v1, String l2, String v2) {
		return heroLabelX(font, p) + Math.max(font.width(l1), font.width(l2)) + 8 + Math.max(font.width(v1), font.width(v2)) + 8;
	}

	/** The card, the status stripe on the left, and the header line. */
	private static void board(GuiGraphics g, Font font, Panel p, Theme t, int w, int h) {
		t.panel(g, 0, 0, w, h, false);
		Theme.stripe(g, 0, 5, h - 10, t.status(p.resting));
		icon(g, headerIcon(), 8, 5);
		t.text(g, font, "광질 측정", 19, 5, t.sub);
		t.pill(g, font, p.resting ? "쉬는 중" : "측정 중", w - 7, 5, t.status(p.resting));
	}

	/** The elapsed time in big numbers, two totals beside it, and a thin line under them. */
	private static void hero(GuiGraphics g, Font font, Panel p, Theme t, int w,
			String l1, String v1, int c1, String l2, String v2, int c2) {
		t.big(g, font, p.time, 8, 20, BIG, p.resting ? t.dim : t.head);
		int lx = heroLabelX(font, p);
		t.text(g, font, l1, lx, 20, t.dim);
		t.textRight(g, font, v1, w - 8, 20, c1);
		t.text(g, font, l2, lx, 32, t.dim);
		t.textRight(g, font, v2, w - 8, 32, c2);
		g.fill(8, 45, w - 8, 46, t.sep);
	}

	/** A mineral line: a bar in the mineral's colour behind it, icon, name and count. */
	private static void mineralRow(GuiGraphics g, Font font, Theme t, Row r, long top, int w, int countR, int nameMax, int y) {
		if (!r.dim() && !r.dots() && top > 0) {
			int color = Palette.of(r.icon());
			int x0 = 5;
			int fill = x0 + Math.max(4, (int) Math.round((w - 10) * (double) r.count() / top));
			Theme.rrect(g, x0, y - 3, fill - x0, 13, Theme.withAlpha(color, t.barAlpha), 1);
			g.fill(x0, y - 2, x0 + 1, y + 9, color);
		}
		if (r.dots()) {
			dots(g, t, 10, y + 5);
		} else {
			icon(g, r.icon(), 9, y);
		}
		int color = r.dim() ? t.dim : t.text;
		t.text(g, font, ellipsize(font, r.name(), nameMax), 20, y, color);
		t.textRight(g, font, fmt(r.count()), countR, y, color);
	}

	private static long topCount(List<Row> rows) {
		long top = 0;
		for (Row r : rows) {
			if (!r.dim() && !r.dots()) {
				top = Math.max(top, r.count());
			}
		}
		return top;
	}

	/** "…" for "외 N종": three small dots. */
	private static void dots(GuiGraphics g, Theme t, int x, int y) {
		for (int i = 0; i < 3; i++) {
			g.fill(x + i * 3, y, x + i * 3 + 1, y + 1, t.dim);
		}
	}

	// ------------------------------------------------------------------ toasts

	static void drawToasts(GuiGraphics g, Font font, List<ToastManager.Toast> toasts, long tick, float partialTick,
			ModConfig cfg, Theme theme) {
		if (toasts.isEmpty()) {
			return;
		}
		float scale = cfg.toastScale;
		PoseStack pose = g.pose();
		pose.pushPose();
		pose.scale(scale, scale, 1f);
		float right = (g.guiWidth() - cfg.toastRight) / scale;
		float y = cfg.toastY / scale;
		float maxY = g.guiHeight() / scale - TOAST_HEIGHT;
		for (ToastManager.Toast t : toasts) {
			if (y > maxY) {
				break;
			}
			float shown = t.visibility(tick, partialTick);
			if (shown > 0f) {
				int w = toastWidth(font, t);
				float eased = 1f - (1f - shown) * (1f - shown);
				drawToast(g, font, theme, t, right - w + (1f - eased) * (w + 10), y);
			}
			y += TOAST_HEIGHT + TOAST_GAP;
		}
		pose.popPose();
	}

	/** Toasts at rest (for the editor). Returns {x, y, width, height} in GUI pixels. */
	static float[] drawToastsStatic(GuiGraphics g, Font font, List<ToastManager.Toast> toasts, ModConfig cfg, Theme theme) {
		float scale = cfg.toastScale;
		int maxW = 0;
		for (ToastManager.Toast t : toasts) {
			maxW = Math.max(maxW, toastWidth(font, t));
		}
		float height = toasts.size() * (TOAST_HEIGHT + TOAST_GAP) - TOAST_GAP;
		float rightGui = clamp(g.guiWidth() - cfg.toastRight, maxW * scale, g.guiWidth());
		float topGui = clamp(cfg.toastY, 0, Math.max(0, g.guiHeight() - height * scale));

		PoseStack pose = g.pose();
		pose.pushPose();
		pose.scale(scale, scale, 1f);
		float right = rightGui / scale;
		float y = topGui / scale;
		for (ToastManager.Toast t : toasts) {
			drawToast(g, font, theme, t, right - toastWidth(font, t), y);
			y += TOAST_HEIGHT + TOAST_GAP;
		}
		pose.popPose();
		return new float[]{rightGui - maxW * scale, topGui, maxW * scale, height * scale};
	}

	private static int toastWidth(Font font, ToastManager.Toast t) {
		String name = ellipsize(font, t.name, NAME_MAX);
		return 16 + font.width(name) + 4 + font.width("+" + fmt(t.amount)) + 5;
	}

	/** One small line as before: icon, name and a green "+N". */
	private static void drawToast(GuiGraphics g, Font font, Theme theme, ToastManager.Toast t, float x, float y) {
		String name = ellipsize(font, t.name, NAME_MAX);
		String plus = "+" + fmt(t.amount);
		int w = toastWidth(font, t);
		PoseStack pose = g.pose();
		pose.pushPose();
		pose.translate(x, y, 0);
		theme.panel(g, 0, 0, w, TOAST_HEIGHT, false);
		icon(g, t.icon, 4, 3);
		theme.text(g, font, name, 16, 3, theme.text);
		theme.text(g, font, plus, 16 + font.width(name) + 4, 3, theme.plus());
		pose.popPose();
	}

	// ------------------------------------------------------------------ helpers

	/** The game's own item icon, at half size (8x8 panel pixels). */
	static void icon(GuiGraphics g, ItemStack stack, int x, int y) {
		if (stack == null || stack.isEmpty()) {
			return;
		}
		PoseStack pose = g.pose();
		pose.pushPose();
		pose.translate(x, y, 0);
		pose.scale(0.5f, 0.5f, 1f);
		g.renderItem(stack, 0, 0);
		pose.popPose();
	}

	static ItemStack headerIcon() {
		if (headerIcon == null) {
			headerIcon = new ItemStack(Items.IRON_PICKAXE);
		}
		return headerIcon;
	}

	static String ellipsize(Font font, String text, int maxWidth) {
		if (font.width(text) <= maxWidth) {
			return text;
		}
		return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("…"))) + "…";
	}

	/** Stack counts in a slot: 1,208 becomes 1.2k so it fits. */
	static String slotCount(long n) {
		if (n >= 10000) {
			return (n / 1000) + "k";
		}
		if (n >= 1000) {
			return String.format(Locale.ROOT, "%.1fk", (n / 100) / 10.0);
		}
		return Long.toString(n);
	}

	static String fmt(long n) {
		return String.format(Locale.ROOT, "%,d", n);
	}

	static String formatTime(long ticks) {
		long seconds = ticks / 20;
		long h = seconds / 3600;
		long m = (seconds % 3600) / 60;
		long s = seconds % 60;
		if (h > 0) {
			return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s);
		}
		return String.format(Locale.ROOT, "%02d:%02d", m, s);
	}

	static float clamp(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}
}
