package com.miningtracker;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 광질 통계 (F10 or /광질 통계), laid out like the 세공 도우미's statistics: 오늘 · 7일 · 30일 · 전체 on top, the
 * totals in four boxes, a bar for each day (each month in 전체) and the minerals by kind. A bar opens that day (in
 * 전체, that month's days); a day shows its minerals and each of its recordings.
 */
public final class StatsScreen extends PanelScreen {
	private static final int W = 424;
	private static final int X0 = 8;
	private static final int TOP = 21;
	private static final int CARD_H = 32;
	private static final int GAP = 5;
	private static final int CHART_H = 100;
	private static final int ROW = 14;
	private static final int SESSION_ROW = 13;
	/** Room under the boxes for the buttons. */
	private static final int FOOTER = 28;
	private static final int MAX_KINDS = 8;
	private static final int MAX_SESSIONS = 6;
	private static final int MAX_MONTHS = 12;
	/** 전체 makes room for this many months, so one or two months do not become huge bars. */
	private static final int MIN_MONTHS = 6;
	private static final int WARN = 0xFFEC6868;
	private static final long CONFIRM_MS = 3000;
	private static final long NOTICE_MS = 4000;
	/** How long 복사 shows "복사했어요". */
	private static final long COPIED_MS = 2500;
	/** Room under the boxes in the picture that 복사 makes. */
	private static final int IMAGE_PAD = 7;
	private static final String[] TABS = {"오늘", "7일", "30일", "전체"};
	private static final int TODAY = 0;
	private static final int WEEK = 1;
	private static final int DAYS30 = 2;
	private static final int ALL = 3;
	private static final String[] WEEKDAYS = {"월", "화", "수", "목", "금", "토", "일"};

	/** A bar of the chart. {@code click} is null for an empty place (a day still to come). */
	private record Bar(String label, long value, boolean now, String tip, Runnable click) {
	}

	/** The recordings from {@code from} to {@code to} (both included), added up. */
	private static final class Total {
		final LocalDate from;
		final LocalDate to;
		int sessions;
		long seconds;
		long blocks;
		long other;
		long minerals;
		/** Minerals by kind, most first. */
		final List<History.Item> items = new ArrayList<>();

		Total(LocalDate from, LocalDate to) {
			this.from = from;
			this.to = to;
			Map<String, History.Item> byKey = new LinkedHashMap<>();
			for (History.Day d : History.days()) {
				if (d.date.isBefore(from) || d.date.isAfter(to)) {
					continue;
				}
				sessions += d.sessions.size();
				seconds += d.seconds;
				blocks += d.blocks;
				other += d.other;
				minerals += d.minerals;
				for (History.Item it : d.items) {
					History.Item sum = byKey.computeIfAbsent(it.key(), k -> {
						History.Item copy = new History.Item();
						copy.id = it.id;
						copy.name = it.name;
						return copy;
					});
					sum.count += it.count;
				}
			}
			items.addAll(byKey.values());
			items.sort((a, b) -> Long.compare(b.count, a.count));
		}
	}

	private int tab = WEEK;
	/** A month opened from 전체, or null. */
	private YearMonth month;
	/** The day being shown, or null for all the days of the tab. */
	private LocalDate day;
	private long undoArmedAt;
	private long clearArmedAt;
	/** A line for the bottom left after a button did something, shown for a while. */
	private String notice;
	private long noticeAt;
	/** True while drawing the picture that 복사 makes: no buttons, no arrows, no mouse. */
	private boolean forImage;
	/** 복사 was pressed: the picture is made once this frame is drawn (see render). */
	private boolean copyWanted;
	/** A picture is on its way to the clipboard. */
	private boolean copying;
	private long copiedAt;
	/** The days {@link #height} was worked out for. */
	private List<History.Day> heightFor;
	private int height;

	public StatsScreen(Screen parent) {
		super(Component.literal("광질 통계"), parent);
	}

	// ------------------------------------------------------------------ layout

	@Override
	protected int panelWidth() {
		return History.isEmpty() ? 236 : W;
	}

	/** One height for every tab and every day, so the buttons stay put. */
	@Override
	protected int panelHeight() {
		if (History.isEmpty()) {
			return 70;
		}
		List<History.Day> days = History.days();
		if (heightFor != days) {
			heightFor = days;
			Total all = new Total(LocalDate.MIN, LocalDate.MAX);
			int h = TOP + CARD_H + GAP + CHART_H + GAP + kindsHeight(kindsRows(all.items.size(), all.other));
			for (History.Day d : days) {
				h = Math.max(h, TOP + CARD_H + GAP + kindsHeight(kindsRows(d.items.size(), d.other)) + GAP + sessionsHeight(d));
			}
			height = h + FOOTER;
		}
		return height;
	}

	private static int kindsRows(int kinds, long other) {
		return Math.max(1, Math.min(MAX_KINDS, kinds) + (other > 0 ? 1 : 0));
	}

	private static int kindsHeight(int rows) {
		return 14 + 5 + ROW * rows + 4;
	}

	private static int sessionsHeight(History.Day d) {
		int rows = Math.min(MAX_SESSIONS, d.sessions.size()) + (d.sessions.size() > MAX_SESSIONS ? 1 : 0);
		return 14 + 12 + SESSION_ROW * rows + 2;
	}

	/** First and last day that the boxes, the chart and the table add up. */
	private LocalDate[] range() {
		LocalDate today = LocalDate.now();
		if (month != null) {
			LocalDate end = month.atEndOfMonth();
			return new LocalDate[]{month.atDay(1), end.isAfter(today) ? today : end};
		}
		LocalDate from = switch (tab) {
			case DAYS30 -> today.minusDays(29);
			case ALL -> firstDay();
			default -> today.minusDays(6);
		};
		return new LocalDate[]{from.isAfter(today) ? today : from, today};
	}

	private static LocalDate firstDay() {
		List<History.Day> days = History.days();
		return days.isEmpty() ? LocalDate.now() : days.get(days.size() - 1).date;
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
		drawTabs(g, t, w);
		if (!forImage) {
			drawCopyButton(g, t, w);
		}
		int bottom = h - FOOTER;
		if (day != null) {
			drawNavigator(g, t);
			drawDay(g, t, w, bottom);
		} else {
			drawRange(g, t, w, bottom);
		}
		if (!forImage) {
			drawFooter(g, t, w, h);
		}
	}

	/** Where the boxes of what is shown end when they take only the room they need (in the picture). */
	private int contentBottom() {
		int top = TOP + CARD_H + GAP;
		if (day == null) {
			LocalDate[] range = range();
			Total total = new Total(range[0], range[1]);
			return top + CHART_H + GAP + kindsHeight(kindsRows(total.items.size(), total.other));
		}
		History.Day d = History.day(day);
		if (d == null) {
			return TOP + 70;
		}
		return top + kindsHeight(kindsRows(d.items.size(), d.other)) + GAP + sessionsHeight(d);
	}

	/** Height of the picture that 복사 makes: just under its boxes, without the buttons. */
	int imageHeight() {
		return contentBottom() + IMAGE_PAD;
	}

	/**
	 * Draws the picture that 복사 makes, in panel pixels from (0, 0): the panel on a solid background, as on
	 * screen but without the buttons, the arrows and anything the mouse lights up. The same on every screen size.
	 */
	void drawForImage(GuiGraphics g) {
		Theme t = ModConfig.get().currentTheme();
		int w = panelWidth();
		int bottom = contentBottom();
		double mouseX = mx;
		double mouseY = my;
		List<Hit> screenHits = new ArrayList<>(hits);
		mx = -10000;
		my = -10000;
		forImage = true;
		try {
			g.fill(0, 0, w, bottom + IMAGE_PAD, Theme.withAlpha(t.screenBg, 255));
			t.panel(g, 0, 0, w, bottom + IMAGE_PAD, true);
			// the boxes end at h - FOOTER
			drawPanel(g, t, w, bottom + FOOTER);
		} finally {
			forImage = false;
			mx = mouseX;
			my = mouseY;
			hits.clear();
			hits.addAll(screenHits);
		}
	}

	/** 복사, left of the tabs; "복사했어요" for a moment after. */
	private void drawCopyButton(GuiGraphics g, Theme t, int w) {
		boolean copied = System.currentTimeMillis() - copiedAt <= COPIED_MS;
		String label = copied ? "복사했어요" : "복사";
		int bw = this.font.width(label) + 12;
		int x = w - 8 - choicesWidth(TABS) - 6 - bw;
		boolean hover = hovered(x, 4, bw, SEG_H);
		int fill = copied ? Theme.withAlpha(t.accent, 56) : hover ? Theme.withAlpha(t.text, 40) : t.track;
		Theme.rrect(g, x, 4, bw, SEG_H, fill, 1);
		t.text(g, this.font, label, x + 6, 7, copied ? t.accent : t.sub);
		hits.add(new Hit(x, 4, x + bw, 4 + SEG_H, this::copyImage));
	}

	private void copyImage() {
		if (!copying && !History.isEmpty()) {
			copyWanted = true;
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		if (copyWanted) {
			copyWanted = false;
			copying = true;
			// what this frame drew so far goes to the screen first; the picture is drawn into a buffer of its own
			g.flush();
			StatsImage.copy(this, this::copied);
		}
	}

	/** The picture reached the clipboard, or was saved as a file, or neither. */
	private void copied(StatsImage.Outcome outcome) {
		copying = false;
		long now = System.currentTimeMillis();
		noticeAt = now;
		if (outcome.copied()) {
			copiedAt = now;
			notice = "복사했어요 · 디스코드에서 Ctrl+V";
		} else if (outcome.file() != null) {
			notice = "클립보드 대신 screenshots 폴더에 저장했어요";
			// like the game's own screenshot message: the file name opens it
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) {
				String path = outcome.file().getAbsolutePath();
				Component link = Component.literal(outcome.file().getName()).withStyle(ChatFormatting.UNDERLINE)
						.withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, path)));
				mc.player.displayClientMessage(Component.literal("[광질 측정] 통계 그림을 저장했어요: ").append(link), false);
			}
		} else {
			notice = "복사하지 못했어요";
		}
	}

	/** 오늘 · 7일 · 30일 · 전체 at the top right. The chosen one also takes a day or a month back to its start. */
	private void drawTabs(GuiGraphics g, Theme t, int w) {
		// on 오늘, stepped to another day: no tab is that day
		boolean away = tab == TODAY && day != null && !day.equals(LocalDate.now());
		int right = w - 8;
		choices(g, t, TABS, away ? -1 : tab, true, right, 4, this::show);
		if (!away) {
			// after the other tabs' areas, so it only catches the chosen one
			hits.add(new Hit(right - choicesWidth(TABS), 4, right, 4 + SEG_H, () -> show(tab)));
		}
	}

	private void show(int index) {
		tab = index;
		month = null;
		day = index == TODAY ? LocalDate.now() : null;
	}

	/** After the title: ◀ the day ▶. */
	private void drawNavigator(GuiGraphics g, Theme t) {
		int x = 19 + this.font.width("광질 통계") + 8;
		g.fill(x, 6, x + 1, 16, t.sep);
		x += 8;
		String title = day.getMonthValue() + "월 " + day.getDayOfMonth() + "일 (" + weekday(day) + ")";
		if (forImage) {
			t.text(g, this.font, title, x, 7, t.head);
			return;
		}
		arrowButton(g, t, x, 4, true, () -> step(-1));
		x += SEG_H + 6;
		t.text(g, this.font, title, x, 7, t.head);
		x += this.font.width(title) + 6;
		arrowButton(g, t, x, 4, false, () -> step(1));
	}

	private void step(int days) {
		LocalDate next = day.plusDays(days);
		LocalDate today = LocalDate.now();
		day = next.isAfter(today) ? today : next;
	}

	/** The days of the tab (or the month): totals, a bar for each day, minerals by kind. */
	private void drawRange(GuiGraphics g, Theme t, int w, int bottom) {
		LocalDate[] range = range();
		Total total = new Total(range[0], range[1]);
		drawCards(g, t, w, total.seconds, total.blocks, total.minerals, total.sessions);
		int top = TOP + CARD_H + GAP;
		drawChart(g, t, w, top, total);
		drawKinds(g, t, w, top + CHART_H + GAP, bottom, total.items, total.other, total.minerals);
	}

	/** One day: totals, minerals by kind and each recording. */
	private void drawDay(GuiGraphics g, Theme t, int w, int bottom) {
		History.Day d = History.day(day);
		if (d == null) {
			int cw = w - 8 - X0;
			Theme.rrect(g, X0, TOP, cw, bottom - TOP, t.card, 1);
			String empty = day.equals(LocalDate.now()) ? "오늘은 아직 광질 기록이 없어요" : "이날은 광질 기록이 없어요";
			String hint = "좌우 방향키로 다른 날도 볼 수 있어요";
			int cy = (TOP + bottom) / 2;
			t.text(g, this.font, empty, X0 + (cw - this.font.width(empty)) / 2, cy - 10, t.sub);
			t.text(g, this.font, hint, X0 + (cw - this.font.width(hint)) / 2, cy + 3, t.dim);
			return;
		}
		drawCards(g, t, w, d.seconds, d.blocks, d.minerals, d.sessions.size());
		int top = TOP + CARD_H + GAP;
		int kindsBottom = top + kindsHeight(kindsRows(d.items.size(), d.other));
		drawKinds(g, t, w, top, kindsBottom, d.items, d.other, d.minerals);
		drawSessions(g, t, d, w, kindsBottom + GAP, bottom);
	}

	/** 광질 시간 · 캔 블록 · 먹은 광물 · 광질 횟수 in four boxes across; 먹은 광물 in the accent colour. */
	private void drawCards(GuiGraphics g, Theme t, int w, long seconds, long blocks, long minerals, int sessions) {
		String[][] cards = {
				{"광질 시간", duration(seconds)},
				{"캔 블록", HudRenderer.fmt(blocks)},
				{"먹은 광물", HudRenderer.fmt(minerals)},
				{"광질 횟수", sessions + "번"},
		};
		int avail = (w - 8 - X0) - (cards.length - 1) * 4;
		int[] widths = new int[cards.length];
		int need = 0;
		for (int i = 0; i < cards.length; i++) {
			widths[i] = Math.max(this.font.width(cards[i][0]), Theme.bigWidth(this.font, cards[i][1], HudRenderer.MID)) + 12;
			need += widths[i];
		}
		int extra = Math.max(0, avail - need);
		int x = X0;
		for (int i = 0; i < cards.length; i++) {
			int cw = widths[i] + (i == cards.length - 1 ? extra - extra / cards.length * (cards.length - 1) : extra / cards.length);
			Theme.rrect(g, x, TOP, cw, CARD_H, t.card, 1);
			t.text(g, this.font, cards[i][0], x + 6, TOP + 5, t.dim);
			t.big(g, this.font, cards[i][1], x + 6, TOP + 16, HudRenderer.MID, i == 2 ? t.accent : t.head);
			x += cw + 4;
		}
	}

	/** Minerals of each day of the range as bars (of each month in 전체); a bar opens its day (month). */
	private void drawChart(GuiGraphics g, Theme t, int w, int top, Total total) {
		LocalDate today = LocalDate.now();
		List<Bar> bars = new ArrayList<>();
		String title;
		boolean values;
		if (tab == ALL && month == null) {
			title = "월별 먹은 광물";
			values = true;
			Map<YearMonth, Long> byMonth = new HashMap<>();
			for (History.Day d : History.days()) {
				byMonth.merge(YearMonth.from(d.date), d.minerals, Long::sum);
			}
			YearMonth last = YearMonth.from(today);
			YearMonth first = YearMonth.from(total.from);
			if (first.isBefore(last.minusMonths(MAX_MONTHS - 1))) {
				first = last.minusMonths(MAX_MONTHS - 1);
			}
			// empty room on the left, so the months stand where they would with more of them
			for (long i = ChronoUnit.MONTHS.between(first, last) + 1; i < MIN_MONTHS; i++) {
				bars.add(new Bar(null, 0, false, "", null));
			}
			for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
				YearMonth open = m;
				long v = byMonth.getOrDefault(m, 0L);
				bars.add(new Bar(m.getMonthValue() + "월", v, m.equals(last),
						m.getYear() + "년 " + m.getMonthValue() + "월 · " + HudRenderer.fmt(v) + "개", () -> month = open));
			}
		} else {
			title = month != null ? month.getMonthValue() + "월 날짜별 먹은 광물" : "날짜별 먹은 광물";
			// a month shows all its days, the ones to come empty
			LocalDate end = month != null ? month.atEndOfMonth() : total.to;
			int n = (int) ChronoUnit.DAYS.between(total.from, end) + 1;
			values = n <= 7;
			Map<LocalDate, Long> byDay = new HashMap<>();
			for (History.Day d : History.days()) {
				byDay.put(d.date, d.minerals);
			}
			for (int i = 0; i < n; i++) {
				LocalDate d = total.from.plusDays(i);
				int dom = d.getDayOfMonth();
				String label;
				if (values) {
					label = dom + "(" + weekday(d) + ")";
				} else if (month != null) {
					label = dom == 1 || dom % 5 == 0 ? dom + "일" : null;
				} else {
					// every 7th day back from today
					label = (n - 1 - i) % 7 == 0 ? d.getMonthValue() + "/" + dom : null;
				}
				long v = byDay.getOrDefault(d, 0L);
				String tip = d.getMonthValue() + "월 " + dom + "일 (" + weekday(d) + ") · " + HudRenderer.fmt(v) + "개";
				bars.add(new Bar(label, v, d.equals(today), tip, d.isAfter(today) ? null : () -> day = d));
			}
		}

		int x0 = X0;
		int x1 = w - 8;
		Theme.rrect(g, x0, top, x1 - x0, CHART_H, t.card, 1);
		int tx = x0 + 6;
		if (month != null && !forImage) {
			arrowButton(g, t, x0 + 4, top + 3, true, () -> month = null);
			tx = x0 + 4 + SEG_H + 5;
		}
		t.text(g, this.font, title, tx, top + 5, t.sub);
		t.textRight(g, this.font, span(total.from, total.to), x1 - 7, top + 5, t.dim);

		int base = top + CHART_H - 16;
		int tallest = base - (top + 31);
		long max = 1;
		for (Bar b : bars) {
			max = Math.max(max, b.value());
		}
		int n = bars.size();
		double slot = (x1 - x0 - 12) / (double) n;
		int barW = Math.max(3, Math.min(30, (int) (slot * (n > 12 ? 0.7 : 0.5))));
		Bar tipBar = null;
		int tipX = 0;
		int tipY = 0;
		for (int i = 0; i < n; i++) {
			Bar b = bars.get(i);
			int sx0 = x0 + 6 + (int) Math.round(slot * i);
			int sx1 = x0 + 6 + (int) Math.round(slot * (i + 1));
			int cx = (sx0 + sx1) / 2;
			int bx = cx - barW / 2;
			boolean open = b.click() != null;
			boolean hover = open && hovered(sx0, top + 17, sx1 - sx0, CHART_H - 17);
			int barH = b.value() <= 0 ? 0 : Math.max(2, (int) Math.round(tallest * (double) b.value() / max));
			if (open) {
				int color = b.now() ? t.accent : Theme.withAlpha(t.accent, t.shadow ? 120 : 140);
				if (hover) {
					color = t.shadow ? Theme.mix(t.accent, 0xFFFFFFFF, 0.3f) : Theme.mix(t.accent, 0xFF000000, 0.2f);
				}
				if (barH > 0) {
					Theme.rrect(g, bx, base - barH, barW, barH, color, 1);
				} else {
					// nothing that day: a thin line where the bar would stand
					g.fill(bx, base - 1, bx + barW, base, Theme.withAlpha(t.text, 40));
				}
				hits.add(new Hit(sx0, top + 17, sx1, top + CHART_H, b.click()));
				if (values) {
					String v = compact(b.value());
					int vc = hover || b.now() ? t.head : b.value() > 0 ? t.sub : t.dim;
					t.text(g, this.font, v, cx - this.font.width(v) / 2, base - Math.max(1, barH) - 10, vc);
				}
			}
			if (b.label() != null) {
				int lw = this.font.width(b.label());
				int lx = Math.max(x0 + 4, Math.min(x1 - 4 - lw, cx - lw / 2));
				t.text(g, this.font, b.label(), lx, base + 4, b.now() ? t.accent : hover ? t.head : t.dim);
			}
			if (hover && !values) {
				tipBar = b;
				tipX = cx;
				tipY = base - Math.max(1, barH);
			}
		}
		if (tipBar != null) {
			// the day and its number over the bar (no room for numbers under 30 thin bars)
			String tip = tipBar.tip();
			int tw = this.font.width(tip) + 8;
			int bx = Math.max(x0 + 2, Math.min(x1 - 2 - tw, tipX - tw / 2));
			int by = Math.max(top + 16, tipY - 15);
			Theme.rrect(g, bx, by, tw, 12, t.head, 1);
			g.drawString(this.font, tip, bx + 4, by + 2, Theme.readableOn(t.head), false);
		}
	}

	/** Minerals by kind, each on a bar as long as its count: the count and its share of all minerals. */
	private void drawKinds(GuiGraphics g, Theme t, int w, int top, int bottom, List<History.Item> items, long other,
			long minerals) {
		int x0 = X0;
		int right = w - 8;
		Theme.rrect(g, x0, top, right - x0, bottom - top, t.card, 1);
		int colShare = right - 7;
		int colCount = colShare - 52;
		t.text(g, this.font, "광물별", x0 + 6, top + 5, t.sub);
		t.textRight(g, this.font, "개수", colCount, top + 5, t.dim);
		t.textRight(g, this.font, "비율", colShare, top + 5, t.dim);
		int y = top + 19;
		if (items.isEmpty() && other <= 0) {
			t.text(g, this.font, "먹은 광물이 없어요", x0 + 19, y, t.dim);
		}
		int shown = Math.min(MAX_KINDS, items.size());
		long most = items.isEmpty() ? 0 : items.get(0).count;
		int countW = 0;
		for (int i = 0; i < shown; i++) {
			countW = Math.max(countW, this.font.width(HudRenderer.fmt(items.get(i).count)));
		}
		for (int i = 0; i < shown; i++) {
			History.Item it = items.get(i);
			ItemStack icon = History.icon(it.id);
			int color = icon.isEmpty() ? Palette.fromName(it.name) : Palette.of(icon);
			int bx0 = x0 + 4;
			int bx1 = colCount + 6;
			int fill = bx0 + Math.max(4, (int) Math.round((bx1 - bx0) * (double) it.count / Math.max(1, most)));
			Theme.rrect(g, bx0, y - 3, fill - bx0, 13, Theme.withAlpha(color, t.barAlpha), 1);
			g.fill(bx0, y - 2, bx0 + 1, y + 9, color);
			HudRenderer.icon(g, icon, x0 + 8, y);
			t.text(g, this.font, HudRenderer.ellipsize(this.font, it.name, colCount - countW - 8 - (x0 + 19)), x0 + 19, y, t.text);
			t.textRight(g, this.font, HudRenderer.fmt(it.count), colCount, y, t.text);
			t.textRight(g, this.font, share(it.count, minerals), colShare, y, t.sub);
			y += ROW;
		}
		if (other > 0) {
			HudRenderer.icon(g, new ItemStack(Items.COBBLED_DEEPSLATE), x0 + 8, y);
			t.text(g, this.font, "기타", x0 + 19, y, t.dim);
			t.textRight(g, this.font, HudRenderer.fmt(other), colCount, y, t.dim);
		}
	}

	/** Each recording of the day, in a box down to {@code bottom}. */
	private void drawSessions(GuiGraphics g, Theme t, History.Day d, int w, int top, int bottom) {
		int x0 = X0;
		int right = w - 8;
		Theme.rrect(g, x0, top, right - x0, Math.max(sessionsHeight(d), bottom - top), t.card, 1);
		t.text(g, this.font, "이날 광질", x0 + 6, top + 5, t.sub);
		int start = x0 + 6;
		int time = x0 + 56;
		int minerals = right - 7;
		int blocks = minerals - 80;
		int y = top + 18;
		t.text(g, this.font, "시작", start, y, t.dim);
		t.text(g, this.font, "걸린 시간", time, y, t.dim);
		t.textRight(g, this.font, "캔 블록", blocks, y, t.dim);
		t.textRight(g, this.font, "먹은 광물", minerals, y, t.dim);
		y += SESSION_ROW;
		List<History.Session> list = d.sessions;
		int from = Math.max(0, list.size() - MAX_SESSIONS);
		for (int i = from; i < list.size(); i++) {
			History.Session s = list.get(i);
			LocalDateTime when = LocalDateTime.ofInstant(Instant.ofEpochMilli(s.start), ZoneId.systemDefault());
			t.text(g, this.font, String.format(Locale.ROOT, "%02d:%02d", when.getHour(), when.getMinute()), start, y, t.text);
			t.text(g, this.font, duration(s.seconds), time, y, t.sub);
			t.textRight(g, this.font, HudRenderer.fmt(s.blocks), blocks, y, t.text);
			t.textRight(g, this.font, HudRenderer.fmt(s.minerals()), minerals, y, t.text);
			y += SESSION_ROW;
		}
		if (from > 0) {
			t.text(g, this.font, "그 전에 " + from + "번 더", start, y, t.dim);
		}
	}

	/** Bottom row: a hint (or the way back, or what a button is about to do) and the three buttons. */
	private void drawFooter(GuiGraphics g, Theme t, int w, int h) {
		int y = h - 22;
		long now = System.currentTimeMillis();
		boolean undoArmed = now - undoArmedAt <= CONFIRM_MS;
		boolean clearArmed = now - clearArmedAt <= CONFIRM_MS;
		int x = w - 7;
		int doneW = buttonWidth("닫기");
		x -= doneW;
		button(g, t, x, y + 1, doneW, "닫기", true, this::onClose);
		String clear = clearArmed ? "한 번 더 누르면 다 지워요" : "전체 기록 지우기";
		int clearW = buttonWidth(clear);
		x -= 5 + clearW;
		button(g, t, x, y + 1, clearW, clear, false, this::onClear);
		String undo = undoArmed ? "한 번 더 누르면 취소" : "방금 기록 취소";
		int undoW = buttonWidth(undo);
		x -= 5 + undoW;
		button(g, t, x, y + 1, undoW, undo, false, this::onUndo);

		// what a pressed button is about to do takes the whole left side
		History.Session last = History.last();
		if (undoArmed && last != null) {
			t.text(g, this.font, HudRenderer.ellipsize(this.font, "지울 기록: " + describe(last), x - 8 - 9), 9, y + 6, t.text);
			return;
		}
		if (clearArmed) {
			t.text(g, this.font, "모든 날의 기록이 지워져요", 9, y + 6, WARN);
			return;
		}
		int tx = 9;
		if (day != null && tab != TODAY) {
			int backW = buttonWidth("돌아가기");
			button(g, t, 7, y + 1, backW, "돌아가기", false, () -> day = null);
			tx = 7 + backW + 6;
		}
		String text;
		int color = t.dim;
		if (notice != null && now - noticeAt <= NOTICE_MS) {
			text = notice;
			color = t.sub;
		} else if (tx > 9) {
			// 돌아가기 is enough
			return;
		} else if (day != null) {
			text = "좌우 방향키로 다른 날도 볼 수 있어요";
		} else {
			text = tab == ALL && month == null ? "막대를 누르면 그달만 자세히 볼 수 있어요" : "막대를 누르면 그날만 자세히 볼 수 있어요";
		}
		t.text(g, this.font, HudRenderer.ellipsize(this.font, text, x - 8 - tx), tx, y + 6, color);
	}

	// ------------------------------------------------------------------ buttons

	/** 방금 기록 취소: the recording saved last goes away (press twice). */
	private void onUndo() {
		long now = System.currentTimeMillis();
		clearArmedAt = 0;
		if (now - undoArmedAt > CONFIRM_MS) {
			undoArmedAt = now;
			return;
		}
		undoArmedAt = 0;
		History.Session last = History.last();
		if (last != null) {
			History.removeLast();
			notice = "지웠어요: " + describe(last);
			noticeAt = now;
		}
	}

	/** 전체 기록 지우기 (press twice). */
	private void onClear() {
		long now = System.currentTimeMillis();
		undoArmedAt = 0;
		if (now - clearArmedAt > CONFIRM_MS) {
			clearArmedAt = now;
			return;
		}
		clearArmedAt = 0;
		History.clear();
		show(WEEK);
	}

	// ------------------------------------------------------------------ helpers

	/** "10/30 01:12 · 광물 241개". */
	private static String describe(History.Session s) {
		LocalDateTime when = LocalDateTime.ofInstant(Instant.ofEpochMilli(s.start), ZoneId.systemDefault());
		return when.getMonthValue() + "/" + when.getDayOfMonth() + " "
				+ String.format(Locale.ROOT, "%02d:%02d", when.getHour(), when.getMinute())
				+ " · 광물 " + HudRenderer.fmt(s.minerals()) + "개";
	}

	/** "10월 24일 ~ 30일", "9월 30일 ~ 10월 29일". */
	private static String span(LocalDate from, LocalDate to) {
		boolean years = from.getYear() != to.getYear();
		String a = (years ? from.getYear() + "년 " : "") + from.getMonthValue() + "월 " + from.getDayOfMonth() + "일";
		if (from.equals(to)) {
			return a;
		}
		String b = years ? to.getYear() + "년 " + to.getMonthValue() + "월 "
				: from.getMonthValue() != to.getMonthValue() ? to.getMonthValue() + "월 " : "";
		return a + " ~ " + b + to.getDayOfMonth() + "일";
	}

	/** "47%", "4.2%" (a share of all minerals). */
	private static String share(long count, long all) {
		if (all <= 0) {
			return "-";
		}
		double p = count * 100.0 / all;
		return p >= 10 || p == 0 ? Math.round(p) + "%" : String.format(Locale.ROOT, "%.1f%%", p);
	}

	/** Numbers over the bars: 12,345 or 12.3만. */
	private static String compact(long n) {
		return n < 100_000 ? HudRenderer.fmt(n) : String.format(Locale.ROOT, "%.1f만", n / 10_000.0);
	}

	private static String weekday(LocalDate date) {
		return WEEKDAYS[date.getDayOfWeek().getValue() - 1];
	}

	/** "12분", "1시간 20분". */
	static String duration(long seconds) {
		long m = seconds / 60;
		return m >= 60 ? (m / 60) + "시간 " + (m % 60) + "분" : m + "분";
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (Keys.stats != null && Keys.stats.matches(keyCode, scanCode)) {
			onClose();
			return true;
		}
		if (day != null && !History.isEmpty() && (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT)) {
			step(keyCode == GLFW.GLFW_KEY_LEFT ? -1 : 1);
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}
}
