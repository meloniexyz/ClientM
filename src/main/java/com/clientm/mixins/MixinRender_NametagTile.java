package com.clientm.mixins;

import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.clientm.render.NametagCache;

/** Hands the standing-player and mob nametag to {@link NametagCache}; vanilla draws it when the cache declines. */
@Mixin(Render.class)
public abstract class MixinRender_NametagTile {

    @Shadow
    protected RenderManager renderManager;

    @Inject(method = "func_147906_a", at = @At("HEAD"), cancellable = true, require = 0)
    private void clientm$nametagFromImage(Entity entity, String name, double x, double y, double z, int maxDistance,
        CallbackInfo ci) {
        if (NametagCache.draw(renderManager, entity, name, x, y, z, maxDistance)) ci.cancel();
    }
}
