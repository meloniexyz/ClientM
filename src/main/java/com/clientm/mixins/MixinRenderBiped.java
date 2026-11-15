package com.clientm.mixins;

import net.minecraft.client.renderer.entity.RenderBiped;
import net.minecraft.entity.EntityLiving;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.clientm.module.impl.GlintModule;

/** Same as {@link MixinRenderPlayer}, for armour worn by mobs. */
@Mixin(RenderBiped.class)
public abstract class MixinRenderBiped {

    @Inject(method = "shouldRenderPass(Lnet/minecraft/entity/EntityLiving;IF)I", at = @At("RETURN"), cancellable = true)
    private void clientm$hideArmourGlint(EntityLiving entity, int pass, float partialTicks,
        CallbackInfoReturnable<Integer> cir) {
        if (cir.getReturnValueI() == 15 && GlintModule.hidden()) cir.setReturnValue(1);
    }
}
