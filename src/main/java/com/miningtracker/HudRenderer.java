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
 * Draws the tally board (집계판) and the pickup toasts (획득 알림).
 *
 * <p>Layout numbers are in "panel pixels": everything is drawn inside a pose scaled by the
 * configured scale (default 2/3, which matches the size of the server's own HUD panels at GUI
 * scale 3). Item icons are the game's real item icons, drawn at half size.
 */
public final class HudRenderer {
	static final int EDGE = 0xFF0F0F0F;
	static final int BORDER = 0xFF383D4A;
	static final int BG = 0xFF202326;
	static final int RULE = 0xFF3664BD;
	static final int TEXT = 0xFFEBEEF2;
	static final int HEAD = 0xFFFCFCFC;
	static final int SUB = 0xFF9DB6D5;
	static final int GREEN = 0xFF7BC37B;
	static final int YELLOW = 0xFFE3C46A;
	static final int LABEL = 0xFF6E9EEB;
	static final int DIM = 0xFF808A98;
	static final int ZERO = 0xFF565E6C;
	static final int SEP = 0xFF30353E;
	static final int PLUS = 0xFF8AE28A;
	static final int TAG_INV = 0xFFDEC48C;

	static final int PITCH = 12;
	static final int MIN_WIDTH = 182;
	static final int NAME_MAX = 124;
	static final int TOAST_HEIGHT = 14;
	static final int TOAST_GAP = 3;

	private static ItemStack headerIcon;

	private HudRenderer() {
	}

	/** One line of the board. For item rows a = total, b = storage, c = inventory. */
	record Row(ItemStack icon, String name, long a, long b, long c, int color, boolean dots) {
	}

	static final class Panel {
		String time = "00:00";
		boolean running = true;
		long totalBlocks;
		final List<Row> blockRows = new ArrayList<>();
		Row blockMore;
		final List<Row> itemRows = new ArrayList<>();
		Row itemMore;
		Row otherRow;
	}

	private static final class Layout {
		int width;
		int height;
		int colAll;
		int colStore;
		int colInv;
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
		Tracker tracker = Tracker.INSTANCE;
		if (cfg.hudVisible) {
			drawPanel(g, mc.font, fromTracker(tracker, cfg), cfg.panelX, cfg.panelY, cfg.panelScale);
		}
		if (cfg.toastsEnabled) {
			drawToasts(g, mc.font, tracker.toasts.list(), tracker.tick(), delta.getGameTimeDeltaPartialTick(false), cfg);
		}
	}

	// ------------------------------------------------------------------ data

	static Panel fromTracker(Tracker tracker, ModConfig cfg) {
		Panel p = new Panel();
		p.time = formatTime(tracker.activeTicks());
		p.running = tracker.isRunning();

		List<Tracker.BlockTally> blocks = tracker.sortedBlocks();
		long total = 0;
		for (Tracker.BlockTally b : blocks) {
			total += b.count;
		}
		p.totalBlocks = total;
		int shown = Math.min(blocks.size(), cfg.maxBlockRows);
		for (int i = 0; i < shown; i++) {
			Tracker.BlockTally b = blocks.get(i);
			p.blockRows.add(new Row(b.icon, b.name, b.count, 0, 0, TEXT, false));
		}
		if (blocks.size() > shown) {
			long rest = 0;
			for (int i = shown; i < blocks.size(); i++) {
				rest += blocks.get(i).count;
			}
			p.blockMore = new Row(ItemStack.EMPTY, "외 " + (blocks.size() - shown) + "종", rest, 0, 0, DIM, true);
		}

		List<Tracker.ItemTally> minerals = new ArrayList<>();
		List<Tracker.ItemTally> others = new ArrayList<>();
		for (Tracker.ItemTally t : tracker.sortedItems()) {
			(t.other ? others : minerals).add(t);
		}
		int shownItems = Math.min(minerals.size(), cfg.maxItemRows);
		for (int i = 0; i < shownItems; i++) {
			Tracker.ItemTally t = minerals.get(i);
			p.itemRows.add(new Row(t.icon, t.name, t.total(), t.storage, t.inventory, TEXT, false));
		}
		if (minerals.size() > shownItems) {
			long a = 0, b = 0, c = 0;
			for (int i = shownItems; i < minerals.size(); i++) {
				Tracker.ItemTally t = minerals.get(i);
				a += t.total();
				b += t.storage;
				c += t.inventory;
			}
			p.itemMore = new Row(ItemStack.EMPTY, "외 " + (minerals.size() - shownItems) + "종", a, b, c, DIM, true);
		}
		if (!others.isEmpty()) {
			long a = 0, b = 0, c = 0;
			for (Tracker.ItemTally t : others) {
				a += t.total();
				b += t.storage;
				c += t.inventory;
			}
			p.otherRow = new Row(others.get(0).icon, "기타 " + others.size() + "종", a, b, c, DIM, false);
		}
		return p;
	}

