package com.clientm.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.clientm.module.impl.CustomSkiesModule;

/** Angelica's pack-sky renderer decides each frame whether a custom sky is active; the Custom Skies switch can veto. */
@Pseudo
@Mixin(targets = "com.prupe.mcpatcher.sky.SkyRenderer", remap = false)
public abstract class MixinSkyRenderer_Toggle {

    @Shadow
    public static boolean active;

    @Inject(method = "setup", at = @At("RETURN"), require = 0)
    private static void clientm$customSkiesSwitch(CallbackInfo ci) {
        if (!CustomSkiesModule.active()) active = false;
    }
}
