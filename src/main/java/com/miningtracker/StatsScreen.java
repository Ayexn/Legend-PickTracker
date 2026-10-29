package com.miningtracker;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * 광질 통계 (F10 or /광질 통계): the saved recordings by date. Pick a day on the left (or step with ◀ ▶);
 * the right side shows that day: totals, minerals by kind with the change against the last day before it,
 * and each recording of the day.
 */
public final class StatsScreen extends PanelScreen {
	private static final int W = 424;
	private static final int LIST_X = 8;
	private static final int LIST_W = 121;
	/** Days the list is made tall enough for (it shows more when the right side is taller). */
	private static final int LIST_ROWS = 12;
	private static final int DAY_ROW = 14;
	/** Height of the list box without its rows: title, column names and the month's total. */
	private static final int LIST_CHROME = 14 + 12 + 18;
	private static final int RIGHT_X = 134;
	private static final int TOP = 21;
	private static final int MAX_KINDS = 8;
	private static final int MAX_SESSIONS = 6;
	private static final int UP = 0xFF6ED682;
	private static final int DOWN = 0xFFEC6868;
	private static final long CLEAR_CONFIRM_MS = 3000;
	private static final String[] WEEKDAYS = {"월", "화", "수", "목", "금", "토", "일"};
	private static final String DIAMOND = "minecraft:diamond";

	private LocalDate selected;
	private int scroll;
	private long clearArmedAt;

	public StatsScreen(Screen parent) {
		super(Component.literal("광질 통계"), parent);
		List<History.Day> days = History.days();
		selected = days.isEmpty() ? LocalDate.now() : days.get(0).date;
	}

	// ------------------------------------------------------------------ layout

	@Override
	protected int panelWidth() {
		return History.isEmpty() ? 236 : W;
	}

	/** The same height for every day, so the buttons stay put while stepping through the days. */
	@Override
	protected int panelHeight() {
		if (History.isEmpty()) {
			return 70;
		}
		int right = TOP + 41;
		for (History.Day d : History.days()) {
			right = Math.max(right, rightBottom(d));
		}
		int list = TOP + LIST_CHROME + DAY_ROW * Math.max(1, Math.min(LIST_ROWS, History.days().size()));
		return Math.max(list, right) + 6 + 22;
	}

	/** Where both columns end, above the buttons. */
	private static int contentBottom(int h) {
		return h - 28;
	}

	/** Days that fit in the list at once. */
	private int listRows() {
		return Math.max(1, (contentBottom(panelHeight()) - TOP - LIST_CHROME) / DAY_ROW);
	}

	private int maxScroll() {
		return Math.max(0, History.days().size() - listRows());
	}

	private static int kindsRows(History.Day d) {
		return Math.max(1, Math.min(MAX_KINDS, d.items.size()) + (d.other > 0 ? 1 : 0));
	}

	private static int kindsHeight(History.Day d) {
		return 14 + 5 + 14 * kindsRows(d) + 4;
	}

	private static int sessionRows(History.Day d) {
		return Math.min(MAX_SESSIONS, d.sessions.size()) + (d.sessions.size() > MAX_SESSIONS ? 1 : 0);
	}

	private static int rightBottom(History.Day d) {
		int kindsTop = TOP + 46;
		int sessionsTop = kindsTop + kindsHeight(d) + 5;
		return sessionsTop + 14 + 12 + 13 * sessionRows(d) + 2;
	}

	// ------------------------------------------------------------------ drawing

