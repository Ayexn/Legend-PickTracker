package com.miningtracker;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Drag the board and the toasts to move them; drag their border or a corner to resize them (the opposite side stays
 * put), or scroll over them. ESC saves.
 */
public final class EditHudScreen extends Screen {
	private enum Target { NONE, BOARD, TOASTS }

	/** How close to a box's frame the mouse takes the frame (resize) instead of the box (move). */
	private static final int EDGE = 4;

	/** Screen to go back to when closing (the settings screen), or null to go back to the game. */
	private final Screen parent;
	private final List<ToastManager.Toast> sampleToasts = HudRenderer.sampleToasts();
	private Target dragging = Target.NONE;
	private double grabX;
	private double grabY;
	private float[] boardRect = new float[4];
	private float[] toastRect = new float[4];
	/** The box whose frame is being dragged, and which sides: -1 left/top, 1 right/bottom, 0 neither. */
	private Target resizing = Target.NONE;
	private int sideX;
	private int sideY;
	/** The box and its size when the frame was taken, and how far from the dragged side the mouse was. */
	private float[] startRect;
	private float startScale;
	private double offsetX;
	private double offsetY;

	public EditHudScreen(Screen parent) {
		super(Component.literal("픽트래커 HUD 편집"));
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

		int boardColor = color(theme, Target.BOARD, boardRect, mouseX, mouseY);
		int toastColor = color(theme, Target.TOASTS, toastRect, mouseX, mouseY);
		outline(g, boardRect, boardColor);
		outline(g, toastRect, toastColor);
		handles(g, Target.BOARD, boardRect, boardColor, mouseX, mouseY);
		handles(g, Target.TOASTS, toastRect, toastColor, mouseX, mouseY);
		label(g, theme, "집계판 · " + percent(boardScale(cfg)), boardRect, boardColor);
		label(g, theme, "획득 알림 · " + percent(cfg.toastScale), toastRect, toastColor);

		String hint1 = "끌어서 위치 이동  ·  테두리나 모서리를 끌어서 크기 조절";
		String hint2 = (parent != null ? "휠로도 크기 조절  ·  R: 기본 위치로  ·  ESC: 저장하고 설정으로"
				: "휠로도 크기 조절  ·  R: 기본 위치로  ·  ESC: 저장하고 닫기");
		int hw = Math.max(this.font.width(hint1), this.font.width(hint2)) + 16;
		int hx = (this.width - hw) / 2;
		int top = isBar(cfg) ? Math.max(6, (int) (boardRect[1] + boardRect[3]) + 16) : 6;
		theme.panel(g, hx, top, hw, 26, true);
		g.drawCenteredString(this.font, hint1, this.width / 2, top + 4, theme.text);
		g.drawCenteredString(this.font, hint2, this.width / 2, top + 15, theme.dim);
	}

	/** Accent colour; lighter under the mouse, white while dragging. */
	private int color(Theme theme, Target target, float[] rect, int mouseX, int mouseY) {
		if (dragging == target || resizing == target) {
			return 0xFFFFFFFF;
		}
		boolean busy = dragging != Target.NONE || resizing != Target.NONE;
		boolean over = inside(rect, mouseX, mouseY) || edgeAt(rect, mouseX, mouseY) != null;
		return !busy && over ? Theme.mix(theme.accent, 0xFFFFFFFF, 0.5f) : theme.accent;
	}

	/**
	 * Small squares on the four corners of the frame, to show it can be pulled. The side under the mouse (or being
	 * pulled) is drawn thicker, in white.
	 */
	private void handles(GuiGraphics g, Target target, float[] r, int color, int mouseX, int mouseY) {
		int x1 = (int) Math.floor(r[0]) - 2;
		int y1 = (int) Math.floor(r[1]) - 2;
		int x2 = (int) Math.ceil(r[0] + r[2]) + 2;
		int y2 = (int) Math.ceil(r[1] + r[3]) + 2;
		int[] side = resizing == target ? new int[]{sideX, sideY}
				: dragging == Target.NONE && resizing == Target.NONE ? edgeAt(r, mouseX, mouseY) : null;
		if (side != null) {
			int white = 0xFFFFFFFF;
			if (side[0] < 0) {
				g.fill(x1 - 1, y1, x1 + 1, y2, white);
			} else if (side[0] > 0) {
				g.fill(x2 - 1, y1, x2 + 1, y2, white);
			}
			if (side[1] < 0) {
				g.fill(x1, y1 - 1, x2, y1 + 1, white);
			} else if (side[1] > 0) {
				g.fill(x1, y2 - 1, x2, y2 + 1, white);
			}
		}
		for (int[] c : new int[][]{{x1, y1, -1, -1}, {x2 - 1, y1, 1, -1}, {x1, y2 - 1, -1, 1}, {x2 - 1, y2 - 1, 1, 1}}) {
			boolean hot = side != null && side[0] == c[2] && side[1] == c[3];
			int size = hot ? 3 : 2;
			g.fill(c[0] - size, c[1] - size, c[0] + size + 1, c[1] + size + 1, hot ? 0xFFFFFFFF : color);
		}
	}

