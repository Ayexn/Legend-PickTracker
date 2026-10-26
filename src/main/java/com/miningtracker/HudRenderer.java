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
 * are the game's real item icons.
 */
public final class HudRenderer {
	static final int PITCH = 12;
	static final int MIN_WIDTH = 182;
	static final int NAME_MAX = 124;
	static final int TOAST_HEIGHT = 14;
	static final int TOAST_GAP = 3;
	/** Rates (per minute / per hour) appear once this much time has been measured. */
	static final int RATE_MIN_SECONDS = 30;
	static final int SLOT = 22;
	static final int SLOT_GAP = 2;
	static final int SLOT_ROWS = 2;
	static final int BAR_HEIGHT = 14;
	static final int BAR_MAX_ITEMS = 8;

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
		ItemStack iron = new ItemStack(Items.RAW_IRON);
		ItemStack redstone = new ItemStack(Items.REDSTONE);
		list.add(new ToastManager.Toast("a", diamond, diamond.getHoverName().getString(), 2, -1000));
		list.add(new ToastManager.Toast("b", iron, iron.getHoverName().getString(), 8, -1000));
		list.add(new ToastManager.Toast("c", redstone, redstone.getHoverName().getString(), 5, -1000));
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

	/** 자세히: mined blocks by type, then minerals. */
	static float[] drawDetail(GuiGraphics g, Font font, Panel p, ModConfig cfg, Theme t) {
		List<Row> blockLines = new ArrayList<>(p.blockRows);
		if (p.blockMore != null) {
			blockLines.add(p.blockMore);
		}
		List<Row> itemLines = mineralLines(p, cfg.maxItemRows);
		int nameW = 0;
		int countW = Math.max(font.width("총 " + fmt(p.totalBlocks)), font.width("획득"));
		for (List<Row> lines : List.of(blockLines, itemLines)) {
			for (Row r : lines) {
				nameW = Math.max(nameW, Math.min(NAME_MAX, font.width(r.name())));
				countW = Math.max(countW, font.width(fmt(r.count())));
			}
		}
		int w = Math.max(MIN_WIDTH, Math.max(18 + nameW + 8 + countW + 6, headerWidth(font, p)));
		int h = 19 + PITCH * (1 + Math.max(1, blockLines.size())) + 5 + PITCH * (1 + Math.max(1, itemLines.size())) + 5;
		int right = w - 6;

		float[] rect = begin(g, cfg.panelX, cfg.panelY, cfg.panelScale, w, h);
		t.frame(g, 0, 0, w, h, false);
		header(g, font, p, t, w);
		int y = 19;
		t.text(g, font, "캔 블록", 6, y + 2, t.label);
		t.textRight(g, font, "총 " + fmt(p.totalBlocks), right, y + 2, t.text);
		y += PITCH;
		if (blockLines.isEmpty()) {
			t.text(g, font, "아직 캔 블록이 없어요", 18, y + 2, t.dim);
			y += PITCH;
		}
		for (Row r : blockLines) {
			listRow(g, font, t, r, NAME_MAX, right, 0, null, y);
			y += PITCH;
		}
		g.fill(5, y + 1, w - 5, y + 2, t.sep);
		y += 5;
		t.text(g, font, "먹은 광물", 6, y + 2, t.label);
		t.textRight(g, font, "획득", right, y + 2, t.dim);
		y += PITCH;
		if (itemLines.isEmpty()) {
			t.text(g, font, "아직 먹은 광물이 없어요", 18, y + 2, t.dim);
		}
		for (Row r : itemLines) {
			listRow(g, font, t, r, NAME_MAX, right, 0, null, y);
			y += PITCH;
		}
		g.pose().popPose();
		return rect;
	}

