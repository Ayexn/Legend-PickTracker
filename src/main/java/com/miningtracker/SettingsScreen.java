package com.miningtracker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 설정창 (F9 or /광질 설정). One card per setting: choices side by side with the chosen one filled, or a
 * switch for on/off. A choice is saved the moment it is clicked.
 */
public final class SettingsScreen extends PanelScreen {
	private static final int MIN_WIDTH = 248;
	private static final int ROW = 32;
	private static final int CARD_H = 29;
	private static final int SECTION = 14;

	private static final String MODE_AUTO = "자동: 야생에서 곡괭이로 돌이나 광석을 캐면 시작돼요";
	private static final String MODE_MANUAL = "수동: F7을 눌러서 직접 시작하고 끝내요";
	private static final String END_AUTO = "이 시간 동안 안 캐면 광질이 끝난 걸로 봐요";
	private static final String END_MANUAL = "수동 방식에서는 F7을 다시 누르면 끝나요";
	private static final String RESTART = "새 광질을 0부터 셀지, 전 기록에 이어서 셀지";
	private static final String SUMMARY = "광질이 끝나면 이번 기록을 채팅에 남겨요 (나만 보여요)";
	private static final String THEME = "집계판, 획득 알림, 설정창 색이 같이 바뀌어요";
	private static final String STATS = "지난 광질 기록을 날짜별로 봐요 (F10)";
	private static final String SAVED_HINT = "바꾸면 바로 저장돼요";

	private enum Kind { CHOICES, SWITCH, BUTTON }

	/** One card: a label, a description and its control. */
	private static final class Setting {
		final String label;
		final Kind kind;
		final String[] choices;
		final IntSupplier selected;
		final IntConsumer select;
		final Supplier<String> description;
		final BooleanSupplier usable;

		Setting(String label, Kind kind, String[] choices, IntSupplier selected, IntConsumer select,
				Supplier<String> description, BooleanSupplier usable) {
			this.label = label;
			this.kind = kind;
			this.choices = choices;
			this.selected = selected;
			this.select = select;
			this.description = description;
			this.usable = usable;
		}
	}

	private record Section(String title, List<Setting> settings) {
	}

	private final List<Section> sections = new ArrayList<>();
	private final List<String> allDescriptions = new ArrayList<>();
	private int panelWidth = MIN_WIDTH;

	public SettingsScreen(Screen parent) {
		super(Component.literal("광질 측정 설정"), parent);

		List<Setting> record = new ArrayList<>();
		record.add(new Setting("기록 방식", Kind.CHOICES, new String[]{"자동", "수동"},
				() -> ModConfig.get().autoMode ? 0 : 1,
				i -> ModConfig.get().autoMode = i == 0,
				() -> ModConfig.get().autoMode ? MODE_AUTO : MODE_MANUAL,
				() -> true));
		int[] seconds = ModConfig.END_AFTER_CHOICES;
		String[] endLabels = new String[seconds.length];
		for (int i = 0; i < seconds.length; i++) {
			endLabels[i] = Tracker.formatSeconds(seconds[i]);
		}
		record.add(new Setting("광질 끝 판정", Kind.CHOICES, endLabels,
				() -> indexOf(seconds, ModConfig.get().endAfterSeconds),
				i -> ModConfig.get().endAfterSeconds = seconds[i],
				() -> ModConfig.get().autoMode ? END_AUTO : END_MANUAL,
				() -> ModConfig.get().autoMode));
		record.add(new Setting("다시 시작하면", Kind.CHOICES, new String[]{"0부터", "이어서"},
				() -> ModConfig.get().continueOnRestart ? 1 : 0,
				i -> ModConfig.get().continueOnRestart = i == 1,
				() -> RESTART,
				() -> true));
		record.add(new Setting("끝나면 채팅에 기록", Kind.SWITCH, null,
				() -> ModConfig.get().chatSummary ? 0 : 1,
				i -> ModConfig.get().chatSummary = i == 0,
				() -> SUMMARY,
				() -> true));
		sections.add(new Section("기록", record));

		List<Setting> screen = new ArrayList<>();
		HudLayout[] layouts = HudLayout.values();
		String[] layoutLabels = new String[layouts.length];
		for (int i = 0; i < layouts.length; i++) {
			layoutLabels[i] = layouts[i].label;
			allDescriptions.add(layouts[i].description);
		}
		screen.add(new Setting("집계판 모양", Kind.CHOICES, layoutLabels,
				() -> ModConfig.get().hudLayout().ordinal(),
				i -> ModConfig.get().layout = layouts[i].id,
				() -> ModConfig.get().hudLayout().description,
				() -> true));
		String[] themeLabels = new String[Theme.ALL.size()];
		for (int i = 0; i < themeLabels.length; i++) {
			themeLabels[i] = Theme.ALL.get(i).name;
		}
		screen.add(new Setting("테마", Kind.CHOICES, themeLabels,
				() -> ModConfig.get().currentTheme().index(),
				i -> ModConfig.get().theme = Theme.ALL.get(i).id,
				() -> THEME,
				() -> true));
		screen.add(new Setting("광질 통계", Kind.BUTTON, new String[]{"열기"},
				() -> -1,
				i -> Minecraft.getInstance().setScreen(new StatsScreen(this)),
				() -> STATS,
				() -> true));
		sections.add(new Section("화면", screen));

		allDescriptions.addAll(List.of(MODE_AUTO, MODE_MANUAL, END_AUTO, END_MANUAL, RESTART, SUMMARY, THEME, STATS));
	}