	/**
	 * Which side of the frame the mouse is on: {x, y} with -1 for left/top, 1 for right/bottom, 0 for neither
	 * (both set on a corner), or null when it is not on the frame.
	 */
	private static int[] edgeAt(float[] r, double x, double y) {
		double left = r[0] - 2;
		double top = r[1] - 2;
		double right = r[0] + r[2] + 2;
		double bottom = r[1] + r[3] + 2;
		if (r[2] <= 0 || x < left - EDGE || x > right + EDGE || y < top - EDGE || y > bottom + EDGE) {
			return null;
		}
		double dl = Math.abs(x - left);
		double dr = Math.abs(x - right);
		double dt = Math.abs(y - top);
		double db = Math.abs(y - bottom);
		int sx = Math.min(dl, dr) > EDGE ? 0 : dl <= dr ? -1 : 1;
		int sy = Math.min(dt, db) > EDGE ? 0 : dt <= db ? -1 : 1;
		return sx == 0 && sy == 0 ? null : new int[]{sx, sy};
	}

	/**
	 * A small name tag above the box (below it when there is no room above), at its left end, or at its right
	 * end for a box on the right half of the screen, and never past the edge of the screen.
	 */
	private void label(GuiGraphics g, Theme theme, String text, float[] rect, int color) {
		int w = this.font.width(text) + 8;
		int left = (int) Math.floor(rect[0]) - 2;
		int right = (int) Math.ceil(rect[0] + rect[2]) + 2;
		int x = rect[0] + rect[2] / 2f > this.width / 2f ? right - w : left;
		x = Math.max(2, Math.min(x, this.width - w - 2));
		int y = (int) rect[1] - 15;
		if (y < 2) {
			y = (int) (rect[1] + rect[3]) + 4;
		}
		Theme.rrect(g, x, y, w, 11, color, 1);
		g.drawString(this.font, text, x + 4, y + 2, Theme.readableOn(color), false);
	}

	private static void outline(GuiGraphics g, float[] r, int color) {
		int x1 = (int) Math.floor(r[0]) - 2;
		int y1 = (int) Math.floor(r[1]) - 2;
		int x2 = (int) Math.ceil(r[0] + r[2]) + 2;
		int y2 = (int) Math.ceil(r[1] + r[3]) + 2;
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

	private float[] rectOf(Target target) {
		return target == Target.BOARD ? boardRect : toastRect;
	}

	private float scaleOf(Target target) {
		ModConfig cfg = ModConfig.get();
		return target == Target.BOARD ? boardScale(cfg) : cfg.toastScale;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0) {
			// the frame first: pressing on it resizes
			for (Target target : new Target[]{Target.BOARD, Target.TOASTS}) {
				float[] r = rectOf(target);
				int[] side = edgeAt(r, mouseX, mouseY);
				if (side != null) {
					resizing = target;
					sideX = side[0];
					sideY = side[1];
					startRect = r.clone();
					startScale = scaleOf(target);
					offsetX = mouseX - (sideX < 0 ? r[0] : r[0] + r[2]);
					offsetY = mouseY - (sideY < 0 ? r[1] : r[1] + r[3]);
					return true;
				}
			}
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
		if (resizing != Target.NONE) {
			resize(cfg, mouseX - offsetX, mouseY - offsetY);
			return true;
		}
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

	/**
	 * The pulled side (or corner) is now at {@code edgeX}, {@code edgeY}: the box takes the size that puts it there
	 * (the same shape, so a corner follows the farther of the two), and the opposite side stays where it was.
	 */
	private void resize(ModConfig cfg, double edgeX, double edgeY) {
		float[] r0 = startRect;
		double fx = sideX > 0 ? (edgeX - r0[0]) / r0[2] : sideX < 0 ? (r0[0] + r0[2] - edgeX) / r0[2] : 0;
		double fy = sideY > 0 ? (edgeY - r0[1]) / r0[3] : sideY < 0 ? (r0[1] + r0[3] - edgeY) / r0[3] : 0;
		double factor = sideX != 0 && sideY != 0 ? Math.max(fx, fy) : sideX != 0 ? fx : fy;
		float scale = ModConfig.stepScale((float) (startScale * factor)); // in 5% steps
		float k = scale / startScale;
		float w = r0[2] * k;
		float h = r0[3] * k;
		float left = sideX < 0 ? r0[0] + r0[2] - w : r0[0];
		float top = sideY < 0 ? r0[1] + r0[3] - h : r0[1];
		if (resizing == Target.BOARD) {
			if (isBar(cfg)) {
				cfg.barScale = scale;
				// the bar is placed by its middle: keep the side that is not pulled where it was
				cfg.barCenterX = sideX == 0 ? r0[0] + r0[2] / 2f : left + w / 2f;
				cfg.barY = top;
			} else {
				cfg.panelScale = scale;
				cfg.panelX = left;
				cfg.panelY = top;
			}
		} else {
			cfg.toastScale = scale;
			cfg.toastRight = this.width - (left + w);
			cfg.toastY = top;
		}
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (resizing != Target.NONE) {
			dragging = resizing; // keep what was drawn, as after a move
			resizing = Target.NONE;
		}
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
		float step = (scrollY > 0 ? 0.05f : -0.05f) * ModConfig.DEFAULT_SCALE; // 5%
		ModConfig cfg = ModConfig.get();
		if (inside(boardRect, mouseX, mouseY)) {
			if (isBar(cfg)) {
				cfg.barScale = ModConfig.stepScale(cfg.barScale + step);
			} else {
				cfg.panelScale = ModConfig.stepScale(cfg.panelScale + step);
			}
			return true;
		}
		if (inside(toastRect, mouseX, mouseY)) {
			cfg.toastScale = ModConfig.stepScale(cfg.toastScale + step);
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
