package com.clientm.mixins;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.EntityLivingBase;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.clientm.render.NametagCache;

/** Tells the nametag sheet when the world's entity drawing starts and ends, so it can send all names together. */
@Mixin(RenderGlobal.class)
public abstract class MixinRenderGlobal_NametagBatch {

    @Inject(method = "renderEntities", at = @At("HEAD"), require = 0)
    private void clientm$beginNametags(EntityLivingBase viewer, ICamera camera, float partialTicks, CallbackInfo ci) {
        NametagCache.beginEntities();
    }

    @Inject(method = "renderEntities", at = @At("RETURN"), require = 0)
    private void clientm$endNametags(EntityLivingBase viewer, ICamera camera, float partialTicks, CallbackInfo ci) {
        NametagCache.endEntities();
    }
}