	@Override
	protected void drawPanel(GuiGraphics g, Theme t, int w, int h) {
		HudRenderer.icon(g, HudRenderer.headerIcon(), 8, 7);
		t.text(g, this.font, "광질 통계", 19, 7, t.text);
		if (History.isEmpty()) {
			t.text(g, this.font, "아직 저장된 광질 기록이 없어요", 9, 24, t.text);
			t.text(g, this.font, "광질이 끝나면 날짜별로 여기에 쌓여요", 9, 37, t.dim);
			int doneW = buttonWidth("닫기");
			button(g, t, w - 7 - doneW, h - 21, doneW, "닫기", true, this::onClose);
			return;
		}
		drawNavigator(g, t, w);
		int bottom = contentBottom(h);
		drawDays(g, t, bottom);
		History.Day day = History.day(selected);
		if (day == null) {
			int rw = w - 8 - RIGHT_X;
			Theme.rrect(g, RIGHT_X, TOP, rw, bottom - TOP, t.card, 1);
			String empty = "이날은 광질 기록이 없어요";
			String hint = "왼쪽에서 기록이 있는 날을 골라 보세요";
			int cy = (TOP + bottom) / 2;
			t.text(g, this.font, empty, RIGHT_X + (rw - this.font.width(empty)) / 2, cy - 10, t.sub);
			t.text(g, this.font, hint, RIGHT_X + (rw - this.font.width(hint)) / 2, cy + 3, t.dim);
		} else {
			drawCards(g, t, day, w);
			int y = drawKinds(g, t, day, w, TOP + 46);
			drawSessions(g, t, day, w, y + 5, bottom);
		}
		int y = h - 22;
		t.text(g, this.font, "기록은 내 컴퓨터에만 저장돼요", 9, y + 6, t.dim);
		int doneW = buttonWidth("닫기");
		boolean armed = System.currentTimeMillis() - clearArmedAt <= CLEAR_CONFIRM_MS;
		String clear = armed ? "한 번 더 누르면 지워요" : "기록 지우기";
		int clearW = buttonWidth(clear);
		button(g, t, w - 7 - doneW, y + 1, doneW, "닫기", true, this::onClose);
		button(g, t, w - 7 - doneW - 5 - clearW, y + 1, clearW, clear, false, this::onClear);
	}

	/** Title bar, right side: ◀ date ▶ and 오늘. */
	private void drawNavigator(GuiGraphics g, Theme t, int w) {
		int x = w - 8;
		int todayW = this.font.width("오늘") + 12;
		Theme.rrect(g, x - todayW, 4, todayW, SEG_H, hovered(x - todayW, 4, todayW, SEG_H) ? Theme.withAlpha(t.text, 40) : t.track, 1);
		t.text(g, this.font, "오늘", x - todayW + 6, 7, t.sub);
		hits.add(new Hit(x - todayW, 4, x, 4 + SEG_H, () -> select(LocalDate.now())));
		x -= todayW + 6;
		arrowButton(g, t, x - SEG_H, 4, false, () -> select(selected.plusDays(1)));
		x -= SEG_H + 6;
		String title = selected.getMonthValue() + "월 " + selected.getDayOfMonth() + "일 (" + weekday(selected) + ")";
		t.textRight(g, this.font, title, x, 7, t.head);
		x -= this.font.width(title) + 6;
		arrowButton(g, t, x - SEG_H, 4, true, () -> select(selected.minusDays(1)));
	}

