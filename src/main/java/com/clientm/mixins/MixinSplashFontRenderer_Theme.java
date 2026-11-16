package com.clientm.mixins;

import net.minecraft.client.gui.FontRenderer;

import org.spongepowered.asm.mixin.Mixin;

/**
 * Every line of text on Forge's loading screen is asked for in black, whatever the configured font colour says. On
 * the black ClientM loading screen it is drawn in the light text colour instead.
 */
@Mixin(targets = "cpw.mods.fml.client.SplashProgress$SplashFontRenderer")
public abstract class MixinSplashFontRenderer_Theme extends FontRenderer {

    private MixinSplashFontRenderer_Theme() {
        super(null, null, null, false);
    }

    @Override
    public int drawString(String text, int x, int y, int colour) {
        return super.drawString(text, x, y, 0xEDEDF2);
    }
}
