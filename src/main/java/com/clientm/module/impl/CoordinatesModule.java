package com.clientm.module.impl;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;

import com.clientm.module.HudModule;

public class CoordinatesModule extends HudModule {

    private static final String[] FACING = { "S", "SW", "W", "NW", "N", "NE", "E", "SE" };

    private int lx = Integer.MIN_VALUE, ly, lz, lf = -1;
    private String sx = "X: 0", sy = "Y: 0", sz = "Z: 0";
    private int textWidth;
    private int facingWidth;

    public CoordinatesModule() {
        super("coordinates", "Coordinates", false, 0.005f, 0.09f);
    }

    @Override
    public void onTick() {
        EntityPlayer p = mc.thePlayer;
        int x = MathHelper.floor_double(p.posX);
        int y = MathHelper.floor_double(p.boundingBox.minY);
        int z = MathHelper.floor_double(p.posZ);
        int f = MathHelper.floor_double(p.rotationYaw * 8.0F / 360.0F + 0.5D) & 7;
        if (x == lx && y == ly && z == lz && f == lf) return;
        FontRenderer font = mc.fontRenderer;
        if (x != lx) sx = "X: " + x;
        if (y != ly) sy = "Y: " + y;
        if (z != lz) sz = "Z: " + z;
        lx = x;
        ly = y;
        lz = z;
        lf = f;
        textWidth = Math.max(font.getStringWidth(sx), Math.max(font.getStringWidth(sy), font.getStringWidth(sz)));
        facingWidth = font.getStringWidth(FACING[f]);
    }

    @Override
    public int width() {
        // text + padding + a gap + room for the widest facing label ("NW")
        return textWidth + 6 + 8 + 12;
    }

    @Override
    public int height() {
        return 3 * 10 + 4;
    }

    @Override
    public void render(FontRenderer font) {
        if (lf < 0) return;
        int w = width();
        if (background) Gui.drawRect(0, 0, w, height(), 0x6F000000);
        font.drawStringWithShadow(sx, 3, 3, 0xFFFFFF);
        font.drawStringWithShadow(sy, 3, 13, 0xFFFFFF);
        font.drawStringWithShadow(sz, 3, 23, 0xFFFFFF);
        font.drawStringWithShadow(FACING[lf], w - 3 - facingWidth, 13, 0xFFD24A);
    }
}
