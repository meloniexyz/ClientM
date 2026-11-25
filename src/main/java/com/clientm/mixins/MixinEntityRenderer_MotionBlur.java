package com.clientm.mixins;

import net.minecraft.client.renderer.EntityRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.clientm.module.impl.MotionBlurModule;

/** Motion blur runs right after the world and the hand are drawn, before the HUD (both renderWorld calls). */
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRenderer_MotionBlur {

    @Inject(
        method = "updateCameraAndRender",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/EntityRenderer;renderWorld(FJ)V",
            shift = At.Shift.AFTER),
        require = 0)
    private void clientm$motionBlur(float partialTicks, CallbackInfo ci) {
        MotionBlurModule.afterWorld();
    }
}