	/** Example numbers for the editor when nothing has been recorded yet. */
	static Panel samplePanel(Tracker tracker) {
		Panel p = new Panel();
		p.time = formatTime(tracker.activeTicks());
		p.running = tracker.isRunning();
		Block[] blocks = {Blocks.DEEPSLATE, Blocks.STONE, Blocks.TUFF, Blocks.DEEPSLATE_IRON_ORE, Blocks.DEEPSLATE_REDSTONE_ORE};
		long[] counts = {1046, 512, 88, 34, 21};
		for (int i = 0; i < blocks.length; i++) {
			p.blockRows.add(new Row(new ItemStack(blocks[i]), blocks[i].getName().getString(), counts[i], 0, 0, TEXT, false));
		}
		p.blockMore = new Row(ItemStack.EMPTY, "외 5종", 39, 0, 0, DIM, true);
		p.totalBlocks = 1740;
		ItemStack[] items = {new ItemStack(Items.REDSTONE), new ItemStack(Items.RAW_IRON), new ItemStack(Items.LAPIS_LAZULI),
				new ItemStack(Items.RAW_COPPER), new ItemStack(Items.COAL), new ItemStack(Items.DIAMOND)};
		long[][] split = {{96, 16}, {47, 0}, {38, 0}, {21, 5}, {13, 0}, {5, 0}};
		for (int i = 0; i < items.length; i++) {
			long st = split[i][0], inv = split[i][1];
			p.itemRows.add(new Row(items[i], items[i].getHoverName().getString(), st + inv, st, inv, TEXT, false));
		}
		p.otherRow = new Row(new ItemStack(Items.COBBLED_DEEPSLATE), "기타 4종", 1208, 0, 1208, DIM, false);
		return p;
	}

	static List<ToastManager.Toast> sampleToasts() {
		List<ToastManager.Toast> list = new ArrayList<>();
		ItemStack diamond = new ItemStack(Items.DIAMOND);
		ItemStack iron = new ItemStack(Items.RAW_IRON);
		ItemStack redstone = new ItemStack(Items.REDSTONE);
		list.add(new ToastManager.Toast("a", diamond, diamond.getHoverName().getString(), true, 2, -1000));
		list.add(new ToastManager.Toast("b", iron, iron.getHoverName().getString(), true, 8, -1000));
		list.add(new ToastManager.Toast("c", redstone, redstone.getHoverName().getString(), false, 5, -1000));
		return list;
	}

	// ------------------------------------------------------------------ board

