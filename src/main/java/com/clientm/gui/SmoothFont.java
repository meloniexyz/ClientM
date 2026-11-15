package com.clientm.gui;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.DynamicTexture;

import org.lwjgl.opengl.GL11;

/**
 * Smooth (anti-aliased) text for the ClientM menu. The letters are drawn once into one texture at the real pixel
 * size for the current GUI scale, then each string is one batch of textured quads. Only the menu uses this; the
 * in-game HUD keeps Minecraft's own font, so nothing here runs while the menu is closed.
 */
public final class SmoothFont {

    private static final int FIRST = 32;
    private static final int LAST = 126;
    private static final Map<Integer, SmoothFont> CACHE = new HashMap<>();

    private final float inv;
    /** Empty pixels around each letter, so a neighbour with an overhang (like "j") never bleeds in. */
    private final int pad;
    private final int scale;
    private final int textureId;
    private final float atlas;
    private final int cellH;
    private final int capTop;
    private final int[] cellX = new int[LAST - FIRST + 1];
    private final int[] cellY = new int[LAST - FIRST + 1];
    private final int[] advance = new int[LAST - FIRST + 1];

    /**
     * @param scale GUI scale factor (screen pixels per GUI unit)
     * @param units letter size in GUI units; 9 sits where Minecraft's font does
     */
    public static SmoothFont get(int scale, int units, boolean bold) {
        int key = scale << 16 | units << 1 | (bold ? 1 : 0);
        SmoothFont f = CACHE.get(key);
        if (f == null) {
            f = new SmoothFont(scale, scale * units, bold);
            CACHE.put(key, f);
        }
        return f;
    }

    private SmoothFont(int scale, int px, boolean bold) {
        this.scale = scale;
        this.inv = 1f / scale;
        this.pad = Math.max(2, px / 5);
        Font font = new Font("Segoe UI", bold ? Font.BOLD : Font.PLAIN, px);

        Graphics2D probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        FontMetrics fm = probe.getFontMetrics(font);
        int ascent = fm.getAscent();
        cellH = ascent + fm.getDescent() + 2;
        int capH = (int) Math.round(
            font.createGlyphVector(probe.getFontRenderContext(), "H")
                .getVisualBounds()
                .getHeight());
        capTop = 1 + ascent - capH;
        for (int c = FIRST; c <= LAST; c++) advance[c - FIRST] = fm.charWidth((char) c);
        probe.dispose();

        // Pack the letters in rows; double the texture until they all fit.
        int size = 256;
        while (!pack(size)) size *= 2;
        atlas = size;

        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g.setFont(font);
        g.setColor(java.awt.Color.WHITE);
        for (int c = FIRST; c <= LAST; c++) {
            int i = c - FIRST;
            g.drawString(String.valueOf((char) c), cellX[i] + pad, cellY[i] + 1 + ascent);
        }
        g.dispose();

        textureId = new DynamicTexture(img).getGlTextureId();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
    }

    private boolean pack(int size) {
        int x = 0;
        int y = 0;
        for (int i = 0; i < advance.length; i++) {
            int w = advance[i] + pad * 2;
            if (x + w > size) {
                x = 0;
                y += cellH;
            }
            if (y + cellH > size) return false;
            cellX[i] = x;
            cellY[i] = y;
            x += w;
        }
        return true;
    }

    private static int index(char c) {
        return c >= FIRST && c <= LAST ? c - FIRST : '?' - FIRST;
    }

    /** Width in GUI units, rounded up. */
    public int width(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) w += advance[index(s.charAt(i))];
        return (w + scale - 1) / scale;
    }

    /** The longest start of the string that fits in the given width. */
    public String trim(String s, int maxWidth) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += advance[index(s.charAt(i))];
            if (w > maxWidth * scale) return s.substring(0, i);
        }
        return s;
    }

    /** Draws with the top of the capital letters at y. Colour is 0xRRGGBB. */
    public void draw(String s, float x, float y, int rgb) {
        draw(s, x, y, rgb, 1f);
    }

    /** Same, with a soft dark copy underneath for text that sits straight on the game world. */
    public void drawShadowed(String s, float x, float y, int rgb) {
        draw(s, x + inv, y + inv, 0x000000, 0.75f);
        draw(s, x, y, rgb, 1f);
    }

    private void draw(String s, float x, float y, int rgb, float alpha) {
        // Snapped to whole screen pixels so the letters stay sharp.
        int px = Math.round(x * scale) - pad;
        int py = Math.round(y * scale) - capTop;
        float y0 = py * inv;
        float y1 = (py + cellH) * inv;

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);

        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.setColorRGBA_F((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f, alpha);
        for (int n = 0; n < s.length(); n++) {
            int i = index(s.charAt(n));
            int w = advance[i] + pad * 2;
            float x0 = px * inv;
            float x1 = (px + w) * inv;
            float u0 = cellX[i] / atlas;
            float u1 = (cellX[i] + w) / atlas;
            float v0 = cellY[i] / atlas;
            float v1 = (cellY[i] + cellH) / atlas;
            t.addVertexWithUV(x0, y1, 0, u0, v1);
            t.addVertexWithUV(x1, y1, 0, u1, v1);
            t.addVertexWithUV(x1, y0, 0, u1, v0);
            t.addVertexWithUV(x0, y0, 0, u0, v0);
            px += advance[i];
        }
        t.draw();

        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }
}
