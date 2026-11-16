package com.clientm.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.Entity;

import org.lwjgl.opengl.GL11;

import com.clientm.ClientM;
import com.clientm.module.impl.NametagCacheModule;
import com.clientm.module.impl.TexturedBackgroundsModule;

/**
 * Draws a nametag from a small image made once per name, instead of laying the text out twice every frame.
 *
 * The image holds the name in white-on-transparent plus a solid white strip. One draw puts down the dark box (sampling
 * the white strip) and the faint see-through-walls text, a second draw puts down the bright text. Vanilla needs three
 * draws and two text layouts for the same picture.
 *
 * A name seen for the first time is drawn the vanilla way for one frame and its image is made at the start of the
 * next frame, when no world rendering is in progress.
 */
public final class NametagCache implements IResourceManagerReloadListener {

    /** Image pixels per font pixel; enough for 32px HD fonts. */
    private static final int SCALE = 4;
    private static final int MAX_TILES = 256;
    private static final int MAX_BAKES_PER_FRAME = 8;
    private static final int MAX_WIDTH = 480;

    private static final Map<String, Tile> TILES = new HashMap<>();
    private static final ArrayList<String> PENDING = new ArrayList<>();
    private static long frame;
    private static boolean listening;
    private static boolean broken;
    private static boolean logged;

    private static final class Tile {

        Framebuffer image;
        int textWidth;
        float textU;
        float whiteU;
        long lastUsed;
    }

    private NametagCache() {}

    /** Draws the nametag from its image. False means the caller should draw it the vanilla way. */
    public static boolean draw(RenderManager manager, Entity entity, String name, double x, double y, double z,
        int maxDistance) {
        if (broken || !NametagCacheModule.active() || manager == null || manager.livingPlayer == null) return false;
        if (entity.getDistanceSqToEntity(manager.livingPlayer) > (double) (maxDistance * maxDistance)) return true;
        Tile tile = TILES.get(name);
        if (tile == null) {
            // Scrambled text changes every frame and cannot be kept.
            if (name.indexOf("§k") < 0 && PENDING.size() < 64 && !PENDING.contains(name)) PENDING.add(name);
            return false;
        }
        tile.lastUsed = frame;

        float scale = 0.016666668F * 1.6F;
        GL11.glPushMatrix();
        GL11.glTranslatef((float) x, (float) y + entity.height + 0.5F, (float) z);
        GL11.glNormal3f(0.0F, 1.0F, 0.0F);
        GL11.glRotatef(-manager.playerViewY, 0.0F, 1.0F, 0.0F);
        GL11.glRotatef(manager.playerViewX, 1.0F, 0.0F, 0.0F);
        GL11.glScalef(-scale, -scale, scale);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        // The image is stored with colour already multiplied by alpha.
        OpenGlHelper.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tile.image.framebufferTexture);

