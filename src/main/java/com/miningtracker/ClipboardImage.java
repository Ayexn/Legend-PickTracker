package com.miningtracker;

import com.mojang.blaze3d.platform.NativeImage;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;

/**
 * Puts a picture on this computer's clipboard with Java's own clipboard (java.awt), so it can be pasted into a
 * chat program. Kept apart from the rest so the game never touches java.awt unless 복사 is pressed.
 */
final class ClipboardImage {
	private static final int TRIES = 3;

	private ClipboardImage() {
	}

	/** Throws when this computer has no clipboard for Java, or another program keeps it busy. */
	static void put(NativeImage image) throws InterruptedException {
		// the game turns Java's window toolkit off (it draws with GLFW); the clipboard needs it, so turn it on
		// before java.awt is first used (it decides once)
		System.setProperty("java.awt.headless", "false");
		int w = image.getWidth();
		int h = image.getHeight();
		BufferedImage picture = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		picture.setRGB(0, 0, w, h, image.getPixels(), 0, w);
		Transferable content = new Picture(picture);
		IllegalStateException busy = new IllegalStateException("clipboard busy");
		for (int i = 0; i < TRIES; i++) {
			try {
				Toolkit.getDefaultToolkit().getSystemClipboard().setContents(content, null);
				return;
			} catch (IllegalStateException e) {
				// another program has the clipboard open right now: try again in a moment
				busy = e;
				Thread.sleep(60);
			}
		}
		throw busy;
	}

	/** A picture for the clipboard. */
	private record Picture(BufferedImage image) implements Transferable {
		@Override
		public DataFlavor[] getTransferDataFlavors() {
			return new DataFlavor[]{DataFlavor.imageFlavor};
		}

		@Override
		public boolean isDataFlavorSupported(DataFlavor flavor) {
			return DataFlavor.imageFlavor.equals(flavor);
		}

		@Override
		public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
			if (!isDataFlavorSupported(flavor)) {
				throw new UnsupportedFlavorException(flavor);
			}
			return image;
		}
	}
}