	/** Left, down to {@code bottom}: the days with recordings, newest first, and the month's total. */
	private void drawDays(GuiGraphics g, Theme t, int bottom) {
		List<History.Day> days = History.days();
		int x0 = LIST_X;
		int x1 = LIST_X + LIST_W;
		int rows = Math.max(1, (bottom - TOP - LIST_CHROME) / DAY_ROW);
		Theme.rrect(g, x0, TOP, LIST_W, bottom - TOP, t.card, 1);
		t.text(g, this.font, "날짜별", x0 + 6, TOP + 5, t.sub);
		int timeR = x1 - 35;
		int countR = x1 - 7;
		int y = TOP + 18;
		t.text(g, this.font, "날짜", x0 + 6, y, t.dim);
		t.textRight(g, this.font, "시간", timeR, y, t.dim);
		t.textRight(g, this.font, "광물", countR, y, t.dim);
		y += 13;
		scroll = Math.max(0, Math.min(scroll, days.size() - rows));
		for (int i = scroll; i < Math.min(days.size(), scroll + rows); i++) {
			History.Day d = days.get(i);
			boolean on = d.date.equals(selected);
			if (on) {
				Theme.rrect(g, x0 + 2, y - 3, LIST_W - 4, 13, Theme.withAlpha(t.accent, 46), 1);
				g.fill(x0 + 2, y - 2, x0 + 3, y + 9, t.accent);
			} else if (hovered(x0 + 2, y - 3, LIST_W - 4, 13)) {
				Theme.rrect(g, x0 + 2, y - 3, LIST_W - 4, 13, Theme.withAlpha(t.text, 14), 1);
			}
			int color = on ? t.head : t.text;
			t.text(g, this.font, d.date.getMonthValue() + "/" + d.date.getDayOfMonth() + " " + weekday(d.date), x0 + 6, y, color);
			t.textRight(g, this.font, shortTime(d.seconds), timeR, y, t.sub);
			t.textRight(g, this.font, HudRenderer.fmt(d.minerals), countR, y, color);
			LocalDate date = d.date;
			hits.add(new Hit(x0 + 2, y - 3, x1 - 2, y + 10, () -> select(date)));
			y += DAY_ROW;
		}
		if (scroll > 0) {
			scrollMark(g, t, x1 - 10, TOP + 6, true);
		}
		if (scroll + rows < days.size()) {
			scrollMark(g, t, x1 - 10, y - 5, false);
		}
		// the month of the selected day
		long seconds = 0;
		long minerals = 0;
		for (History.Day d : days) {
			if (d.date.getYear() == selected.getYear() && d.date.getMonthValue() == selected.getMonthValue()) {
				seconds += d.seconds;
				minerals += d.minerals;
			}
		}
		y = bottom - 18;
		g.fill(x0 + 6, y + 1, x1 - 6, y + 2, t.sep);
		t.text(g, this.font, selected.getMonthValue() + "월", x0 + 6, y + 6, t.dim);
		t.textRight(g, this.font, shortTime(seconds), timeR, y + 6, t.dim);
		t.textRight(g, this.font, HudRenderer.fmt(minerals), countR, y + 6, t.dim);
	}

	private static void scrollMark(GuiGraphics g, Theme t, int x, int y, boolean up) {
		for (int i = 0; i < 3; i++) {
			int row = up ? y + i : y + 2 - i;
			g.fill(x + 2 - i, row, x + 3 + i, row + 1, t.dim);
		}
	}

	/** The day's totals in four boxes. */
	private void drawCards(GuiGraphics g, Theme t, History.Day d, int w) {
		long diamonds = d.count(DIAMOND);
		String[][] cards = {
				{"광질 시간", duration(d.seconds), d.sessions.size() + "번 광질"},
				{"캔 블록", HudRenderer.fmt(d.blocks), "분당 " + HudRenderer.fmt(HudRenderer.perMinute(d.blocks, d.seconds))},
				{"먹은 광물", HudRenderer.fmt(d.minerals), "시간당 " + HudRenderer.fmt(HudRenderer.perHour(d.minerals, d.seconds))},
				{"다이아", HudRenderer.fmt(diamonds), "시간당 " + HudRenderer.fmt(HudRenderer.perHour(diamonds, d.seconds))},
		};
		int avail = (w - 8 - RIGHT_X) - 3 * 4;
		int[] widths = new int[cards.length];
		int need = 0;
		for (int i = 0; i < cards.length; i++) {
			widths[i] = Math.max(Math.max(this.font.width(cards[i][0]), this.font.width(cards[i][2])),
					Theme.bigWidth(this.font, cards[i][1], HudRenderer.MID)) + 12;
			need += widths[i];
		}
		int extra = Math.max(0, avail - need);
		int x = RIGHT_X;
		for (int i = 0; i < cards.length; i++) {
			int cw = widths[i] + (i == cards.length - 1 ? extra - extra / cards.length * (cards.length - 1) : extra / cards.length);
			Theme.rrect(g, x, TOP, cw, 41, t.card, 1);
			t.text(g, this.font, cards[i][0], x + 6, TOP + 5, t.dim);
			t.big(g, this.font, cards[i][1], x + 6, TOP + 15, HudRenderer.MID, i == 2 ? t.accent : t.head);
			t.text(g, this.font, cards[i][2], x + 6, TOP + 30, t.dim);
			x += cw + 4;
		}
	}

