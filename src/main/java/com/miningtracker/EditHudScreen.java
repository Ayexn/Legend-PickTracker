package com.miningtracker;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Drag the board and the toasts to move them, scroll over them to resize. ESC saves. */
public final class EditHudScreen extends Screen {
	private enum Target { NONE, BOARD, TOASTS }

	/** Screen to go back to when closing (the settings screen), or null to go back to the game. */
	private final Screen parent;
	private final List<ToastManager.Toast> sampleToasts = HudRenderer.sampleToasts();
	private Target dragging = Target.NONE;
	private double grabX;
	private double grabY;
	private float[] boardRect = new float[4];
	private float[] toastRect = new float[4];

	public EditHudScreen(Screen parent) {
		super(Component.literal("광질 측정 HUD 편집"));
		this.parent = parent;
	}

	private static boolean isBar(ModConfig cfg) {
		return cfg.hudLayout() == HudLayout.BAR;
	}

	private static float boardScale(ModConfig cfg) {
		return isBar(cfg) ? cfg.barScale : cfg.panelScale;
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		// no blur: the point is to see the game behind
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		g.fill(0, 0, this.width, this.height, 0x40000000);

		ModConfig cfg = ModConfig.get();
		Theme theme = cfg.currentTheme();
		Tracker tracker = Tracker.INSTANCE;
		HudRenderer.Panel panel = tracker.isEmpty() ? HudRenderer.samplePanel() : HudRenderer.fromTracker(tracker, cfg);
		boardRect = HudRenderer.drawBoard(g, this.font, panel, cfg, theme);
		toastRect = HudRenderer.drawToastsStatic(g, this.font, sampleToasts, cfg, theme);

		outline(g, boardRect, color(Target.BOARD, boardRect, mouseX, mouseY));
		outline(g, toastRect, color(Target.TOASTS, toastRect, mouseX, mouseY));
		label(g, "집계판 " + percent(boardScale(cfg)), boardRect);
		label(g, "획득 알림 " + percent(cfg.toastScale), toastRect);

		int cx = this.width / 2;
		int top = isBar(cfg) ? Math.max(8, (int) (boardRect[1] + boardRect[3]) + 18) : 8;
		g.drawCenteredString(this.font, "끌어서 위치 이동  ·  마우스 휠로 크기 조절", cx, top, 0xFFFFFFFF);
		g.drawCenteredString(this.font, parent != null ? "R: 기본 위치로  ·  ESC: 저장하고 설정으로" : "R: 기본 위치로  ·  ESC: 저장하고 닫기",
				cx, top + 12, 0xFFB8C0CC);
	}

	private int color(Target target, float[] rect, int mouseX, int mouseY) {
		if (dragging == target) {
			return 0xFFFFD866;
		}
		return inside(rect, mouseX, mouseY) ? 0xFFFFFFFF : 0xFF8FB4FF;
	}

	private void label(GuiGraphics g, String text, float[] rect) {
		int x = (int) rect[0];
		int y = (int) rect[1] - 11;
		if (y < 2) {
			y = (int) (rect[1] + rect[3]) + 3;
		}
		g.drawString(this.font, text, x, y, 0xFFFFFFFF, true);
	}

	private static void outline(GuiGraphics g, float[] r, int color) {
		int x1 = (int) Math.floor(r[0]) - 1;
		int y1 = (int) Math.floor(r[1]) - 1;
		int x2 = (int) Math.ceil(r[0] + r[2]) + 1;
		int y2 = (int) Math.ceil(r[1] + r[3]) + 1;
		g.fill(x1, y1, x2, y1 + 1, color);
		g.fill(x1, y2 - 1, x2, y2, color);
		g.fill(x1, y1, x1 + 1, y2, color);
		g.fill(x2 - 1, y1, x2, y2, color);
	}

	private static boolean inside(float[] r, double x, double y) {
		return x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3];
	}

	private static String percent(float scale) {
		return Math.round(scale / ModConfig.DEFAULT_SCALE * 100f) + "%";
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0) {
			if (inside(boardRect, mouseX, mouseY)) {
				dragging = Target.BOARD;
				grabX = mouseX - boardRect[0];
				grabY = mouseY - boardRect[1];
				return true;
			}
			if (inside(toastRect, mouseX, mouseY)) {
				dragging = Target.TOASTS;
				grabX = mouseX - toastRect[0];
				grabY = mouseY - toastRect[1];
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		ModConfig cfg = ModConfig.get();
		if (dragging == Target.BOARD) {
			float x = (float) (mouseX - grabX);
			float y = (float) (mouseY - grabY);
			if (isBar(cfg)) {
				cfg.barCenterX = x + boardRect[2] / 2f;
				cfg.barY = y;
			} else {
				cfg.panelX = x;
				cfg.panelY = y;
			}
			return true;
		}
		if (dragging == Target.TOASTS) {
			float rightEdge = (float) (mouseX - grabX) + toastRect[2];
			cfg.toastRight = this.width - rightEdge;
			cfg.toastY = (float) (mouseY - grabY);
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (dragging != Target.NONE) {
			ModConfig cfg = ModConfig.get();
			// keep what was actually drawn (it is kept on the screen)
			if (dragging == Target.BOARD) {
				if (isBar(cfg)) {
					cfg.barCenterX = boardRect[0] + boardRect[2] / 2f;
					cfg.barY = boardRect[1];
				} else {
					cfg.panelX = boardRect[0];
					cfg.panelY = boardRect[1];
				}
			} else {
				cfg.toastRight = this.width - (toastRect[0] + toastRect[2]);
				cfg.toastY = toastRect[1];
			}
			dragging = Target.NONE;
			return true;
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY == 0) {
			return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		}
		float step = scrollY > 0 ? 0.05f : -0.05f;
		ModConfig cfg = ModConfig.get();
		if (inside(boardRect, mouseX, mouseY)) {
			if (isBar(cfg)) {
				cfg.barScale = ModConfig.clampScale(cfg.barScale + step);
			} else {
				cfg.panelScale = ModConfig.clampScale(cfg.panelScale + step);
			}
			return true;
		}
		if (inside(toastRect, mouseX, mouseY)) {
			cfg.toastScale = ModConfig.clampScale(cfg.toastScale + step);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_R) {
			ModConfig.get().resetLayout();
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
