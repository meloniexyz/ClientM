package com.clientm.module.impl;

import org.lwjgl.opengl.GL11;

import com.clientm.ClientM;
import com.clientm.module.Category;
import com.clientm.module.Module;
import com.clientm.setting.BoolSetting;
import com.clientm.setting.IntSetting;

/** Recolours the enchantment glint, or hides it completely. The mixins in com.clientm.mixins read this. */
public class GlintModule extends Module {

    private static GlintModule instance;
    private static boolean hookLogged;

    private final BoolSetting show = add(new BoolSetting("show", "Show glint", true));
    private final BoolSetting custom = add(new BoolSetting("custom", "Custom colour", false));
    private final IntSetting red = add(new IntSetting("red", "Red", 128, 0, 255));
    private final IntSetting green = add(new IntSetting("green", "Green", 64, 0, 255));
    private final IntSetting blue = add(new IntSetting("blue", "Blue", 204, 0, 255));
    private final BoolSetting single = add(new BoolSetting("single", "Single layer on armour (faster)", false));
    private final IntSetting strength = add(new IntSetting("strength", "Strength %", 100, 10, 200));

    public GlintModule() {
        super("glint", "Enchant Glint", Category.VISUAL, true);
        instance = this;
    }

    @Override
    public String description() {
        return "Recolour the enchantment glint or turn it off";
    }

    @Override
    public int previewColor() {
        if (!show.value) return -1;
        return custom.value ? (red.value << 16) | (green.value << 8) | blue.value : 0x8040CC;
    }

    /** Used by the video presets: hides the glint (forcing the module on) or shows it again. */
    public static void setShown(boolean shown) {
        GlintModule m = instance;
        if (m == null || m.show.value == shown) return;
        m.show.value = shown;
        if (!shown) m.enabled = true;
    }

    /** How many times worn armour is drawn again for the glint: vanilla 2, or 1 with the faster option. */
    public static int armourLayers() {
        GlintModule m = instance;
        return m != null && m.enabled && m.single.value ? 1 : 2;
    }

    /** True when glint should not be drawn at all. */
    public static boolean hidden() {
        GlintModule m = instance;
        return m != null && m.enabled && !m.show.value;
    }

    /**
     * Stands in for the vanilla glint colour call. Vanilla passes (0.5, 0.25, 0.8) times a brightness factor, so the
     * factor is recovered from the red channel and applied to the custom colour.
     */
    public static void color(float r, float g, float b, float a) {
        GlintModule m = instance;
        if (!hookLogged) {
            hookLogged = true;
            ClientM.LOG.info("Glint colour hook is active");
        }
        if (m == null || !m.enabled || !m.custom.value) {
            GL11.glColor4f(r, g, b, a);
            return;
        }
        float factor = r * 2.0F * (m.strength.value / 100.0F);
        GL11.glColor4f(
            m.red.value / 255.0F * factor,
            m.green.value / 255.0F * factor,
            m.blue.value / 255.0F * factor,
            a);
    }
}