	private static int indexOf(int[] values, int value) {
		for (int i = 0; i < values.length; i++) {
			if (values[i] == value) {
				return i;
			}
		}
		return -1;
	}

	// ------------------------------------------------------------------ layout

	@Override
	protected void init() {
		int w = MIN_WIDTH;
		w = Math.max(w, 19 + this.font.width("광질 측정 설정") + 16 + stateWidth() + 8);
		for (Section section : sections) {
			for (Setting s : section.settings()) {
				w = Math.max(w, 11 + this.font.width(s.label) + 12 + controlWidth(s) + 11);
			}
		}
		for (String d : allDescriptions) {
			w = Math.max(w, 11 + this.font.width(d) + 11);
		}
		w = Math.max(w, 9 + this.font.width(SAVED_HINT) + 12 + buttonWidth("위치/크기 편집") + 5 + buttonWidth("완료") + 7);
		panelWidth = w;
	}

	private int controlWidth(Setting s) {
		return switch (s.kind) {
			case CHOICES -> choicesWidth(s.choices);
			case SWITCH -> 22;
			case BUTTON -> this.font.width(s.choices[0]) + 16;
		};
	}

	private int stateWidth() {
		return Math.max(this.font.width("측정 안 함"), Math.max(this.font.width("측정 중"), this.font.width("쉬는 중")) + 15);
	}

	@Override
	protected int panelWidth() {
		return panelWidth;
	}

	@Override
	protected int panelHeight() {
		int rows = 0;
		for (Section section : sections) {
			rows += section.settings().size();
		}
		return 20 + SECTION * sections.size() + ROW * rows + 24;
	}

	// ------------------------------------------------------------------ drawing

	@Override
	protected void drawPanel(GuiGraphics g, Theme t, int w, int h) {
		HudRenderer.icon(g, HudRenderer.headerIcon(), 8, 6);
		t.text(g, this.font, "광질 측정 설정", 19, 6, t.text);
		drawState(g, t, w - 8);

		int y = 20;
		for (Section section : sections) {
			t.text(g, this.font, section.title(), 9, y + 2, t.accent);
			g.fill(9 + this.font.width(section.title()) + 5, y + 6, w - 10, y + 7, t.sep);
			y += SECTION;
			for (Setting s : section.settings()) {
				boolean usable = s.usable.getAsBoolean();
				Theme.rrect(g, 6, y, w - 12, CARD_H, t.card, 1);
				t.text(g, this.font, s.label, 11, y + 5, usable ? t.text : t.dim);
				t.text(g, this.font, HudRenderer.ellipsize(this.font, s.description.get(), w - 22), 11, y + 18, t.dim);
				drawControl(g, t, s, usable, w - 11, y + 3);
				y += ROW;
			}
		}

		int doneW = buttonWidth("완료");
		int editW = buttonWidth("위치/크기 편집");
		int doneX = w - 7 - doneW;
		int editX = doneX - 5 - editW;
		t.text(g, this.font, SAVED_HINT, 9, y + 6, t.dim);
		button(g, t, editX, y + 1, editW, "위치/크기 편집", false, () -> this.minecraft.setScreen(new EditHudScreen(this)));
		button(g, t, doneX, y + 1, doneW, "완료", true, this::onClose);
	}

	private void drawControl(GuiGraphics g, Theme t, Setting s, boolean usable, int right, int y) {
		switch (s.kind) {
			case CHOICES -> choices(g, t, s.choices, s.selected.getAsInt(), usable, right, y, i -> {
				s.select.accept(i);
				ModConfig.save();
			});
			case SWITCH -> {
				boolean on = s.selected.getAsInt() == 0;
				toggle(g, t, on, usable, right, y, () -> {
					s.select.accept(on ? 1 : 0);
					ModConfig.save();
				});
			}
			case BUTTON -> {
				String label = s.choices[0];
				int bw = this.font.width(label) + 16;
				int x = right - bw;
				boolean hover = hovered(x, y, bw, SEG_H);
				Theme.rrect(g, x, y, bw, SEG_H, t.accent, 1);
				Theme.rrect(g, x + 1, y + 1, bw - 2, SEG_H - 2, hover ? Theme.mix(t.screenBg | 0xFF000000, t.accent, 0.2f)
						: t.screenBg | 0xFF000000, 1);
				t.text(g, this.font, label, x + 8, y + 3, t.accent);
				hits.add(new Hit(x, y, right, y + SEG_H, () -> s.select.accept(0)));
			}
		}
	}

	/** Header, right side: whether a recording is running right now. */
	private void drawState(GuiGraphics g, Theme t, int right) {
		Tracker tracker = Tracker.INSTANCE;
		if (!tracker.isRecording()) {
			t.textRight(g, this.font, "측정 안 함", right, 6, t.dim);
			return;
		}
		boolean resting = tracker.isResting();
		t.pill(g, this.font, resting ? "쉬는 중" : "측정 중", right, 6, t.status(resting));
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (Keys.edit != null && Keys.edit.matches(keyCode, scanCode)) {
			onClose(); // the same key that opened it closes it
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}
}
