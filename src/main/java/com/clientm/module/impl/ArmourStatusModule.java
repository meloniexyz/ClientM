package com.clientm.module.impl;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.clientm.module.HudModule;

/** Worn armour and held item, each with remaining durability (or stack size). */
public class ArmourStatusModule extends HudModule {

    private static final int SLOTS = 5;
    private static final int ROW = 17;

    private final RenderItem itemRender = new RenderItem();
    private final ItemStack[] stacks = new ItemStack[SLOTS];
    private final String[] text = new String[SLOTS];
    private final int[] value = new int[SLOTS];
    private final int[] color = new int[SLOTS];
    private int count;
    private int maxWidth;

    private final com.clientm.setting.ChoiceSetting durability = add(
        new com.clientm.setting.ChoiceSetting("durability", "Durability", 0, "Uses left", "Percent", "Hidden"));

    public ArmourStatusModule() {
        super("armour", "Armour Status", false, 0.9f, 0.6f);
        background = false;
        java.util.Arrays.fill(value, Integer.MIN_VALUE);
        java.util.Arrays.fill(text, "");
    }

    @Override
    public void onSettingsChanged() {
        java.util.Arrays.fill(value, Integer.MIN_VALUE);
    }

    @Override
    public void onTick() {
        InventoryPlayer inv = mc.thePlayer.inventory;
        FontRenderer font = mc.fontRenderer;
        count = 0;
        maxWidth = 0;
        for (int i = 3; i >= 0; i--) add(font, inv.armorInventory[i]);
        add(font, inv.getCurrentItem());
    }

    private void add(FontRenderer font, ItemStack stack) {
        if (stack == null) return;
        int i = count++;
        stacks[i] = stack;
        int v;
        int c = 0xFFFFFF;
        boolean percent = false;
        if (stack.isItemStackDamageable()) {
            int max = stack.getMaxDamage();
            v = max - stack.getItemDamage();
            int pct = max <= 0 ? 100 : v * 100 / max;
            c = pct > 50 ? 0x55FF55 : pct > 20 ? 0xFFFF55 : 0xFF5555;
            // Durability option: 0 = uses left, 1 = percent, 2 = hidden
            if (durability.value == 1) {
                v = pct;
                percent = true;
            } else if (durability.value == 2) {
                v = -1;
            }
        } else {
            v = stack.stackSize > 1 ? stack.stackSize : -1;
        }
        if (v != value[i]) {
            value[i] = v;
            text[i] = v < 0 ? "" : percent ? v + "%" : Integer.toString(v);
        }
        color[i] = c;
        int w = font.getStringWidth(text[i]);
        if (w > maxWidth) maxWidth = w;
    }

    @Override
    public int width() {
        return count == 0 ? 0 : 19 + maxWidth + 2;
    }

    @Override
    public int height() {
        return count * ROW;
    }

    @Override
    public void render(FontRenderer font) {
        if (count == 0) return;
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        RenderHelper.enableGUIStandardItemLighting();
        for (int i = 0; i < count; i++) {
            itemRender.renderItemAndEffectIntoGUI(font, mc.getTextureManager(), stacks[i], 0, i * ROW);
        }
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL12.GL_RESCALE_NORMAL);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        for (int i = 0; i < count; i++) {
            if (!text[i].isEmpty()) text(font, text[i], 19, i * ROW + 4, color[i]);
        }
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }
}