	/** 슬림: mined blocks in one line, minerals with a per-hour column. */
	static float[] drawSlim(GuiGraphics g, Font font, Panel p, ModConfig cfg, Theme t) {
		List<Row> lines = mineralLines(p, cfg.maxItemRows);
		boolean rates = p.seconds >= RATE_MIN_SECONDS;
		String blockRate = "분당 " + (rates ? fmt(perMinute(p.totalBlocks, p.seconds)) : "-");
		List<String> lineRates = new ArrayList<>();
		int countW = Math.max(font.width("획득"), font.width(fmt(p.totalBlocks)));
		int rateW = Math.max(font.width("시간당"), font.width(blockRate));
		int nameW = 0;
		for (Row r : lines) {
			String rate = rates ? fmt(perHour(r.count(), p.seconds)) : "-";
			lineRates.add(rate);
			countW = Math.max(countW, font.width(fmt(r.count())));
			rateW = Math.max(rateW, font.width(rate));
			nameW = Math.max(nameW, Math.min(NAME_MAX, font.width(r.name())));
		}
		int w = Math.max(MIN_WIDTH, Math.max(18 + nameW + 8 + countW + 8 + rateW + 6, headerWidth(font, p)));
		int h = 19 + PITCH + 5 + PITCH + PITCH * Math.max(1, lines.size()) + 5;
		int rateR = w - 6;
		int countR = rateR - rateW - 8;
		int nameMax = countR - countW - 8 - 18;

		float[] rect = begin(g, cfg.panelX, cfg.panelY, cfg.panelScale, w, h);
		t.frame(g, 0, 0, w, h, false);
		header(g, font, p, t, w);
		int y = 19;
		t.text(g, font, "캔 블록", 6, y + 2, t.label);
		t.textRight(g, font, fmt(p.totalBlocks), countR, y + 2, t.text);
		t.textRight(g, font, blockRate, rateR, y + 2, t.dim);
		y += PITCH;
		g.fill(5, y + 1, w - 5, y + 2, t.sep);
		y += 5;
		t.text(g, font, "먹은 광물", 6, y + 2, t.label);
		t.textRight(g, font, "획득", countR, y + 2, t.dim);
		t.textRight(g, font, "시간당", rateR, y + 2, t.dim);
		y += PITCH;
		if (lines.isEmpty()) {
			t.text(g, font, "아직 먹은 광물이 없어요", 18, y + 2, t.dim);
		}
		for (int i = 0; i < lines.size(); i++) {
			listRow(g, font, t, lines.get(i), nameMax, countR, rateR, lineRates.get(i), y);
			y += PITCH;
		}
		g.pose().popPose();
		return rect;
	}

	/** 아이콘: totals on two lines, minerals as inventory-style slots. */
	static float[] drawIcons(GuiGraphics g, Font font, Panel p, ModConfig cfg, Theme t) {
		boolean rates = p.seconds >= RATE_MIN_SECONDS;
		String blockRate = "분당 " + (rates ? fmt(perMinute(p.totalBlocks, p.seconds)) : "-");
		String itemRate = "시간당 " + (rates ? fmt(perHour(p.totalMinerals, p.seconds)) : "-");
		int line1 = 6 + font.width("캔 블록") + 4 + font.width(fmt(p.totalBlocks)) + 10 + font.width(blockRate) + 6;
		int line2 = 6 + font.width("먹은 광물") + 4 + font.width(fmt(p.totalMinerals)) + 10 + font.width(itemRate) + 6;
		int w = Math.max(MIN_WIDTH, Math.max(headerWidth(font, p), Math.max(line1, line2)));
		int perRow = Math.max(1, (w - 12 + SLOT_GAP) / (SLOT + SLOT_GAP));
		List<Row> slots = slotRows(p, perRow * SLOT_ROWS);
		int rows = Math.max(1, (slots.size() + perRow - 1) / perRow);
		int gridTop = 19 + PITCH + PITCH + 1;
		int h = gridTop + rows * (SLOT + SLOT_GAP) - SLOT_GAP + 6;

		float[] rect = begin(g, cfg.panelX, cfg.panelY, cfg.panelScale, w, h);
		t.frame(g, 0, 0, w, h, false);
		header(g, font, p, t, w);
		int y = 19;
		t.text(g, font, "캔 블록", 6, y + 2, t.label);
		t.text(g, font, fmt(p.totalBlocks), 6 + font.width("캔 블록") + 4, y + 2, t.text);
		t.textRight(g, font, blockRate, w - 6, y + 2, t.dim);
		y += PITCH;
		t.text(g, font, "먹은 광물", 6, y + 2, t.label);
		t.text(g, font, fmt(p.totalMinerals), 6 + font.width("먹은 광물") + 4, y + 2, t.text);
		t.textRight(g, font, itemRate, w - 6, y + 2, t.dim);
		if (slots.isEmpty()) {
			t.text(g, font, "아직 먹은 광물이 없어요", 6, gridTop + 7, t.dim);
		}
		PoseStack pose = g.pose();
		for (int i = 0; i < slots.size(); i++) {
			Row r = slots.get(i);
			int sx = 6 + (i % perRow) * (SLOT + SLOT_GAP);
			int sy = gridTop + (i / perRow) * (SLOT + SLOT_GAP);
			t.slot(g, sx, sy, SLOT);
			if (r.dots()) {
				for (int d = 0; d < 3; d++) {
					g.fill(sx + 5 + d * 5, sy + 8, sx + 7 + d * 5, sy + 10, t.dim);
				}
			} else if (!r.icon().isEmpty()) {
				g.renderItem(r.icon(), sx + 3, sy + 3);
			}
			// above the item, like the game's own stack counts
			pose.pushPose();
			pose.translate(0, 0, 200);
			if (r.dim() && !r.dots()) {
				g.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, 0x70000000);
			}
			String count = slotCount(r.count());
			g.drawString(font, count, sx + SLOT - 2 - font.width(count), sy + SLOT - 10, r.dim() ? 0xFFAAAAAA : 0xFFFFFFFF, true);
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
		t.frame(g, 0, 0, w, BAR_HEIGHT, false);
		bar(g, font, p, t);
		g.pose().popPose();
		return rect;
	}

