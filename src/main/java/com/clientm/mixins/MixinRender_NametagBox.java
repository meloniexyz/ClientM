package com.clientm.mixins;

import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.Render;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.clientm.module.impl.TexturedBackgroundsModule;

/**
 * The dark box behind a nametag: lets the Textured Backgrounds module draw it with texturing left on, or
 * leave it out. The box is the only thing this method draws through the tessellator itself.
 */
@Mixin(Render.class)
public abstract class MixinRender_NametagBox {

    @Redirect(
        method = "func_147906_a",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;startDrawingQuads()V"),
        require = 0)
    private void clientm$beginNametagBox(Tessellator tessellator) {
        TexturedBackgroundsModule.begin();
        tessellator.startDrawingQuads();
    }

    @Redirect(
        method = "func_147906_a",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;addVertex(DDD)V"),
        require = 0)
    private void clientm$nametagBoxVertex(Tessellator tessellator, double x, double y, double z) {
        if (!TexturedBackgroundsModule.skipNametagBox()) tessellator.addVertex(x, y, z);
    }

    @Redirect(
        method = "func_147906_a",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;draw()I"),
        require = 0)
    private int clientm$endNametagBox(Tessellator tessellator) {
        int drawn = tessellator.draw();
        TexturedBackgroundsModule.end();
        return drawn;
    }
}
