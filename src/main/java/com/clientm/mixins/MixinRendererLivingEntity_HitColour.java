package com.clientm.mixins;

import net.minecraft.client.renderer.entity.RendererLivingEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.clientm.module.impl.HitColourModule;

/**
 * Angelica sets its red hit tint just before these draws. Running straight before each model draw lets
 * {@link HitColourModule} swap that red for the chosen colour without touching Angelica's own hooks.
 */
@Mixin(value = RendererLivingEntity.class, priority = 1100)
public abstract class MixinRendererLivingEntity_HitColour {

    @Inject(method = "renderModel(Lnet/minecraft/entity/EntityLivingBase;FFFFFF)V", at = @At("HEAD"), require = 0)
    private void clientm$hitColourBase(CallbackInfo ci) {
        HitColourModule.apply();
    }

    @Inject(
        method = "doRender(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/model/ModelBase;render(Lnet/minecraft/entity/Entity;FFFFFF)V"),
        require = 0)
    private void clientm$hitColourLayers(CallbackInfo ci) {
        HitColourModule.apply();
    }
}
