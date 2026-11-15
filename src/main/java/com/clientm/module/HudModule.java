package com.clientm.module;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

/**
 * A module that draws on the HUD. Position is stored as a fraction of the screen so it survives resolution and GUI
 * scale changes.
 *
 * Performance rule: {@link #render} runs every frame, so it must not allocate. Build strings in {@link #onTick} (20
 * times a second) and only draw the cached result here.
 */
public abstract class HudModule extends Module {

    public float x;
    public float y;
    public boolean background = true;

    protected HudModule(String id, String name, boolean enabledByDefault, float x, float y) {
        this(id, name, Category.HUD, enabledByDefault, x, y);
    }

    protected HudModule(String id, String name, Category category, boolean enabledByDefault, float x, float y) {
        super(id, name, category, enabledByDefault);
        this.x = x;
        this.y = y;
    }

    public abstract int width();

    public abstract int height();

    /** Draw with the origin already translated to this module's top-left corner. */
    public abstract void render(FontRenderer font);

    protected void drawLabel(FontRenderer font, String text) {
        if (background) Gui.drawRect(0, 0, width(), height(), 0x6F000000);
        font.drawStringWithShadow(text, 3, 3, 0xFFFFFF);
    }
}
