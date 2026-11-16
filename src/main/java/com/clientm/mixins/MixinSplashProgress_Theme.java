package com.clientm.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import cpw.mods.fml.client.SplashProgress;

/**
 * Gives Forge's loading screen the ClientM look: black background, light text, red progress bars and the ClientM
 * wordmark in place of the Mojang logo. Forge reads these values from config/splash.properties; they are answered
 * here instead, so the look is the same on a fresh install and on one that already has that file.
 */
@Mixin(value = SplashProgress.class, remap = false)
public abstract class MixinSplashProgress_Theme {

    private static final String LOGO = "clientm:textures/gui/splash_logo.png";
    private static final String CORNER = "clientm:textures/gui/splash_corner.png";

    @Shadow
    private static String getString(String name, String def) {
        throw new AssertionError();
    }

    @Redirect(
        method = "start",
        at = @At(value = "INVOKE", target = "Lcpw/mods/fml/client/SplashProgress;getHex(Ljava/lang/String;I)I"),
        require = 0)
    private static int clientm$colours(String name, int def) {
        if ("background".equals(name)) return 0x000000;
        if ("font".equals(name)) return 0xEDEDF2;
        if ("barBorder".equals(name)) return 0x22222C;
        if ("bar".equals(name)) return 0xFF4D5E;
        if ("barBackground".equals(name)) return 0x0B0B0F;
        return def;
    }

    @Redirect(
        method = "start",
        at = @At(
            value = "INVOKE",
            target = "Lcpw/mods/fml/client/SplashProgress;getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
        require = 0)
    private static String clientm$textures(String name, String def) {
        // Only when the pictures can really be found: a missing one would stop the game from starting.
        if ("logoTexture".equals(name) && clientm$present("splash_logo.png")) return LOGO;
        if ("forgeTexture".equals(name) && clientm$present("splash_corner.png")) return CORNER;
        return getString(name, def);
    }

    private static boolean clientm$present(String file) {
        return SplashProgress.class.getResource("/assets/clientm/textures/gui/" + file) != null;
    }
}
