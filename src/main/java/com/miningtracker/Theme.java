package com.miningtracker;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.Map;

/**
 * Colours shared by the board, the pickup toasts and the screens (설정 → 테마). One look in six colours: dark
 * cards with pixel-rounded corners and a coloured accent; 흰색 is a light card, 투명 has almost no panel.
 */
public final class Theme {
	public final String id;
	public final String name;
	/** Panel background. */
	final int bg;
	/** Background of full screens (settings, statistics): more solid so they stay readable. */
	final int screenBg;
	/** 1 px line along the top edge of a panel (0 = none). */
	final int highlight;
	final int head;
	final int text;
	final int sub;
	final int dim;
	/** Boxes and rows inside a panel. */
	final int card;
	/** Background of switches and choice groups. */
	final int track;
	final int sep;
	final int accent;
	/** Text on the accent colour. */
	final int onAccent;
	/** How strongly the mineral bars and item slots are tinted (0-255). */
	final int barAlpha;
	final int slotAlpha;
	final boolean shadow;

	private Theme(String id, String name, int bg, int screenBg, int highlight, int head, int text, int sub, int dim,
			int card, int track, int sep, int accent, int onAccent, int barAlpha, int slotAlpha, boolean shadow) {
		this.id = id;
		this.name = name;
		this.bg = bg;
		this.screenBg = screenBg;
		this.highlight = highlight;
		this.head = head;
		this.text = text;
		this.sub = sub;
		this.dim = dim;
		this.card = card;
		this.track = track;
		this.sep = sep;
		this.accent = accent;
		this.onAccent = onAccent;
		this.barAlpha = barAlpha;
		this.slotAlpha = slotAlpha;
		this.shadow = shadow;
	}

	private static Theme dark(String id, String name, int bg, int highlight, int card, int accent, int onAccent) {
		return new Theme(id, name, bg, 0xF40E1015, highlight, 0xFFFCFDFF, 0xFFEEF1F6, 0xFFAAB2C0, 0xFF808A9A,
				card, 0x10FFFFFF, 0x12FFFFFF, accent, onAccent, 44, 40, true);
	}

	public static final Theme MINT = dark("mint", "민트", 0xE40E1015, 0x1AFFFFFF, 0x0AFFFFFF, 0xFF62D6A0, 0xFF0C1612);
	public static final Theme BLUE = dark("blue", "파랑", 0xE40E1015, 0x1AFFFFFF, 0x0AFFFFFF, 0xFF6CA8FF, 0xFF0A1428);
	public static final Theme PURPLE = dark("purple", "보라", 0xE40E1015, 0x1AFFFFFF, 0x0AFFFFFF, 0xFFB28EFF, 0xFF180E2C);
	public static final Theme ORANGE = dark("orange", "주황", 0xE40E1015, 0x1AFFFFFF, 0x0AFFFFFF, 0xFFFFA65E, 0xFF2C1606);
	public static final Theme WHITE = new Theme("white", "흰색", 0xEEF4F6F9, 0xF8F4F6F9, 0, 0xFF14181E, 0xFF1A1E26,
			0xFF464E5C, 0xFF7A8290, 0x09000000, 0x10000000, 0x16000000, 0xFF1A926A, 0xFFFFFFFF, 70, 60, false);
	public static final Theme CLEAR = dark("clear", "투명", 0x3C000000, 0, 0x08FFFFFF, 0xFF62D6A0, 0xFF0C1612);
	public static final List<Theme> ALL = List.of(MINT, BLUE, PURPLE, ORANGE, WHITE, CLEAR);

	/** Themes of 0.2.x, mapped to the closest new one. */
	private static final Map<String, String> OLD_IDS = Map.of("default", "mint", "vanilla", "purple",
			"inventory", "white", "black", "mint");

	public static Theme byId(String id) {
		String wanted = OLD_IDS.getOrDefault(id, id);
		for (Theme t : ALL) {
			if (t.id.equals(wanted)) {
				return t;
			}
		}
		return MINT;
	}

	int index() {
		return ALL.indexOf(this);
	}

	/** The green "+N" of the pickup toasts (darker on the light theme so it stays readable). */
	int plus() {
		return shadow ? 0xFF8AE28A : 0xFF1A926A;
	}

	/** Colour of the "측정 중" mark: the accent, or grey while resting (the time is stopped). */
	int status(boolean resting) {
		return resting ? dim : accent;
	}

	// ------------------------------------------------------------------ shapes

	/** A rectangle with pixel-rounded corners. Rows never overlap, so see-through colours stay even. */
	static void rrect(GuiGraphics g, int x, int y, int w, int h, int color, int r) {
		if (w <= 0 || h <= 0) {
			return;
		}
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		for (int i = 0; i < r; i++) {
			int inset = r - i;
			g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
			g.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
		}
		g.fill(x, y + r, x + w, y + h - r, color);
	}

	/** A panel: rounded background and a light line along the top. {@code screen}: for a full screen. */
	void panel(GuiGraphics g, int x, int y, int w, int h, boolean screen) {
		rrect(g, x, y, w, h, screen ? screenBg : bg, 2);
		if (highlight != 0) {
			g.fill(x + 2, y, x + w - 2, y + 1, highlight);
		}
	}

	/** The coloured line along the left edge of the board. */
	static void stripe(GuiGraphics g, int x, int y, int h, int color) {
		g.fill(x, y, x + 2, y + h, color);
	}

	/** "● 측정 중" in a soft pill whose right edge is at {@code right}. Returns its left edge. */
	int pill(GuiGraphics g, Font font, String label, int right, int y, int color) {
		int w = font.width(label) + 15;
		int x = right - w;
		rrect(g, x, y - 2, w, 12, withAlpha(color, shadow ? 48 : 40), 1);
		g.fill(x + 5, y + 3, x + 7, y + 5, color);
		text(g, font, label, x + 10, y, color);
		return x;
	}

	// ------------------------------------------------------------------ text

	void text(GuiGraphics g, Font font, String s, int x, int y, int color) {
		g.drawString(font, s, x, y, color, shadow);
	}

	void textRight(GuiGraphics g, Font font, String s, int right, int y, int color) {
		g.drawString(font, s, right - font.width(s), y, color, shadow);
	}

	/** Text drawn {@code scale} times bigger (for the timer and big numbers). */
	void big(GuiGraphics g, Font font, String s, float x, float y, float scale, int color) {
		PoseStack pose = g.pose();
		pose.pushPose();
		pose.translate(x, y, 0);
		pose.scale(scale, scale, 1f);
		g.drawString(font, s, 0, 0, color, shadow);
		pose.popPose();
	}

	static int bigWidth(Font font, String s, float scale) {
		return Math.round(font.width(s) * scale);
	}

	// ------------------------------------------------------------------ colours

	static int withAlpha(int rgb, int alpha) {
		return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0x00FFFFFF);
	}

	/** Dark or white text, whichever reads better on {@code color}. */
	static int readableOn(int color) {
		int r = (color >> 16) & 0xFF;
		int g = (color >> 8) & 0xFF;
		int b = color & 0xFF;
		return (r * 299 + g * 587 + b * 114) / 1000 > 150 ? 0xFF14181E : 0xFFFFFFFF;
	}

	/** {@code a} mixed with {@code b}; {@code f} = share of b. */
	static int mix(int a, int b, float f) {
		int r = 0;
		for (int shift = 0; shift <= 24; shift += 8) {
			int ca = (a >>> shift) & 0xFF;
			int cb = (b >>> shift) & 0xFF;
			r |= Math.round(ca + (cb - ca) * f) << shift;
		}
		return r;
	}
}
