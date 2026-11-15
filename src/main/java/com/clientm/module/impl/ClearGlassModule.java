package com.clientm.module.impl;

import java.awt.image.BufferedImage;

import com.clientm.gui.ClientScreen;
import com.clientm.module.Category;
import com.clientm.module.Module;
import com.clientm.setting.BoolSetting;

/**
 * Clear glass on top of whatever resource pack is active: when the block textures are loaded, the inside of the
 * glass texture is made fully transparent and only its outer frame is kept. Nothing is done per frame; the price
 * is one resource reload when the option is switched. See MixinTextureAtlasSprite_ClearGlass.
 */
public class ClearGlassModule extends Module {

    private static ClearGlassModule instance;

    private final BoolSetting stained = add(new BoolSetting("stained", "Stained glass too", false));
    private final BoolSetting frame = add(new BoolSetting("frame", "Keep the frame", true));

    public ClearGlassModule() {
        super("clearglass", "Clear Glass", Category.VISUAL, false);
        instance = this;
    }

    @Override
    public String description() {
        return "Glass without streaks, on any pack";
    }

    @Override
    public void onEnable() {
        reload();
    }

    @Override
    public void onDisable() {
        reload();
    }

    @Override
    public void onSettingsChanged() {
        reload();
    }

    /** Textures are only rebuilt when the change was made in the menu, not while settings load at startup. */
    private void reload() {
        if (mc.currentScreen instanceof ClientScreen) mc.refreshResources();
    }

    /** Called for every block and item texture as it is loaded; replaces the image of glass textures. */
    public static void process(String name, BufferedImage[] images) {
        ClearGlassModule m = instance;
        if (m == null || !m.enabled || name == null || images == null || images.length == 0 || images[0] == null)
            return;
        if (name.startsWith("minecraft:")) name = name.substring(10);
        if (!name.equals("glass") && !(m.stained.value && name.startsWith("glass_") && !name.contains("pane"))) return;
        BufferedImage src = images[0];
        int w = src.getWidth();
        int h = src.getHeight();
        int edge = m.frame.value ? Math.max(1, w / 16) : 0;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            // Animated textures stack square frames vertically; treat each frame on its own.
            int fy = y % w;
            for (int x = 0; x < w; x++) {
                boolean onFrame = x < edge || fy < edge || x >= w - edge || fy >= w - edge;
                out.setRGB(x, y, onFrame ? src.getRGB(x, y) : src.getRGB(x, y) & 0x00FFFFFF);
            }
        }
        images[0] = out;
    }
}
