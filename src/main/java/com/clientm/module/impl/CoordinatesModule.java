package com.clientm.module.impl;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;

import com.clientm.module.HudModule;
import com.clientm.setting.BoolSetting;

public class CoordinatesModule extends HudModule {

    private static final String[] FACING = { "S", "SW", "W", "NW", "N", "NE", "E", "SE" };

    private final BoolSetting facing = add(new BoolSetting("facing", "Show facing", true));
    private final BoolSetting oneLine = add(new BoolSetting("oneLine", "Single line", false));

    private int lx = Integer.MIN_VALUE, ly, lz, lf = -1;
    private String sx = "X: 0", sy = "Y: 0", sz = "Z: 0", line = "0, 0, 0";
    private int textWidth;
    private int lineWidth;
    private int facingWidth;

    public CoordinatesModule() {
        super("coordinates", "Coordinates", false, 0.005f, 0.09f);
    }

    @Override
    public String description() {
        return "Position and facing";
    }

    @Override
    public void onSettingsChanged() {
        lx = Integer.MIN_VALUE;
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
        lx = x;
        ly = y;
        lz = z;
        lf = f;
        sx = "X: " + x;
        sy = "Y: " + y;
        sz = "Z: " + z;
        line = x + ", " + y + ", " + z;
        textWidth = Math.max(font.getStringWidth(sx), Math.max(font.getStringWidth(sy), font.getStringWidth(sz)));
        lineWidth = font.getStringWidth(line);
        facingWidth = font.getStringWidth(FACING[f]);
    }

    @Override
    public int width() {
        // text + padding, then a gap and room for the widest facing label ("NW")
        return (oneLine.value ? lineWidth : textWidth) + 6 + (facing.value ? 8 + 12 : 0);
    }

    @Override
    public int height() {
        return oneLine.value ? 14 : 3 * 10 + 4;
    }

    @Override
    public void render(FontRenderer font) {
        if (lf < 0) return;
        int w = width();
        if (background) Gui.drawRect(0, 0, w, height(), bg());
        if (oneLine.value) {
            text(font, line, 3, 3);
        } else {
            text(font, sx, 3, 3);
            text(font, sy, 3, 13);
            text(font, sz, 3, 23);
        }
        if (facing.value) text(font, FACING[lf], w - 3 - facingWidth, oneLine.value ? 3 : 13, 0xFFD24A);
    }
}
