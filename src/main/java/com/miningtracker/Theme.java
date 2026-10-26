package com.miningtracker;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/** Colours and panel style shared by the board, the pickup toasts and the settings screen (설정 → 테마). */
public final class Theme {
	enum Style { FRAME, TOOLTIP, BEVEL, FLAT }

	public final String id;
	public final String name;
	final Style style;
	// panel
	int edge;
	int border;
	int bg;
	int light;
	int dark;
	int borderTop;
	int borderBottom;
	/** Background of the settings screen (see-through themes get a darker one there so it stays readable). */
	int screenBg;
	int rule;
	int sep;
	// text
	int head;
	int text;
	int sub;
	int label;
	int dim;
	int green;
	int yellow;
	int plus;
	boolean shadow = true;
	// settings controls and item slots
	int accent;
	int accentEdge;
	int accentText;
	int control;
	int controlEdge;
	int controlText;
	int hint;
	int slot;
	int slotDark;
	int slotLight;

	private Theme(String id, String name, Style style) {
		this.id = id;
		this.name = name;
		this.style = style;
	}

	public static final Theme DEFAULT = new Theme("default", "기본", Style.FRAME);
	public static final Theme VANILLA = new Theme("vanilla", "바닐라", Style.TOOLTIP);
	public static final Theme INVENTORY = new Theme("inventory", "인벤토리", Style.BEVEL);
	public static final Theme BLACK = new Theme("black", "검정", Style.FLAT);
	public static final Theme CLEAR = new Theme("clear", "투명", Style.FLAT);
	public static final List<Theme> ALL = List.of(DEFAULT, VANILLA, INVENTORY, BLACK, CLEAR);

	static {
		// 기본: the same navy panels the server uses
		Theme t = DEFAULT;
		t.edge = 0xFF0F0F0F;
		t.border = 0xFF383D4A;
		t.bg = 0xFF202326;
		t.screenBg = t.bg;
		t.rule = 0xFF3664BD;
		t.sep = 0xFF30353E;
		t.head = 0xFFFCFCFC;
		t.text = 0xFFEBEEF2;
		t.sub = 0xFF9DB6D5;
		t.label = 0xFF6E9EEB;
		t.dim = 0xFF808A98;
		t.green = 0xFF7BC37B;
		t.yellow = 0xFFE3C46A;
		t.plus = 0xFF8AE28A;
		t.accent = 0xFF3664BD;
		t.accentEdge = 0xFF628CDE;
		t.accentText = 0xFFFCFCFC;
		t.control = 0xFF181A1E;
		t.controlEdge = 0xFF383D4A;
		t.controlText = 0xFF9DB6D5;
		t.hint = 0xFF5F6775;
		t.slot = 0xFF181A1E;
		t.slotDark = 0xFF383D4A;
		t.slotLight = 0xFF464C5C;

		// 바닐라: Minecraft's item tooltip, dark with a purple border
		t = VANILLA;
		t.bg = 0xF0100010;
		t.borderTop = 0x505000FF;
		t.borderBottom = 0x5028007F;
		t.screenBg = t.bg;
		t.rule = 0xFF5000FF;
		t.sep = 0xFF3C1E5A;
		t.head = 0xFFFFFFFF;
		t.text = 0xFFFFFFFF;
		t.sub = 0xFFAAAAAA;
		t.label = 0xFF55FFFF;
		t.dim = 0xFFAAAAAA;
		t.green = 0xFF55FF55;
		t.yellow = 0xFFFFFF55;
		t.plus = 0xFF55FF55;
		t.accent = 0xFF5C2AA8;
		t.accentEdge = 0xFF9466FF;
		t.accentText = 0xFFFFFFFF;
		t.control = 0xFF1C0C28;
		t.controlEdge = 0xFF3C1E66;
		t.controlText = 0xFFAAAAAA;
		t.hint = 0xFF6E5A87;
		t.slot = 0xFF1C0C28;
		t.slotDark = 0xFF3C1E66;
		t.slotLight = 0xFF4A2A70;

		// 인벤토리: the light grey of Minecraft's inventory window, dark text without shadow
		t = INVENTORY;
		t.edge = 0xFF000000;
		t.light = 0xFFFFFFFF;
		t.dark = 0xFF555555;
		t.bg = 0xFFC6C6C6;
		t.screenBg = t.bg;
		t.rule = 0xFF555555;
		t.sep = 0xFF8B8B8B;
		t.head = 0xFF404040;
		t.text = 0xFF404040;
		t.sub = 0xFF284696;
		t.label = 0xFF284696;
		t.dim = 0xFF6E6E6E;
		t.green = 0xFF008000;
		t.yellow = 0xFF9A7400;
		t.plus = 0xFF008000;
		t.shadow = false;
		t.accent = 0xFF3C64C8;
		t.accentEdge = 0xFF1E3C8C;
		t.accentText = 0xFFFFFFFF;
		t.control = 0xFFB4B4B4;
		t.controlEdge = 0xFF555555;
		t.controlText = 0xFF404040;
		t.hint = 0xFF707070;
		t.slot = 0xFF8B8B8B;
		t.slotDark = 0xFF373737;
		t.slotLight = 0xFFFFFFFF;

		// 검정: plain see-through black, no border
		t = BLACK;
		t.bg = 0x96000000;
		t.screenBg = 0xD8000000;
		t.rule = 0x46FFFFFF;
		t.sep = 0x2DFFFFFF;
		t.head = 0xFFFFFFFF;
		t.text = 0xFFFFFFFF;
		t.sub = 0xFFC8C8C8;
		t.label = 0xFFFFD666;
		t.dim = 0xFFAAAAAA;
		t.green = 0xFF78E678;
		t.yellow = 0xFFFFB84D;
		t.plus = 0xFF78E678;
		t.accent = 0xFF3664BD;
		t.accentEdge = 0xFF7FA6F0;
		t.accentText = 0xFFFFFFFF;
		t.control = 0xFF1E1E1E;
		t.controlEdge = 0xFF505050;
		t.controlText = 0xFFC8C8C8;
		t.hint = 0xFF808080;
		t.slot = 0x50000000;
		t.slotDark = 0x40FFFFFF;
		t.slotLight = 0x20FFFFFF;

		// 투명: almost no panel, just the text
		t = CLEAR;
		t.bg = 0x2D000000;
		t.screenBg = 0xC8000000;
		t.rule = 0x3CFFFFFF;
		t.sep = 0x28FFFFFF;
		t.head = 0xFFFFFFFF;
		t.text = 0xFFFFFFFF;
		t.sub = 0xFFD2DCEB;
		t.label = 0xFF8CBEFF;
		t.dim = 0xFFC8C8C8;
		t.green = 0xFF8CEB8C;
		t.yellow = 0xFFE3C46A;
		t.plus = 0xFF8CEB8C;
		t.accent = 0xFF3664BD;
		t.accentEdge = 0xFF7FA6F0;
		t.accentText = 0xFFFFFFFF;
		t.control = 0xFF1E1E1E;
		t.controlEdge = 0xFF505050;
		t.controlText = 0xFFD2DCEB;
		t.hint = 0xFF808080;
		t.slot = 0x40000000;
		t.slotDark = 0x40FFFFFF;
		t.slotLight = 0x20FFFFFF;
	}

