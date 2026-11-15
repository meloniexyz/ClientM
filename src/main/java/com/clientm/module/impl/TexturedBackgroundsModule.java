package com.clientm.module.impl;

import net.minecraft.client.renderer.texture.DynamicTexture;

import org.lwjgl.opengl.GL11;

import com.clientm.module.Category;
import com.clientm.module.Module;

/**
 * Draws flat background boxes (scoreboard, chat, HUD module backgrounds) as a tinted 1x1 white texture instead of
 * switching texturing off and on around each one. Keeping texturing on avoids a render-state change per box. The
 * colour still comes from the caller, so nothing looks different.
 *
 * Experimental and off by default: with HUD caching on, these boxes are only redrawn a few times a second anyway.
 */
public class TexturedBackgroundsModule extends Module {

    private static TexturedBackgroundsModule instance;
    private static DynamicTexture white;
    private static int previousTexture;
    private static boolean active;

    public TexturedBackgroundsModule() {
        super("texturedbg", "Textured Backgrounds", Category.PERFORMANCE, false);
        instance = this;
    }

    @Override
    public String description() {
        return "Experimental: fewer state changes for box backgrounds";
    }

    /** Called from the Gui.drawRect mixin right after vanilla switched texturing off. */
    public static void begin() {
        TexturedBackgroundsModule m = instance;
        if (m == null || !m.enabled) return;
        if (white == null) {
            white = new DynamicTexture(1, 1);
            white.getTextureData()[0] = 0xFFFFFFFF;
            white.updateDynamicTexture();
        }
        previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, white.getGlTextureId());
        active = true;
    }

    /** Called when drawRect returns: put back whatever texture the caller had bound. */
    public static void end() {
        if (!active) return;
        active = false;
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
    }
}
