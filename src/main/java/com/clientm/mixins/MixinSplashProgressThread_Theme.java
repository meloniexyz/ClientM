package com.clientm.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Forge multiplies the two pictures on its loading screen by the background colour, which suits a white screen and
 * turns everything invisible on a black one. The two pictures are drawn in their own colours instead.
 */
@Mixin(targets = "cpw.mods.fml.client.SplashProgress$3", remap = false)
public abstract class MixinSplashProgressThread_Theme {

    @ModifyArg(
        method = "run",
        at = @At(value = "INVOKE", target = "Lcpw/mods/fml/client/SplashProgress$3;setColor(I)V"),
        require = 0)
    private int clientm$picturesInOwnColours(int colour) {
        return 0xFFFFFF;
    }
}
