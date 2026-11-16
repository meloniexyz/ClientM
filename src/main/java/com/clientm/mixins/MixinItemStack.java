package com.clientm.mixins;

import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.clientm.module.impl.GlintModule;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

/** hasEffect is a client-side rendering question only, so answering "no" hides item glint everywhere. */
@Mixin(ItemStack.class)
public abstract class MixinItemStack {

    // Asked for every drawn item each frame, so it must not make an object per call.
    @ModifyReturnValue(method = "hasEffect(I)Z", at = @At("RETURN"), remap = false)
    private boolean clientm$hideGlint(boolean original) {
        return original && !GlintModule.hidden();
    }
}