	private static Layout layout(Font font, Panel p) {
		int nameW = font.width("광질 측정");
		int countW = font.width("총 " + fmt(p.totalBlocks));
		int allW = font.width("합계");
		int storeW = font.width("저장소");
		int invW = font.width("인벤");
		List<Row> blockLines = new ArrayList<>(p.blockRows);
		if (p.blockMore != null) {
			blockLines.add(p.blockMore);
		}
		for (Row r : blockLines) {
			nameW = Math.max(nameW, Math.min(NAME_MAX, font.width(r.name())));
			countW = Math.max(countW, font.width(fmt(r.a())));
		}
		List<Row> itemLines = itemLines(p);
		for (Row r : itemLines) {
			nameW = Math.max(nameW, Math.min(NAME_MAX, font.width(r.name())));
			allW = Math.max(allW, font.width(fmt(r.a())));
			storeW = Math.max(storeW, font.width(fmt(r.b())));
			invW = Math.max(invW, font.width(fmt(r.c())));
		}
		int status = 8 + font.width("일시정지") + 4 + font.width(p.time);
		int width = MIN_WIDTH;
		width = Math.max(width, 18 + nameW + 8 + countW + 6);
		width = Math.max(width, 18 + nameW + 8 + allW + 8 + storeW + 8 + invW + 6);
		width = Math.max(width, 18 + font.width("광질 측정") + 12 + status + 6);

		Layout l = new Layout();
		l.width = width;
		l.colInv = width - 6;
		l.colStore = l.colInv - invW - 8;
		l.colAll = l.colStore - storeW - 8;
		int blockCount = Math.max(1, blockLines.size());
		int itemCount = Math.max(1, itemLines.size());
		l.height = 19 + PITCH * (1 + blockCount) + 5 + PITCH * (1 + itemCount) + 5;
		return l;
	}

	private static List<Row> itemLines(Panel p) {
		List<Row> lines = new ArrayList<>(p.itemRows);
		if (p.itemMore != null) {
			lines.add(p.itemMore);
		}
		if (p.otherRow != null) {
			lines.add(p.otherRow);
		}
		return lines;
	}

	/** Draws the board and returns where it ended up on screen: {x, y, width, height} in GUI pixels. */
	static float[] drawPanel(GuiGraphics g, Font font, Panel p, float px, float py, float scale) {
		Layout l = layout(font, p);
		float x = clamp(px, 0, Math.max(0, g.guiWidth() - l.width * scale));
		float y = clamp(py, 0, Math.max(0, g.guiHeight() - l.height * scale));
		int w = l.width;

		PoseStack pose = g.pose();
		pose.pushPose();
		pose.translate(x, y, 0);
		pose.scale(scale, scale, 1f);

		frame(g, 0, 0, w, l.height);

		// header: icon, title, state and measured time
		icon(g, headerIcon(), 6, 4);
		g.drawString(font, "광질 측정", 18, 4, HEAD, true);
		int right = w - 6;
		g.drawString(font, p.time, right - font.width(p.time), 4, SUB, true);
		right -= font.width(p.time) + 4;
		String state = p.running ? "측정 중" : "일시정지";
		int stateColor = p.running ? GREEN : YELLOW;
		g.drawString(font, state, right - font.width(state), 4, stateColor, true);
		right -= font.width(state) + 4;
		g.fill(right - 4, 6, right, 10, stateColor);
		g.fill(3, 15, w - 3, 16, RULE);

		int rowY = 19;

		// mined blocks
		g.drawString(font, "캔 블록", 6, rowY + 2, LABEL, true);
		textRight(g, font, "총 " + fmt(p.totalBlocks), l.colInv, rowY + 2, TEXT);
		rowY += PITCH;
		if (p.blockRows.isEmpty()) {
			g.drawString(font, "아직 캔 블록이 없어요", 18, rowY + 2, DIM, true);
			rowY += PITCH;
		}
		for (Row r : p.blockRows) {
			drawNameCell(g, font, r, rowY);
			textRight(g, font, fmt(r.a()), l.colInv, rowY + 2, r.color());
			rowY += PITCH;
		}
		if (p.blockMore != null) {
			drawNameCell(g, font, p.blockMore, rowY);
			textRight(g, font, fmt(p.blockMore.a()), l.colInv, rowY + 2, DIM);
			rowY += PITCH;
		}

		g.fill(5, rowY + 1, w - 5, rowY + 2, SEP);
		rowY += 5;

		// picked-up items, split by where they went
		g.drawString(font, "먹은 광물", 6, rowY + 2, LABEL, true);
		textRight(g, font, "합계", l.colAll, rowY + 2, DIM);
		textRight(g, font, "저장소", l.colStore, rowY + 2, DIM);
		textRight(g, font, "인벤", l.colInv, rowY + 2, DIM);
		rowY += PITCH;
		List<Row> lines = itemLines(p);
		if (lines.isEmpty()) {
			g.drawString(font, "아직 먹은 광물이 없어요", 18, rowY + 2, DIM, true);
		}
		for (Row r : lines) {
			boolean main = r.color() == TEXT;
			drawNameCell(g, font, r, rowY);
			textRight(g, font, fmt(r.a()), l.colAll, rowY + 2, main ? TEXT : DIM);
			textRight(g, font, fmt(r.b()), l.colStore, rowY + 2, r.b() == 0 ? ZERO : (main ? SUB : DIM));
			textRight(g, font, fmt(r.c()), l.colInv, rowY + 2, r.c() == 0 ? ZERO : (main ? SUB : DIM));
			rowY += PITCH;
		}

		pose.popPose();
		return new float[]{x, y, l.width * scale, l.height * scale};
	}