	/** Minerals of the day by kind: count, per hour, and the change in per hour against the last day before. */
	private int drawKinds(GuiGraphics g, Theme t, History.Day d, int w, int top) {
		int x0 = RIGHT_X;
		int right = w - 8;
		int height = kindsHeight(d);
		Theme.rrect(g, x0, top, right - x0, height, t.card, 1);
		int colDiff = right - 7;
		int colRate = right - 51;
		int colCount = right - 93;
		t.text(g, this.font, "광물별", x0 + 6, top + 5, t.sub);
		t.textRight(g, this.font, "개수", colCount, top + 5, t.dim);
		t.textRight(g, this.font, "시간당", colRate, top + 5, t.dim);
		t.textRight(g, this.font, "지난번보다", colDiff, top + 5, t.dim);
		History.Day before = History.before(d.date);
		int y = top + 19;
		long most = d.items.isEmpty() ? 0 : d.items.get(0).count;
		if (d.items.isEmpty() && d.other <= 0) {
			t.text(g, this.font, "먹은 광물이 없어요", x0 + 19, y, t.dim);
		}
		int countW = 0;
		for (int i = 0; i < Math.min(MAX_KINDS, d.items.size()); i++) {
			countW = Math.max(countW, this.font.width(HudRenderer.fmt(d.items.get(i).count)));
		}
		for (int i = 0; i < Math.min(MAX_KINDS, d.items.size()); i++) {
			History.Item it = d.items.get(i);
			ItemStack icon = History.icon(it.id);
			int color = icon.isEmpty() ? Palette.fromName(it.name) : Palette.of(icon);
			int bx0 = x0 + 4;
			int bx1 = colRate - 2;
			int fill = bx0 + Math.max(4, (int) Math.round((bx1 - bx0) * (double) it.count / Math.max(1, most)));
			Theme.rrect(g, bx0, y - 3, fill - bx0, 13, Theme.withAlpha(color, t.barAlpha), 1);
			g.fill(bx0, y - 2, bx0 + 1, y + 9, color);
			HudRenderer.icon(g, icon, x0 + 8, y);
			t.text(g, this.font, HudRenderer.ellipsize(this.font, it.name, colCount - countW - 8 - (x0 + 19)), x0 + 19, y, t.text);
			t.textRight(g, this.font, HudRenderer.fmt(it.count), colCount, y, t.text);
			long rate = HudRenderer.perHour(it.count, d.seconds);
			t.textRight(g, this.font, HudRenderer.fmt(rate), colRate, y, t.sub);
			drawChange(g, t, rate, before == null ? -1 : rateOf(before, it), colDiff, y);
			y += 14;
		}
		if (d.other > 0) {
			HudRenderer.icon(g, new ItemStack(Items.COBBLED_DEEPSLATE), x0 + 8, y);
			t.text(g, this.font, "기타", x0 + 19, y, t.dim);
			t.textRight(g, this.font, HudRenderer.fmt(d.other), colCount, y, t.dim);
			t.textRight(g, this.font, HudRenderer.fmt(HudRenderer.perHour(d.other, d.seconds)), colRate, y, t.dim);
		}
		return top + height;
	}

	private static long rateOf(History.Day day, History.Item item) {
		for (History.Item it : day.items) {
			if (it.key().equals(item.key())) {
				return HudRenderer.perHour(it.count, day.seconds);
			}
		}
		return -1;
	}

	/** "▲ 12%" in green, "▼ 8%" in red, "-" when there is nothing to compare with. */
	private void drawChange(GuiGraphics g, Theme t, long rate, long before, int right, int y) {
		if (before <= 0) {
			t.textRight(g, this.font, "-", right, y, t.dim);
			return;
		}
		long change = Math.round((rate - before) * 100.0 / before);
		String text = Math.abs(change) + "%";
		int color = change > 0 ? UP : change < 0 ? DOWN : t.dim;
		t.textRight(g, this.font, text, right, y, color);
		if (change != 0) {
			int x = right - this.font.width(text) - 7;
			for (int i = 0; i < 3; i++) {
				int row = change > 0 ? y + 2 + i : y + 4 - i;
				g.fill(x + 2 - i, row, x + 3 + i, row + 1, color);
			}
		}
	}

