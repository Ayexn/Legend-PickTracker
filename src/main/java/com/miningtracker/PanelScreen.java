package com.miningtracker;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;

/**
 * A screen that is one panel in the middle, drawn in the chosen theme (설정창, 광질 통계). Layout is in panel
 * pixels, at the size of the server's own panels; clicks are matched against the areas drawn this frame.
 */
abstract class PanelScreen extends Screen {
	static final int BTN_H = 16;
	static final int SEG_H = 13;

	/** A clickable area, in panel pixels. */
	record Hit(int x1, int y1, int x2, int y2, Runnable action) {
		boolean contains(double x, double y) {
			return x >= x1 && x < x2 && y >= y1 && y < y2;
		}
	}

	/** Screen to go back to when closing, or null to go back to the game. */
	protected final Screen parent;
	protected final List<Hit> hits = new ArrayList<>();
	protected float originX;
	protected float originY;
	protected float scale = 1f;
	/** The mouse in panel pixels, for hover effects. */
	protected double mx;
	protected double my;

	protected PanelScreen(Component title, Screen parent) {
		super(title);
		this.parent = parent;
	}

	protected abstract int panelWidth();

	protected abstract int panelHeight();

	protected abstract void drawPanel(GuiGraphics g, Theme t, int w, int h);

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		// no blur: the game stays visible behind the panel
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		g.fill(0, 0, this.width, this.height, 0x60000000);
		hits.clear();
		Theme t = ModConfig.get().currentTheme();
		int w = panelWidth();
		int h = panelHeight();
		double guiScale = this.minecraft.getWindow().getGuiScale();
		scale = panelScale(guiScale, w, h);
		originX = snap((this.width - w * scale) / 2f, guiScale);
		originY = snap((this.height - h * scale) / 2f, guiScale);
		mx = (mouseX - originX) / scale;
		my = (mouseY - originY) / scale;

		PoseStack pose = g.pose();
		pose.pushPose();
		pose.translate(originX, originY, 0);
		pose.scale(scale, scale, 1f);
		t.panel(g, 0, 0, w, h, true);
		drawPanel(g, t, w, h);
		pose.popPose();
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

	protected boolean hovered(int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	// ------------------------------------------------------------------ controls

	/** A button; the primary one is filled with the accent colour. */
	protected void button(GuiGraphics g, Theme t, int x, int y, int w, String label, boolean primary, Runnable action) {
		boolean hover = hovered(x, y, w, BTN_H);
		int fill = primary ? t.accent : Theme.withAlpha(t.text, hover ? 40 : 26);
		if (primary && hover) {
			fill = Theme.mix(fill, 0xFFFFFFFF, 0.15f);
		}
		Theme.rrect(g, x, y, w, BTN_H, fill, 1);
		int tx = x + (w - this.font.width(label)) / 2;
		if (primary) {
			g.drawString(this.font, label, tx, y + 4, t.onAccent, false);
		} else {
			t.text(g, this.font, label, tx, y + 4, t.text);
		}
		hits.add(new Hit(x, y, x + w, y + BTN_H, action));
	}

	protected int buttonWidth(String label) {
		return this.font.width(label) + 20;
	}

	/** A small square button with an arrow (◀ ▶). */
	protected void arrowButton(GuiGraphics g, Theme t, int x, int y, boolean left, Runnable action) {
		int size = SEG_H;
		Theme.rrect(g, x, y, size, size, hovered(x, y, size, size) ? Theme.withAlpha(t.text, 40) : t.track, 1);
		int c = t.text;
		for (int i = 0; i < 4; i++) {
			int px = left ? x + 7 - i : x + 5 + i;
			g.fill(px, y + 3 + i, px + 1, y + 4 + i, c);
			g.fill(px, y + 9 - i, px + 1, y + 10 - i, c);
		}
		hits.add(new Hit(x, y, x + size, y + size, action));
	}

	/** Choices side by side in one rounded group; the chosen one filled with the accent colour. */
	protected void choices(GuiGraphics g, Theme t, String[] labels, int selected, boolean usable, int right, int y,
			java.util.function.IntConsumer select) {
		int total = choicesWidth(labels);
		int x = right - total;
		Theme.rrect(g, x, y, total, SEG_H, t.track, 1);
		x += 1;
		for (int i = 0; i < labels.length; i++) {
			int segW = this.font.width(labels[i]) + 12;
			boolean on = i == selected;
			if (on) {
				Theme.rrect(g, x, y + 1, segW, SEG_H - 2, usable ? t.accent : Theme.withAlpha(t.dim, 110), 1);
				g.drawString(this.font, labels[i], x + 6, y + 3, usable ? t.onAccent : t.text, false);
			} else {
				boolean hover = usable && hovered(x, y, segW, SEG_H);
				if (hover) {
					Theme.rrect(g, x, y + 1, segW, SEG_H - 2, Theme.withAlpha(t.text, 22), 1);
				}
				t.text(g, this.font, labels[i], x + 6, y + 3, usable ? t.sub : t.dim);
				if (usable) {
					int index = i;
					hits.add(new Hit(x, y, x + segW, y + SEG_H, () -> select.accept(index)));
				}
			}
			x += segW;
		}
	}

	protected int choicesWidth(String[] labels) {
		int total = 2;
		for (String l : labels) {
			total += this.font.width(l) + 12;
		}
		return total;
	}

	/** An on/off switch whose right edge is at {@code right}. */
	protected void toggle(GuiGraphics g, Theme t, boolean on, boolean usable, int right, int y, Runnable flip) {
		int x = right - 22;
		int track = on ? t.accent : 0xFF5A606C;
		if (!usable) {
			track = Theme.withAlpha(track, 90);
		}
		Theme.rrect(g, x, y + 1, 22, 11, track, 2);
		int kx = on ? right - 10 : x + 2;
		// a greyed knob on dark themes; on the light theme the greyed track is light, so the knob stays white
		int knob = usable || !t.shadow ? 0xFFFAFCFF : 0xFFB8BEC8;
		Theme.rrect(g, kx, y + 3, 8, 7, knob, 1);
		if (usable) {
			hits.add(new Hit(x, y, right, y + SEG_H, flip));
		}
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && scale > 0f) {
			double x = (mouseX - originX) / scale;
			double y = (mouseY - originY) / scale;
			for (Hit hit : List.copyOf(hits)) {
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
	public void onClose() {
		ModConfig.save();
		this.minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
