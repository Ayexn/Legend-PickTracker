package com.miningtracker;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;

import java.io.File;
import java.util.function.Consumer;

/**
 * 광질 통계 → 복사: draws the statistics panel on its own, two picture pixels for each panel pixel, so the picture
 * is the same on every screen and has nothing of the game around it. It goes to the clipboard (paste with Ctrl+V);
 * where that does not work (macOS, or the clipboard refuses) it is saved to the screenshots folder instead.
 * Nothing leaves this computer.
 */
public final class StatsImage {
	/** Picture pixels for each panel pixel. */
	static final int SCALE = 2;
	/** The picture's buffer while it is being drawn, else null (see MinecraftMixin). */
	private static RenderTarget drawingInto;

	/** How it went: on the clipboard, or saved as {@code file}, or neither. */
	record Outcome(boolean copied, File file) {
	}

	private StatsImage() {
	}

	/**
	 * Where the game's drawing should go instead of the screen, or null (nearly always). The game sends all its
	 * screen drawing to the main target; MinecraftMixin hands out this buffer as the main target meanwhile.
	 */
	public static RenderTarget drawingInto() {
		return drawingInto;
	}

	/**
	 * Draws the picture now (on the render thread, while the screen is being drawn) and hands it over to the
	 * clipboard on a thread of its own; {@code done} hears how it went, back on the game's thread.
	 */
	static void copy(StatsScreen screen, Consumer<Outcome> done) {
		Minecraft mc = Minecraft.getInstance();
		NativeImage image;
		try {
			image = draw(mc, screen);
		} catch (Exception | LinkageError e) {
			MiningTrackerClient.LOGGER.warn("광질 통계 그림을 그리지 못했어요", e);
			done.accept(new Outcome(false, null));
			return;
		}
		Thread thread = new Thread(() -> {
			Outcome outcome = deliver(mc, image);
			mc.execute(() -> done.accept(outcome));
		}, "광질 측정 복사");
		thread.setDaemon(true);
		thread.start();
	}

	/** The panel drawn into a buffer of its own (not the screen), read back as a picture. */
	private static NativeImage draw(Minecraft mc, StatsScreen screen) {
		int w = screen.panelWidth() * SCALE;
		int h = screen.imageHeight() * SCALE;
		TextureTarget target = new TextureTarget(w, h, true);
		RenderSystem.backupProjectionMatrix();
		try {
			target.setClearColor(0f, 0f, 0f, 0f);
			target.clear();
			// from here the game draws into the picture, not the screen
			drawingInto = target;
			target.bindWrite(true);
			// like the game's own screen drawing, but one unit is one pixel of the picture
			RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0f, w, h, 0f, 1000f, 21000f), ProjectionType.ORTHOGRAPHIC);
			GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
			try {
				g.pose().pushPose();
				g.pose().scale(SCALE, SCALE, 1f);
				screen.drawForImage(g);
				g.pose().popPose();
			} finally {
				// into the picture's buffer, even if drawing stopped half way
				g.flush();
			}
			return Screenshot.takeScreenshot(target);
		} finally {
			drawingInto = null;
			RenderSystem.restoreProjectionMatrix();
			target.destroyBuffers();
			mc.getMainRenderTarget().bindWrite(true);
		}
	}

	/** Clipboard first; the screenshots folder when there is no clipboard to use. */
	private static Outcome deliver(Minecraft mc, NativeImage image) {
		try {
			int[] argb = image.getPixels();
			if (blank(argb)) {
				// nothing was drawn into it: better no picture than an empty one
				MiningTrackerClient.LOGGER.warn("광질 통계 그림이 비어 있어서 복사하지 않았어요");
				return new Outcome(false, null);
			}
			if (!Minecraft.ON_OSX) {
				try {
					ClipboardImage.put(argb, image.getWidth(), image.getHeight());
					return new Outcome(true, null);
				} catch (Throwable e) {
					// no clipboard for Java here, or another program holds it
					MiningTrackerClient.LOGGER.warn("광질 통계 그림을 클립보드에 넣지 못했어요: {}", e.toString());
				}
			}
			File dir = new File(mc.gameDirectory, Screenshot.SCREENSHOT_DIR);
			if (!dir.isDirectory() && !dir.mkdirs()) {
				throw new java.io.IOException("폴더를 만들지 못했어요: " + dir);
			}
			File file = new File(dir, "mining-stats_" + Util.getFilenameFormattedDateTime() + ".png");
			image.writeToFile(file);
			return new Outcome(false, file);
		} catch (Exception e) {
			MiningTrackerClient.LOGGER.warn("광질 통계 그림을 저장하지 못했어요", e);
			return new Outcome(false, null);
		} finally {
			image.close();
		}
	}

	/** True if every pixel is the same colour. */
	private static boolean blank(int[] argb) {
		for (int c : argb) {
			if (c != argb[0]) {
				return false;
			}
		}
		return true;
	}
}
