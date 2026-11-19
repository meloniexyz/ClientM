package com.clientm.mixins;

import net.minecraft.client.settings.KeyBinding;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.clientm.module.impl.HitFixModule;

/**
 * Hit Fix's instant clicks: an attack press already carried out on the frame it happened is not counted again when
 * the game tick reads it from the mouse queue, so every click attacks exactly once.
 */
@Mixin(KeyBinding.class)
public abstract class MixinKeyBinding_InstantClick {

    @Inject(method = "onTick", at = @At("HEAD"), cancellable = true)
    private static void clientm$skipHandledPress(int keyCode, CallbackInfo ci) {
        if (HitFixModule.skipTickPress(keyCode)) ci.cancel();
    }
}
