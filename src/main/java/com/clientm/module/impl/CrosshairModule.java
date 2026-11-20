package com.clientm.module.impl;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;

import com.clientm.module.Category;
import com.clientm.module.Module;
import com.clientm.setting.BoolSetting;
import com.clientm.setting.ChoiceSetting;
import com.clientm.setting.IntSetting;

/** Replaces the vanilla crosshair with one you can shape: style, size, gap, thickness, colour and an outline. */
public class CrosshairModule extends Module {

    private static CrosshairModule instance;

    private final ChoiceSetting style = add(new ChoiceSetting("style", "Style", 0, "Cross", "Cross + dot", "Dot", "T"));
    private final IntSetting size = add(new IntSetting("size", "Arm length", 4, 1, 12));
    private final IntSetting gap = add(new IntSetting("gap", "Gap", 2, 0, 8));
    private final IntSetting thickness = add(new IntSetting("thickness", "Thickness", 1, 1, 4));
    private final ChoiceSetting colour = add(
        new ChoiceSetting("colour", "Colour", 0, "White", "Green", "Red", "Cyan", "Yellow", "Pink"));
    private final BoolSetting outline = add(new BoolSetting("outline", "Black outline", true));

    private static final int[] COLOURS = { 0xFFFFFF, 0x5AFF6E, 0xFF4D5E, 0x5AE6FF, 0xFFE15A, 0xFF8AD8 };

    public CrosshairModule() {
        super("crosshair", "Custom Crosshair", Category.VISUAL, false);
        instance = this;
    }

    @Override
    public String description() {
        return "Shape and colour your crosshair";
    }

    /** True when this module draws the crosshair, so the vanilla one is left out. */
    public static boolean active() {
        CrosshairModule m = instance;
        return m != null && m.enabled;
    }

    public static void draw(ScaledResolution res) {
        CrosshairModule m = instance;
        if (m == null || !m.enabled) return;
        int cx = res.getScaledWidth() / 2, cy = res.getScaledHeight() / 2;
        int t = m.thickness.value, len = m.size.value, g = m.gap.value;
        int argb = 0xFF000000 | COLOURS[Math.max(0, Math.min(COLOURS.length - 1, m.colour.value))];
        int lo = -(t / 2), hi = lo + t; // the thickness, centred on the middle pixel
        boolean arms = m.style.value != 2;
        boolean dot = m.style.value == 1 || m.style.value == 2;
        boolean top = m.style.value != 3;
        if (m.outline.value) {
            if (arms) drawArms(cx, cy, lo - 1, hi + 1, g - 1, len + 2, top, 0xC0000000);
            if (dot) Gui.drawRect(cx + lo - 1, cy + lo - 1, cx + hi + 1, cy + hi + 1, 0xC0000000);
        }
        if (arms) drawArms(cx, cy, lo, hi, g, len, top, argb);
        if (dot) Gui.drawRect(cx + lo, cy + lo, cx + hi, cy + hi, argb);
    }

    private static void drawArms(int cx, int cy, int lo, int hi, int gap, int len, boolean top, int argb) {
        int start = Math.max(0, gap), end = gap + len;
        Gui.drawRect(cx + hi + start - 1, cy + lo, cx + hi + end - 1, cy + hi, argb); // right
        Gui.drawRect(cx + lo - end + 1, cy + lo, cx + lo - start + 1, cy + hi, argb); // left
        Gui.drawRect(cx + lo, cy + hi + start - 1, cx + hi, cy + hi + end - 1, argb); // bottom
        if (top) Gui.drawRect(cx + lo, cy + lo - end + 1, cx + hi, cy + lo - start + 1, argb); // top
    }
}
