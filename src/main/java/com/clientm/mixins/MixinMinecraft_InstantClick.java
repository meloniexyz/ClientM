package com.clientm.mixins;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.clientm.module.impl.HitFixModule;

/** Notes when the game carries out a left click (for Hit Fix's click test). */
@Mixin(Minecraft.class)
public abstract class MixinMinecraft_InstantClick {

    // func_147116_af: the left-click action (swing, then attack the entity or hit the block under the crosshair)
    @Inject(method = "func_147116_af", at = @At("HEAD"))
    private void clientm$noteClick(CallbackInfo ci) {
        HitFixModule.clickAction();
    }
}
