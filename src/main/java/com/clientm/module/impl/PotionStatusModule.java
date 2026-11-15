package com.clientm.module.impl;

import java.util.Collection;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.InventoryEffectRenderer;
import net.minecraft.client.resources.I18n;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import com.clientm.module.HudModule;
import com.clientm.setting.ChoiceSetting;

/**
 * Active potion effects. The Vanilla style draws them exactly like the list beside the inventory: the same box and
 * icon from the inventory texture (so resource packs restyle it), name with level on top and the time left below.
 * The Compact style is one line of text per effect.
 */
public class PotionStatusModule extends HudModule {

    private static final int MAX = 12;
    private static final String[] LEVEL = { "", " II", " III", " IV", " V", " VI", " VII", " VIII", " IX", " X" };
    private static final ResourceLocation INVENTORY = new ResourceLocation("textures/gui/container/inventory.png");
    private static final int BOX_W = 140;
    private static final int BOX_STEP = 33;

    private final ChoiceSetting style = add(new ChoiceSetting("style", "Style", 0, "Vanilla", "Compact"));

    private final String[] names = new String[MAX];
    private final String[] times = new String[MAX];
    private final String[] lines = new String[MAX];
    private final int[] colors = new int[MAX];
    private final int[] icons = new int[MAX];
    private int count;
    private int maxWidth;
    private int tick;

    public PotionStatusModule() {
        super("potions", "Potion Status", false, 0.005f, 0.3f);
    }

    @Override
    public void onTick() {
        // Durations are shown in seconds, so rebuilding four times a second is plenty.
        if ((tick++ & 3) != 0) return;
        Collection<?> effects = mc.thePlayer.getActivePotionEffects();
        FontRenderer font = mc.fontRenderer;
        count = 0;
        maxWidth = 0;
        for (Object o : effects) {
            if (count == MAX) break;
            PotionEffect e = (PotionEffect) o;
            Potion potion = Potion.potionTypes[e.getPotionID()];
            int amp = e.getAmplifier();
            String level = amp >= 0 && amp < LEVEL.length ? LEVEL[amp] : " " + (amp + 1);
            names[count] = I18n.format(e.getEffectName()) + level;
            times[count] = Potion.getDurationString(e);
            lines[count] = names[count] + "  " + times[count];
            colors[count] = e.getDuration() < 200 ? 0xFF5555 : 0xFFFFFF;
            icons[count] = potion != null && potion.hasStatusIcon() ? potion.getStatusIconIndex() : -1;
            int w = font.getStringWidth(lines[count]);
            if (w > maxWidth) maxWidth = w;
            count++;
        }
    }

    private boolean vanilla() {
        return style.value == 0;
    }

    @Override
    public int width() {
        if (count == 0) return 0;
        return vanilla() ? BOX_W : maxWidth + 6;
    }

    @Override
    public int height() {
        if (count == 0) return 0;
        return vanilla() ? count * BOX_STEP - 1 : count * 10 + 4;
    }

    @Override
    public void render(FontRenderer font) {
        if (count == 0) return;
        // The inventory shows its own effect list; do not draw a second one behind it.
        if (mc.currentScreen instanceof InventoryEffectRenderer) return;
        if (!vanilla()) {
            if (background) Gui.drawRect(0, 0, width(), height(), bg());
            for (int i = 0; i < count; i++) text(font, lines[i], 3, 3 + i * 10, colors[i]);
            return;
        }
        // Same layout and numbers as InventoryEffectRenderer.
        Gui gui = mc.ingameGUI;
        for (int i = 0; i < count; i++) {
            int y = i * BOX_STEP;
            GL11.glColor4f(1f, 1f, 1f, 1f);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_BLEND);
            mc.getTextureManager()
                .bindTexture(INVENTORY);
            if (background) gui.drawTexturedModalRect(0, y, 0, 166, BOX_W, 32);
            int icon = icons[i];
            if (icon >= 0) gui.drawTexturedModalRect(6, y + 7, icon % 8 * 18, 198 + icon / 8 * 18, 18, 18);
            font.drawStringWithShadow(names[i], 28, y + 6, 0xFFFFFF);
            font.drawStringWithShadow(times[i], 28, y + 16, 0x7F7F7F);
        }
    }
}