	/** Each recording of the day, in a box down to {@code bottom}. */
	private void drawSessions(GuiGraphics g, Theme t, History.Day d, int w, int top, int bottom) {
		int x0 = RIGHT_X;
		int right = w - 8;
		int height = Math.max(14 + 12 + 13 * sessionRows(d) + 2, bottom - top);
		Theme.rrect(g, x0, top, right - x0, height, t.card, 1);
		t.text(g, this.font, "이날 광질", x0 + 6, top + 5, t.sub);
		int start = x0 + 6;
		int time = x0 + 46;
		int blocks = right - 119;
		int minerals = right - 77;
		int rate = right - 31;
		int dia = right - 7;
		int y = top + 18;
		t.text(g, this.font, "시작", start, y, t.dim);
		t.text(g, this.font, "걸린 시간", time, y, t.dim);
		t.textRight(g, this.font, "블록", blocks, y, t.dim);
		t.textRight(g, this.font, "광물", minerals, y, t.dim);
		t.textRight(g, this.font, "시간당", rate, y, t.dim);
		HudRenderer.icon(g, new ItemStack(Items.DIAMOND), dia - 8, y);
		y += 13;
		List<History.Session> list = d.sessions;
		int from = Math.max(0, list.size() - MAX_SESSIONS);
		for (int i = from; i < list.size(); i++) {
			History.Session s = list.get(i);
			LocalDateTime when = LocalDateTime.ofInstant(Instant.ofEpochMilli(s.start), ZoneId.systemDefault());
			t.text(g, this.font, String.format(Locale.ROOT, "%02d:%02d", when.getHour(), when.getMinute()), start, y, t.text);
			t.text(g, this.font, duration(s.seconds), time, y, t.sub);
			t.textRight(g, this.font, HudRenderer.fmt(s.blocks), blocks, y, t.text);
			t.textRight(g, this.font, HudRenderer.fmt(s.minerals()), minerals, y, t.text);
			t.textRight(g, this.font, HudRenderer.fmt(HudRenderer.perHour(s.minerals(), s.seconds)), rate, y, t.sub);
			t.textRight(g, this.font, HudRenderer.fmt(s.count(DIAMOND)), dia, y, t.text);
			y += 13;
		}
		if (from > 0) {
			t.text(g, this.font, "그 전에 " + from + "번 더", start, y, t.dim);
		}
	}

	// ------------------------------------------------------------------ helpers

	private void select(LocalDate date) {
		LocalDate today = LocalDate.now();
		selected = date.isAfter(today) ? today : date;
		List<History.Day> days = History.days();
		int rows = listRows();
		for (int i = 0; i < days.size(); i++) {
			if (days.get(i).date.equals(selected)) {
				if (i < scroll) {
					scroll = i;
				} else if (i >= scroll + rows) {
					scroll = i - rows + 1;
				}
				break;
			}
		}
	}

	private void onClear() {
		long now = System.currentTimeMillis();
		if (now - clearArmedAt <= CLEAR_CONFIRM_MS) {
			History.clear();
			clearArmedAt = 0;
			selected = LocalDate.now();
			scroll = 0;
		} else {
			clearArmedAt = now;
		}
	}

	private static String weekday(LocalDate date) {
		return WEEKDAYS[date.getDayOfWeek().getValue() - 1];
	}

	/** "12분", "1시간 20분". */
	static String duration(long seconds) {
		long m = seconds / 60;
		return m >= 60 ? (m / 60) + "시간 " + (m % 60) + "분" : m + "분";
	}

	/** "45분", "1:20" (for the narrow day list). */
	private static String shortTime(long seconds) {
		long m = seconds / 60;
		return m >= 60 ? String.format(Locale.ROOT, "%d:%02d", m / 60, m % 60) : m + "분";
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY != 0 && !History.isEmpty()) {
			scroll += scrollY > 0 ? -1 : 1;
			scroll = Math.max(0, Math.min(scroll, maxScroll()));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (Keys.stats != null && Keys.stats.matches(keyCode, scanCode)) {
			onClose();
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_LEFT) {
			select(selected.minusDays(1));
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_RIGHT) {
			select(selected.plusDays(1));
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}
}
