package com.clientm.mixins;

import net.minecraft.client.renderer.entity.RenderBiped;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.clientm.module.impl.GlintModule;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

/** Same as {@link MixinRenderPlayer}, for armour worn by mobs. */
@Mixin(RenderBiped.class)
public abstract class MixinRenderBiped {

    @ModifyReturnValue(method = "shouldRenderPass(Lnet/minecraft/entity/EntityLiving;IF)I", at = @At("RETURN"))
    private int clientm$hideArmourGlint(int original) {
        return original == 15 && GlintModule.hidden() ? 1 : original;
    }
}
