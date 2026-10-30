package com.miningtracker.mixin;

import com.miningtracker.StatsImage;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 광질 통계 → 복사: the game sends every bit of screen drawing to "the main target" (the screen). While the picture
 * is being drawn (a moment, on the render thread), the main target is the picture's own buffer instead, so the
 * picture gets the drawing and the screen does not. At all other times this does nothing.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "getMainRenderTarget", at = @At("HEAD"), cancellable = true)
	private void miningTracker$pictureTarget(CallbackInfoReturnable<RenderTarget> cir) {
		RenderTarget picture = StatsImage.drawingInto();
		if (picture != null) {
			cir.setReturnValue(picture);
		}
	}
}
