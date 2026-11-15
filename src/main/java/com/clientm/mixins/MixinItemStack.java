package com.clientm.mixins;

import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.clientm.module.impl.GlintModule;

/** hasEffect is a client-side rendering question only, so answering "no" hides item glint everywhere. */
@Mixin(ItemStack.class)
public abstract class MixinItemStack {

    @Inject(method = "hasEffect(I)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void clientm$hideGlint(int pass, CallbackInfoReturnable<Boolean> cir) {
        if (GlintModule.hidden()) cir.setReturnValue(false);
    }
}
