package com.miningtracker;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 설정창 (F9 or /광질 설정). Drawn like the tally board, in the chosen theme: one row per setting with all
 * of its choices side by side, the chosen one highlighted. A choice is saved the moment it is clicked.
 */
public final class SettingsScreen extends Screen {
	private static final int MIN_WIDTH = 262;
	private static final int ROW = 30;
	private static final int SECTION = 13;
	private static final int SEG_H = 13;
	private static final int BTN_H = 16;

	private static final String MODE_AUTO = "자동: 야생에서 곡괭이로 돌이나 광석을 캐면 시작돼요";
	private static final String MODE_MANUAL = "수동: F7을 눌러서 직접 시작하고 끝내요";
	private static final String PLACE_ON = "세레니티처럼 기록 안 할 곳에서 한 번 끄기로 바꿔 두세요";
	private static final String PLACE_OFF = "이곳에서는 자동 기록을 하지 않아요";
	private static final String PLACE_NOT_WILD = "여기는 야생이 아니라서 원래 자동 기록이 안 켜져요";
	private static final String PLACE_MANUAL = "수동 방식에서는 쓰지 않아요";
	private static final String PLACE_NO_WORLD = "월드에 들어가 있을 때 바꿀 수 있어요";
	private static final String END_AUTO = "이 시간 동안 안 캐면 광질이 끝난 걸로 봐요";
	private static final String END_MANUAL = "수동 방식에서는 F7을 다시 누르면 끝나요";
	private static final String RESTART = "새 광질을 0부터 셀지, 전 기록에 이어서 셀지";
	private static final String SUMMARY = "광질이 끝나면 이번 기록을 채팅에 남겨요 (나만 보여요)";
	private static final String THEME = "집계판, 획득 알림, 설정창 색이 같이 바뀌어요";
	private static final String SAVED_HINT = "바꾸면 바로 저장돼요";

	/** One row: a label, its choices, which one is chosen (-1 = none), and what clicking one does. */
	private static final class Setting {
		final String label;
		final String[] choices;
		final IntSupplier selected;
		final IntConsumer select;
		final Supplier<String> description;
		final BooleanSupplier usable;

		Setting(String label, String[] choices, IntSupplier selected, IntConsumer select,
				Supplier<String> description, BooleanSupplier usable) {
			this.label = label;
			this.choices = choices;
			this.selected = selected;
			this.select = select;
			this.description = description;
			this.usable = usable;
		}
	}

	private record Section(String title, List<Setting> settings) {
	}

	/** A clickable area, in panel pixels. */
	private record Hit(int x1, int y1, int x2, int y2, Runnable action) {
		boolean contains(double x, double y) {
			return x >= x1 && x < x2 && y >= y1 && y < y2;
		}
	}

	private final Screen parent;
	private final List<Section> sections = new ArrayList<>();
	private final List<String> allDescriptions = new ArrayList<>();
	private final List<Hit> hits = new ArrayList<>();
	private int panelWidth = MIN_WIDTH;
	private float originX;
	private float originY;
	private float scale = 1f;