	/** Lays out the one-line board and draws it when {@code g} is not null. Returns its width. */
	private static int bar(GuiGraphics g, Font font, Panel p, Theme t) {
		int x = 5;
		if (g != null) {
			icon(g, headerIcon(), x, 3);
		}
		x += 10;
		x = barText(g, font, t, p.time, x, t.sub) + 4;
		if (g != null) {
			g.fill(x, 5, x + 4, 9, p.resting ? t.yellow : t.green);
		}
		x += 8;
		x = barSep(g, t, x);
		x = barText(g, font, t, "블록", x, t.label) + 3;
		x = barText(g, font, t, fmt(p.totalBlocks), x, t.text) + 2;
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
			x = barSep(g, t, x - 4);
			x = barText(g, font, t, "기타 " + fmt(p.otherRow.count()), x, t.dim) + 6;
		}
		return x - 1;
	}

	private static int barText(GuiGraphics g, Font font, Theme t, String s, int x, int color) {
		if (g != null) {
			t.text(g, font, s, x, 3, color);
		}
		return x + font.width(s);
	}

	private static int barSep(GuiGraphics g, Theme t, int x) {
		if (g != null) {
			g.fill(x + 2, 3, x + 3, 11, (t.dim & 0x00FFFFFF) | 0x99000000);
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

	private static int headerWidth(Font font, Panel p) {
		int status = 8 + Math.max(font.width("측정 중"), font.width("쉬는 중")) + 4 + font.width(p.time);
		return 18 + font.width("광질 측정") + 12 + status + 6;
	}

	/** Icon, title, state and measured time, then the coloured rule. */
	private static void header(GuiGraphics g, Font font, Panel p, Theme t, int w) {
		icon(g, headerIcon(), 6, 4);
		t.text(g, font, "광질 측정", 18, 4, t.head);
		int hx = w - 6;
		t.textRight(g, font, p.time, hx, 4, t.sub);
		hx -= font.width(p.time) + 4;
		String status = p.resting ? "쉬는 중" : "측정 중";
		int color = p.resting ? t.yellow : t.green;
		t.textRight(g, font, status, hx, 4, color);
		hx -= font.width(status) + 4;
		g.fill(hx - 4, 6, hx, 10, color);
		g.fill(t.inset(), 15, w - t.inset(), 16, t.rule);
	}

	/** A list line: icon (or dots), name, count, and optionally a rate in its own column. */
	private static void listRow(GuiGraphics g, Font font, Theme t, Row r, int nameMax, int countR, int rateR, String rate, int y) {
		if (r.dots()) {
			g.fill(6, y + 7, 7, y + 8, t.dim);
			g.fill(9, y + 7, 10, y + 8, t.dim);
			g.fill(12, y + 7, 13, y + 8, t.dim);
		} else {
			icon(g, r.icon(), 6, y + 2);
		}
		int color = r.dim() ? t.dim : t.text;
		t.text(g, font, ellipsize(font, r.name(), nameMax), 18, y + 2, color);
		t.textRight(g, font, fmt(r.count()), countR, y + 2, color);
		if (rate != null) {
			t.textRight(g, font, rate, rateR, y + 2, r.dim() ? t.dim : t.sub);
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

	private static void drawToast(GuiGraphics g, Font font, Theme theme, ToastManager.Toast t, float x, float y) {
		String name = ellipsize(font, t.name, NAME_MAX);
		String plus = "+" + fmt(t.amount);
		int w = toastWidth(font, t);
		PoseStack pose = g.pose();
		pose.pushPose();
		pose.translate(x, y, 0);
		theme.frame(g, 0, 0, w, TOAST_HEIGHT, false);
		icon(g, t.icon, 4, 3);
		theme.text(g, font, name, 16, 3, theme.text);
		theme.text(g, font, plus, 16 + font.width(name) + 4, 3, theme.plus);
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

	static long perMinute(long count, long seconds) {
		return seconds <= 0 ? 0 : Math.round(count * 60.0 / seconds);
	}

	static long perHour(long count, long seconds) {
		return seconds <= 0 ? 0 : Math.round(count * 3600.0 / seconds);
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