	public static Theme byId(String id) {
		for (Theme t : ALL) {
			if (t.id.equals(id)) {
				return t;
			}
		}
		return DEFAULT;
	}

	int index() {
		return ALL.indexOf(this);
	}

	/** Left/right inset of lines drawn inside a panel (the bevel is thicker). */
	int inset() {
		return style == Style.BEVEL ? 4 : 3;
	}

	/** Panel background. {@code screen}: for the settings screen. */
	void frame(GuiGraphics g, int x, int y, int w, int h, boolean screen) {
		switch (style) {
			case FRAME -> {
				g.fill(x, y, x + w, y + h, edge);
				g.fill(x + 1, y + 1, x + w - 1, y + h - 1, border);
				g.fill(x + 2, y + 2, x + w - 2, y + h - 2, bg);
			}
			case TOOLTIP -> {
				// dark body with cut corners, then a purple border that fades downwards
				g.fill(x + 1, y, x + w - 1, y + h, bg);
				g.fill(x, y + 1, x + 1, y + h - 1, bg);
				g.fill(x + w - 1, y + 1, x + w, y + h - 1, bg);
				g.fillGradient(x + 1, y + 1, x + 2, y + h - 1, borderTop, borderBottom);
				g.fillGradient(x + w - 2, y + 1, x + w - 1, y + h - 1, borderTop, borderBottom);
				g.fill(x + 2, y + 1, x + w - 2, y + 2, borderTop);
				g.fill(x + 2, y + h - 2, x + w - 2, y + h - 1, borderBottom);
			}
			case BEVEL -> {
				g.fill(x + 1, y, x + w - 1, y + h, edge);
				g.fill(x, y + 1, x + 1, y + h - 1, edge);
				g.fill(x + w - 1, y + 1, x + w, y + h - 1, edge);
				g.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg);
				g.fill(x + 1, y + 1, x + w - 2, y + 3, light);
				g.fill(x + 1, y + 3, x + 3, y + h - 2, light);
				g.fill(x + 2, y + h - 3, x + w - 1, y + h - 1, dark);
				g.fill(x + w - 3, y + 2, x + w - 1, y + h - 3, dark);
			}
			case FLAT -> g.fill(x, y, x + w, y + h, screen ? screenBg : bg);
		}
	}

	/** An inventory-style item slot. */
	void slot(GuiGraphics g, int x, int y, int size) {
		g.fill(x, y, x + size - 1, y + 1, slotDark);
		g.fill(x, y + 1, x + 1, y + size - 1, slotDark);
		g.fill(x + size - 1, y, x + size, y + size, slotLight);
		g.fill(x, y + size - 1, x + size - 1, y + size, slotLight);
		g.fill(x + 1, y + 1, x + size - 1, y + size - 1, slot);
	}

	void text(GuiGraphics g, Font font, String s, int x, int y, int color) {
		g.drawString(font, s, x, y, color, shadow);
	}

	void textRight(GuiGraphics g, Font font, String s, int right, int y, int color) {
		g.drawString(font, s, right - font.width(s), y, color, shadow);
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
