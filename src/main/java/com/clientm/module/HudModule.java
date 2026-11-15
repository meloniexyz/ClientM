package com.clientm.module;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

import com.clientm.setting.BoolSetting;
import com.clientm.setting.ChoiceSetting;
import com.clientm.setting.IntSetting;

/**
 * A module that draws on the HUD. Position is stored as a fraction of the screen so it survives resolution and GUI
 * scale changes.
 *
 * Performance rule: {@link #render} runs every frame, so it must not allocate. Build strings in {@link #onTick} (20
 * times a second) and only draw the cached result here.
 */
public abstract class HudModule extends Module {

    private static final int[] COLOURS = { 0xFFFFFF, 0xFF4D5E, 0xA99DFF, 0x55FF55, 0xFFFF55, 0x55FFFF };

    public float x;
    public float y;
    public boolean background = true;

    // Look options every HUD module has; they come first in its options list.
    protected final ChoiceSetting colour = add(
        new ChoiceSetting("colour", "Text colour", 0, "White", "Red", "Purple", "Green", "Yellow", "Aqua", "Rainbow"));
    protected final BoolSetting shadow = add(new BoolSetting("shadow", "Text shadow", true));
    protected final IntSetting opacity = add(new IntSetting("opacity", "Background opacity %", 44, 0, 100));
    protected final IntSetting scale = add(new IntSetting("scale", "Size %", 100, 50, 200));

    protected HudModule(String id, String name, boolean enabledByDefault, float x, float y) {
        this(id, name, Category.HUD, enabledByDefault, x, y);
    }

    protected HudModule(String id, String name, Category category, boolean enabledByDefault, float x, float y) {
        super(id, name, category, enabledByDefault);
        this.x = x;
        this.y = y;
    }

    /** Unscaled size; the renderer applies {@link #scale()}. */
    public abstract int width();

    public abstract int height();

    /** Draw with the origin already translated (and scaled) to the top-left corner of this module. */
    public abstract void render(FontRenderer font);

    public float scale() {
        return scale.value / 100f;
    }

    /** Size on screen, after the Size option. */
    public int scaledWidth() {
        return Math.round(width() * scale());
    }

    public int scaledHeight() {
        return Math.round(height() * scale());
    }

    /** 0xRRGGBB text colour from the Text colour option. */
    protected int textColour() {
        int i = colour.value;
        if (i < COLOURS.length) return COLOURS[i];
        return java.awt.Color.HSBtoRGB((System.currentTimeMillis() % 4000L) / 4000f, 0.75f, 1f) & 0xFFFFFF;
    }

    /** Background colour (black at the chosen opacity). */
    protected int bg() {
        return (opacity.value * 255 / 100) << 24;
    }

    protected void text(FontRenderer font, String s, int x, int y) {
        text(font, s, x, y, textColour());
    }

    protected void text(FontRenderer font, String s, int x, int y, int rgb) {
        if (shadow.value) font.drawStringWithShadow(s, x, y, rgb);
        else font.drawString(s, x, y, rgb);
    }

    protected void drawLabel(FontRenderer font, String text) {
        if (background) Gui.drawRect(0, 0, width(), height(), bg());
        text(font, text, 3, 3);
    }
}
