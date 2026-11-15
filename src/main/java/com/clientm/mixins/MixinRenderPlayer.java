package com.clientm.mixins;

import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.RenderPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.clientm.module.impl.GlintModule;

/** Vanilla returns 15 for "armour piece with glint" and 1 for "armour piece without"; downgrade when hidden. */
@Mixin(RenderPlayer.class)
public abstract class MixinRenderPlayer {

    @Inject(
        method = "shouldRenderPass(Lnet/minecraft/client/entity/AbstractClientPlayer;IF)I",
        at = @At("RETURN"),
        cancellable = true)
    private void clientm$hideArmourGlint(AbstractClientPlayer player, int pass, float partialTicks,
        CallbackInfoReturnable<Integer> cir) {
        if (cir.getReturnValueI() == 15 && GlintModule.hidden()) cir.setReturnValue(1);
    }
}
