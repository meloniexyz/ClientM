package com.clientm.mixins;

import net.minecraft.client.renderer.entity.RenderPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.clientm.module.impl.GlintModule;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

/** Vanilla returns 15 for "armour piece with glint" and 1 for "armour piece without"; downgrade when hidden. */
@Mixin(RenderPlayer.class)
public abstract class MixinRenderPlayer {

    // Runs for every armour piece of every player each frame, so it must not make an object per call.
    @ModifyReturnValue(
        method = "shouldRenderPass(Lnet/minecraft/client/entity/AbstractClientPlayer;IF)I",
        at = @At("RETURN"))
    private int clientm$hideArmourGlint(int original) {
        return original == 15 && GlintModule.hidden() ? 1 : original;
    }
}
