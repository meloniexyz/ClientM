package com.clientm.mixins;

import net.minecraft.client.renderer.EntityRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.clientm.module.impl.ZoomModule;

/** Zoom: narrows the world's field of view while the zoom key is held (the held item is drawn as usual). */
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRenderer_Zoom {

    @Inject(method = "getFOVModifier", at = @At("RETURN"), cancellable = true)
    private void clientm$zoom(float partialTicks, boolean useFovSetting, CallbackInfoReturnable<Float> cir) {
        // The world's view asks with the FOV setting; the held item asks without it and keeps its look.
        if (useFovSetting) cir.setReturnValue(ZoomModule.fov(cir.getReturnValue()));
    }
}