	private static void drawNameCell(GuiGraphics g, Font font, Row r, int rowY) {
		if (r.dots()) {
			g.fill(6, rowY + 7, 7, rowY + 8, DIM);
			g.fill(9, rowY + 7, 10, rowY + 8, DIM);
			g.fill(12, rowY + 7, 13, rowY + 8, DIM);
		} else {
			icon(g, r.icon(), 6, rowY + 2);
		}
		g.drawString(font, ellipsize(font, r.name(), NAME_MAX), 18, rowY + 2, r.color(), true);
	}

	// ------------------------------------------------------------------ toasts

	static void drawToasts(GuiGraphics g, Font font, List<ToastManager.Toast> toasts, long tick, float partialTick, ModConfig cfg) {
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
				drawToast(g, font, t, right - w + (1f - eased) * (w + 10), y);
			}
			y += TOAST_HEIGHT + TOAST_GAP;
		}
		pose.popPose();
	}

	/** Toasts at rest (for the editor). Returns {x, y, width, height} in GUI pixels. */
	static float[] drawToastsStatic(GuiGraphics g, Font font, List<ToastManager.Toast> toasts, ModConfig cfg) {
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
			drawToast(g, font, t, right - toastWidth(font, t), y);
			y += TOAST_HEIGHT + TOAST_GAP;
		}
		pose.popPose();
		return new float[]{rightGui - maxW * scale, topGui, maxW * scale, height * scale};
	}

	private static int toastWidth(Font font, ToastManager.Toast t) {
		String name = ellipsize(font, t.name, NAME_MAX);
		return 16 + font.width(name) + 4 + font.width("+" + fmt(t.amount)) + 6 + font.width(t.storage ? "저장소" : "인벤") + 5;
	}

	private static void drawToast(GuiGraphics g, Font font, ToastManager.Toast t, float x, float y) {
		String name = ellipsize(font, t.name, NAME_MAX);
		String plus = "+" + fmt(t.amount);
		String tag = t.storage ? "저장소" : "인벤";
		int w = toastWidth(font, t);
		PoseStack pose = g.pose();
		pose.pushPose();
		pose.translate(x, y, 0);
		frame(g, 0, 0, w, TOAST_HEIGHT);
		icon(g, t.icon, 4, 3);
		int tx = 16;
		g.drawString(font, name, tx, 3, TEXT, true);
		tx += font.width(name) + 4;
		g.drawString(font, plus, tx, 3, PLUS, true);
		tx += font.width(plus) + 6;
		g.drawString(font, tag, tx, 3, t.storage ? SUB : TAG_INV, true);
		pose.popPose();
	}

	// ------------------------------------------------------------------ helpers

	/** Same frame as the server's own panels: dark edge, border, solid background. */
	static void frame(GuiGraphics g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, EDGE);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, BORDER);
		g.fill(x + 2, y + 2, x + w - 2, y + h - 2, BG);
	}

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

	private static ItemStack headerIcon() {
		if (headerIcon == null) {
			headerIcon = new ItemStack(Items.IRON_PICKAXE);
		}
		return headerIcon;
	}

	private static void textRight(GuiGraphics g, Font font, String text, int right, int y, int color) {
		g.drawString(font, text, right - font.width(text), y, color, true);
	}

	static String ellipsize(Font font, String text, int maxWidth) {
		if (font.width(text) <= maxWidth) {
			return text;
		}
		return font.plainSubstrByWidth(text, maxWidth - font.width("…")) + "…";
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
