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
        if (!wanted(name.toLowerCase(java.util.Locale.ROOT), m.stained.value)) return;
        BufferedImage src = images[0];
        int w = src.getWidth();
        int h = src.getHeight();
        int edge = m.frame.value ? Math.max(1, w / 16) : 0;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        // Animated textures stack square frames vertically; treat each frame on its own.
        for (int top = 0; top + w <= h; top += w) {
            // A connected-glass piece only has a frame on the sides where the glass ends, so each side and each
            // corner is kept only when the picture really has a solid line there.
            boolean left = edge > 0 && solid(src, 0, top, 0, 1, w);
            boolean right = edge > 0 && solid(src, w - 1, top, 0, 1, w);
            boolean up = edge > 0 && solid(src, 0, top, 1, 0, w);
            boolean down = edge > 0 && solid(src, 0, top + w - 1, 1, 0, w);
            for (int fy = 0; fy < w; fy++) {
                for (int x = 0; x < w; x++) {
                    boolean nearLeft = x < edge, nearRight = x >= w - edge, nearUp = fy < edge,
                        nearDown = fy >= w - edge;
                    boolean keep = nearLeft && left || nearRight && right || nearUp && up || nearDown && down;
                    if (!keep && edge > 0 && (nearLeft || nearRight) && (nearUp || nearDown)) {
                        // An inner corner: just the corner block, when its outermost pixel is solid.
                        keep = opaque(src.getRGB(nearLeft ? 0 : w - 1, top + (nearUp ? 0 : w - 1)));
                    }
                    int rgb = src.getRGB(x, top + fy);
                    out.setRGB(x, top + fy, keep ? rgb : rgb & 0x00FFFFFF);
                }
            }
        }
        images[0] = out;
    }

    /**
     * Plain glass is "glass"; stained glass is "glass_red" and so on. Packs with connected glass add one picture per
     * piece under a "ctm" folder, for example "mcpatcher/ctm/glass/12". Panes are left alone.
     */
    private static boolean wanted(String name, boolean stainedToo) {
        if (name.startsWith("minecraft:")) name = name.substring(10);
        if (name.contains("pane")) return false;
        boolean connected = name.contains("ctm/") && name.contains("glass");
        boolean coloured = name.startsWith("glass_")
            || connected && (name.contains("glass_") || name.contains("stained"));
        boolean plain = name.equals("glass") || connected && !coloured;
        return plain || coloured && stainedToo;
    }

    /** True when most of one outermost line of a frame is filled in. */
    private static boolean solid(BufferedImage image, int x, int y, int stepX, int stepY, int length) {
        int filled = 0;
        for (int i = 0; i < length; i++) if (opaque(image.getRGB(x + i * stepX, y + i * stepY))) filled++;
        return filled * 10 >= length * 6;
    }

    private static boolean opaque(int argb) {
        return (argb >>> 24) >= 96;
    }
}
