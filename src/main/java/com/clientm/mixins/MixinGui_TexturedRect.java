package com.clientm.mixins;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.Tessellator;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.clientm.module.impl.TexturedBackgroundsModule;

/**
 * Hooks the start of the quad inside Gui.drawRect (a tessellator call, which other mods do not rewrite) so the
 * Textured Backgrounds module can keep texturing on for it.
 */
@Mixin(Gui.class)
public abstract class MixinGui_TexturedRect {

    @Redirect(
        method = "drawRect",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;startDrawingQuads()V"),
        require = 0)
    private static void clientm$beginTexturedRect(Tessellator tessellator) {
        TexturedBackgroundsModule.begin();
        tessellator.startDrawingQuads();
    }

    @Inject(method = "drawRect", at = @At("RETURN"), require = 0)
    private static void clientm$endTexturedRect(int left, int top, int right, int bottom, int color, CallbackInfo ci) {
        TexturedBackgroundsModule.end();
    }
}