        int offset = name.equals("deadmau5") ? -10 : 0;
        int half = tile.textWidth / 2;
        // Text area of the image: one pixel of margin around the text, like the vanilla box.
        double left = -half - 1;
        double right = left + tile.textWidth + 2;
        double top = offset - 1;
        double bottom = offset + 9;

        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        if (!TexturedBackgroundsModule.skipNametagBox()) {
            t.setColorRGBA_F(0.0F, 0.0F, 0.0F, 0.25F);
            t.addVertexWithUV(-half - 1, offset - 1, 0.0D, tile.whiteU, 0.5D);
            t.addVertexWithUV(-half - 1, offset + 8, 0.0D, tile.whiteU, 0.5D);
            t.addVertexWithUV(half + 1, offset + 8, 0.0D, tile.whiteU, 0.5D);
            t.addVertexWithUV(half + 1, offset - 1, 0.0D, tile.whiteU, 0.5D);
        }
        t.setColorRGBA_F(0.125F, 0.125F, 0.125F, 0.125F);
        quad(t, left, top, right, bottom, tile.textU);
        t.draw();

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        t.startDrawingQuads();
        t.setColorRGBA_F(1.0F, 1.0F, 1.0F, 1.0F);
        quad(t, left, top, right, bottom, tile.textU);
        t.draw();

        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glPopMatrix();
        return true;
    }

    /** The image is upside down in texture space: its top row is v = 1. */
    private static void quad(Tessellator t, double left, double top, double right, double bottom, float maxU) {
        t.addVertexWithUV(left, top, 0.0D, 0.0D, 1.0D);
        t.addVertexWithUV(left, bottom, 0.0D, 0.0D, 0.0D);
        t.addVertexWithUV(right, bottom, 0.0D, maxU, 0.0D);
        t.addVertexWithUV(right, top, 0.0D, maxU, 1.0D);
    }

    /** Called at the start of every rendered frame, before the world is drawn. */
    public static void frameStart() {
        frame++;
        if (PENDING.isEmpty() || broken) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (!listening) {
            listening = true;
            if (mc.getResourceManager() instanceof IReloadableResourceManager) {
                ((IReloadableResourceManager) mc.getResourceManager()).registerReloadListener(new NametagCache());
            }
        }
        if (!OpenGlHelper.isFramebufferEnabled() || mc.fontRenderer == null) {
            PENDING.clear();
            broken = true;
            return;
        }
        try {
            if (TILES.size() + PENDING.size() > MAX_TILES) evict();
            int count = Math.min(PENDING.size(), MAX_BAKES_PER_FRAME);
            for (int i = 0; i < count; i++) {
                String name = PENDING.remove(PENDING.size() - 1);
                if (!TILES.containsKey(name)) TILES.put(name, bake(mc.fontRenderer, name));
            }
            if (!logged) {
                logged = true;
                ClientM.LOG.info("Nametag images are active");
            }
        } catch (Throwable t) {
            broken = true;
            PENDING.clear();
            ClientM.LOG.warn("Nametag images switched off after an error", t);
        } finally {
            mc.getFramebuffer()
                .bindFramebuffer(true);
        }
    }

    private static Tile bake(FontRenderer font, String name) {
        Tile tile = new Tile();
        tile.textWidth = Math.min(font.getStringWidth(name), MAX_WIDTH);
        // Logical layout: [text area: textWidth + 2][gap: 1][white strip: 2], 10 high.
        int textArea = tile.textWidth + 2;
        int logicalWidth = textArea + 3;
        Framebuffer fb = new Framebuffer(logicalWidth * SCALE, 10 * SCALE, false);
        fb.setFramebufferFilter(GL11.GL_NEAREST);
        fb.setFramebufferColor(0.0F, 0.0F, 0.0F, 0.0F);
        fb.framebufferClear();
        fb.bindFramebuffer(true);

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0.0D, logicalWidth, 10.0D, 0.0D, -1.0D, 1.0D);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 771);

        Gui.drawRect(textArea + 1, 0, logicalWidth, 10, 0xFFFFFFFF);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 771);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        font.drawString(name, 1, 1, 0xFFFFFFFF);

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();

        // The framebuffer texture can be larger than asked for, so work in its real size.
        float texWidth = fb.framebufferTextureWidth;
        tile.textU = textArea * SCALE / texWidth;
        tile.whiteU = (textArea + 2) * SCALE / texWidth;
        tile.image = fb;
        tile.lastUsed = frame;
        return tile;
    }

    /** Drops every image not used in the last few seconds of frames; all of them if that is not enough. */
    private static void evict() {
        long cutoff = frame - 2000;
        for (Iterator<Tile> it = TILES.values()
            .iterator(); it.hasNext();) {
            Tile tile = it.next();
            if (tile.lastUsed < cutoff) {
                tile.image.deleteFramebuffer();
                it.remove();
            }
        }
        if (TILES.size() + PENDING.size() > MAX_TILES) clear();
    }

    public static void clear() {
        for (Tile tile : TILES.values()) tile.image.deleteFramebuffer();
        TILES.clear();
        PENDING.clear();
    }

    /** A new texture pack can change the font, so every image is made again. */
    @Override
    public void onResourceManagerReload(IResourceManager manager) {
        clear();
    }
}