	public SettingsScreen(Screen parent) {
		super(Component.literal("광질 측정 설정"));
		this.parent = parent;

		List<Setting> record = new ArrayList<>();
		record.add(new Setting("기록 방식", new String[]{"자동", "수동"},
				() -> ModConfig.get().autoMode ? 0 : 1,
				i -> ModConfig.get().autoMode = i == 0,
				() -> ModConfig.get().autoMode ? MODE_AUTO : MODE_MANUAL,
				() -> true));
		record.add(new Setting("지금 있는 곳에서 자동 기록", new String[]{"켜기", "끄기"},
				SettingsScreen::placeSelected,
				SettingsScreen::selectPlace,
				SettingsScreen::placeDescription,
				SettingsScreen::placeUsable));
		int[] seconds = ModConfig.END_AFTER_CHOICES;
		String[] endLabels = new String[seconds.length];
		for (int i = 0; i < seconds.length; i++) {
			endLabels[i] = Tracker.formatSeconds(seconds[i]);
		}
		record.add(new Setting("광질 끝 판정", endLabels,
				() -> indexOf(seconds, ModConfig.get().endAfterSeconds),
				i -> ModConfig.get().endAfterSeconds = seconds[i],
				() -> ModConfig.get().autoMode ? END_AUTO : END_MANUAL,
				() -> ModConfig.get().autoMode));
		record.add(new Setting("다시 시작하면", new String[]{"0부터", "이어서"},
				() -> ModConfig.get().continueOnRestart ? 1 : 0,
				i -> ModConfig.get().continueOnRestart = i == 1,
				() -> RESTART,
				() -> true));
		record.add(new Setting("끝나면 채팅에 기록", new String[]{"켜기", "끄기"},
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
		screen.add(new Setting("집계판 모양", layoutLabels,
				() -> ModConfig.get().hudLayout().ordinal(),
				i -> ModConfig.get().layout = layouts[i].id,
				() -> ModConfig.get().hudLayout().description,
				() -> true));
		String[] themeLabels = new String[Theme.ALL.size()];
		for (int i = 0; i < themeLabels.length; i++) {
			themeLabels[i] = Theme.ALL.get(i).name;
		}
		screen.add(new Setting("테마", themeLabels,
				() -> ModConfig.get().currentTheme().index(),
				i -> ModConfig.get().theme = Theme.ALL.get(i).id,
				() -> THEME,
				() -> true));
		sections.add(new Section("화면", screen));

		allDescriptions.addAll(List.of(MODE_AUTO, MODE_MANUAL, PLACE_ON, PLACE_OFF, PLACE_NOT_WILD, PLACE_MANUAL,
				PLACE_NO_WORLD, END_AUTO, END_MANUAL, RESTART, SUMMARY, THEME));
	}

	// ------------------------------------------------------------------ the "지금 있는 곳" row

	private static ClientLevel level() {
		return Minecraft.getInstance().level;
	}

	private static boolean placeUsable() {
		ClientLevel level = level();
		return level != null && ModConfig.get().autoMode && Tracker.isWildWorld(level);
	}

	private static int placeSelected() {
		ClientLevel level = level();
		if (level == null || !Tracker.isWildWorld(level)) {
			return 1; // never automatic here anyway
		}
		return ModConfig.get().isExcluded(Tracker.placeKey(level)) ? 1 : 0;
	}

	private static void selectPlace(int index) {
		ClientLevel level = level();
		if (level == null) {
			return;
		}
		boolean off = index == 1;
		ModConfig.get().setExcluded(Tracker.placeKey(level), off);
		if (off) {
			Tracker.INSTANCE.onPlaceSwitchedOff(Minecraft.getInstance());
		}
	}

	private static String placeDescription() {
		ClientLevel level = level();
		if (level == null) {
			return PLACE_NO_WORLD;
		}
		if (!Tracker.isWildWorld(level)) {
			return PLACE_NOT_WILD;
		}
		if (!ModConfig.get().autoMode) {
			return PLACE_MANUAL;
		}
		return ModConfig.get().isExcluded(Tracker.placeKey(level)) ? PLACE_OFF : PLACE_ON;
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
		w = Math.max(w, 18 + this.font.width("광질 측정 설정") + 16 + stateWidth() + 8);
		for (Section section : sections) {
			for (Setting s : section.settings()) {
				w = Math.max(w, 8 + this.font.width(s.label) + 12 + choicesWidth(s) + 8);
			}
		}
		for (String d : allDescriptions) {
			w = Math.max(w, 8 + this.font.width(d) + 8);
		}
		panelWidth = w;
	}

	private int panelHeight() {
		int rows = 0;
		for (Section section : sections) {
			rows += section.settings().size();
		}
		return 20 + SECTION * sections.size() + ROW * rows + 6 + BTN_H + 9;
	}

	/**
	 * Same size as the server's own panels: at GUI scale 3 one panel pixel is two screen pixels, so the
	 * Korean letters stay sharp. Smaller if the window is too small for it.
	 */
	private float panelScale(double guiScale, int w, int h) {
		int k = Math.max(1, (int) Math.round(guiScale / 3.0));
		float s = (float) (2.0 * k / guiScale);
		float fit = Math.min((this.width - 8f) / w, (this.height - 8f) / h);
		return Math.max(0.25f, Math.min(s, fit));
	}

	/** Rounds a GUI coordinate to a whole screen pixel so the text is not drawn between pixels. */
	private static float snap(float value, double guiScale) {
		return (float) (Math.round(value * guiScale) / guiScale);
	}

	private int choicesWidth(Setting s) {
		int total = (s.choices.length - 1) * 2;
		for (String c : s.choices) {
			total += this.font.width(c) + 12;
		}
		return total;
	}

	private int stateWidth() {
		return 8 + Math.max(this.font.width("측정 중"), Math.max(this.font.width("쉬는 중"), this.font.width("측정 안 함")));
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		// no blur: the game stays visible behind the panel
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		g.fill(0, 0, this.width, this.height, 0x60000000);
		hits.clear();
		Theme t = ModConfig.get().currentTheme();

		int w = panelWidth;
		int h = panelHeight();
		double guiScale = this.minecraft.getWindow().getGuiScale();
		scale = panelScale(guiScale, w, h);
		originX = snap((this.width - w * scale) / 2f, guiScale);
		originY = snap((this.height - h * scale) / 2f, guiScale);
		double mx = (mouseX - originX) / scale;
		double my = (mouseY - originY) / scale;

		PoseStack pose = g.pose();
		pose.pushPose();
		pose.translate(originX, originY, 0);
		pose.scale(scale, scale, 1f);

		t.frame(g, 0, 0, w, h, true);
		HudRenderer.icon(g, HudRenderer.headerIcon(), 6, 4);
		t.text(g, this.font, "광질 측정 설정", 18, 4, t.head);
		drawState(g, t, w - 8);
		g.fill(t.inset(), 15, w - t.inset(), 16, t.rule);

		int y = 20;
		for (Section section : sections) {
			t.text(g, this.font, section.title(), 8, y + 3, t.label);
			g.fill(8 + this.font.width(section.title()) + 5, y + 7, w - 9, y + 8, t.sep);
			y += SECTION;
			List<Setting> settings = section.settings();
			for (int i = 0; i < settings.size(); i++) {
				Setting s = settings.get(i);
				boolean usable = s.usable.getAsBoolean();
				t.text(g, this.font, s.label, 8, y + 5, usable ? t.text : t.dim);
				drawChoices(g, t, s, usable, w - 8, y + 2, mx, my);
				t.text(g, this.font, HudRenderer.ellipsize(this.font, s.description.get(), w - 16), 8, y + 18, t.dim);
				y += ROW;
				if (i < settings.size() - 1) {
					g.fill(6, y - 3, w - 6, y - 2, t.sep);
				}
			}
		}

		y += 6;
		int doneW = this.font.width("완료") + 20;
		int editW = this.font.width("위치/크기 편집") + 20;
		int doneX = w - 8 - doneW;
		int editX = doneX - 6 - editW;
		if (8 + this.font.width(SAVED_HINT) + 8 <= editX) {
			t.text(g, this.font, SAVED_HINT, 8, y + 4, t.hint);
		}
		drawButton(g, t, editX, y, editW, "위치/크기 편집", false, mx, my,
				() -> this.minecraft.setScreen(new EditHudScreen(this)));
		drawButton(g, t, doneX, y, doneW, "완료", true, mx, my, this::onClose);

		pose.popPose();
	}

	/** Header, right side: whether a recording is running right now. */
	private void drawState(GuiGraphics g, Theme t, int right) {
		Tracker tracker = Tracker.INSTANCE;
		if (!tracker.isRecording()) {
			t.textRight(g, this.font, "측정 안 함", right, 4, t.dim);
			return;
		}
		boolean resting = tracker.isResting();
		String text = resting ? "쉬는 중" : "측정 중";
		int color = resting ? t.yellow : t.green;
		int x = right - this.font.width(text);
		t.text(g, this.font, text, x, 4, color);
		g.fill(x - 8, 6, x - 4, 10, color);
	}

	private void drawChoices(GuiGraphics g, Theme t, Setting s, boolean usable, int right, int y, double mx, double my) {
		int x = right - choicesWidth(s);
		int selected = s.selected.getAsInt();
		int hoverFill = Theme.mix(t.control, t.accent, 0.25f);
		for (int i = 0; i < s.choices.length; i++) {
			String label = s.choices[i];
			int segW = this.font.width(label) + 12;
			boolean on = i == selected;
			boolean hover = usable && !on && mx >= x && mx < x + segW && my >= y && my < y + SEG_H;
			int edge;
			int fill;
			int text;
			if (!usable) {
				edge = t.controlEdge;
				fill = on ? Theme.mix(t.control, t.controlEdge, 0.5f) : t.control;
				text = on ? t.controlText : t.dim;
			} else if (on) {
				edge = t.accentEdge;
				fill = t.accent;
				text = t.accentText;
			} else {
				edge = hover ? t.accentEdge : t.controlEdge;
				fill = hover ? hoverFill : t.control;
				text = t.controlText;
			}
			g.fill(x, y, x + segW, y + SEG_H, edge);
			g.fill(x + 1, y + 1, x + segW - 1, y + SEG_H - 1, fill);
			g.drawString(this.font, label, x + 6, y + 3, text, (on && usable) || t.shadow);
			if (usable && !on) {
				int index = i;
				hits.add(new Hit(x, y, x + segW, y + SEG_H, () -> {
					s.select.accept(index);
					ModConfig.save();
				}));
			}
			x += segW + 2;
		}
	}

	private void drawButton(GuiGraphics g, Theme t, int x, int y, int w, String label, boolean primary,
			double mx, double my, Runnable action) {
		boolean hover = mx >= x && mx < x + w && my >= y && my < y + BTN_H;
		int base = primary ? t.accent : t.control;
		int fill = hover ? Theme.mix(base, 0xFFFFFFFF, 0.12f) : base;
		int edge = primary || hover ? t.accentEdge : t.controlEdge;
		g.fill(x, y, x + w, y + BTN_H, edge);
		g.fill(x + 1, y + 1, x + w - 1, y + BTN_H - 1, fill);
		g.drawString(this.font, label, x + 10, y + 4, primary ? t.accentText : t.controlText, t.shadow || primary);
		hits.add(new Hit(x, y, x + w, y + BTN_H, action));
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && scale > 0f) {
			double x = (mouseX - originX) / scale;
			double y = (mouseY - originY) / scale;
			for (Hit hit : hits) {
				if (hit.contains(x, y)) {
					this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
					hit.action().run();
					return true;
				}
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (Keys.edit != null && Keys.edit.matches(keyCode, scanCode)) {
			onClose(); // the same key that opened it closes it
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void onClose() {
		ModConfig.save();
		this.minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
