package com.clientm.render;

import java.util.ArrayList;
import java.util.HashMap;
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
 * Draws nametags from one shared image instead of laying the text out twice per name every frame.
 *
 * Every name is written once, in white on transparent, into a sheet that also holds a solid white patch. While the
 * world's entities are being drawn, each nametag only adds its corners to a list. When the entities are done, the
 * whole list goes to the graphics card in two draws: one for the dark boxes and the faint see-through-walls text, one
 * for the bright text. Vanilla needs three draws and two text layouts per name.
 *
 * A name seen for the first time is drawn the vanilla way for one frame and written into the sheet at the start of
 * the next frame, when no world rendering is in progress.
 */
public final class NametagCache implements IResourceManagerReloadListener {

    /** Sheet pixels per font pixel; enough for 32px HD fonts. */
    private static final int SCALE = 4;
    /** Sheet size in font pixels (2048 real pixels). */
    private static final int SHEET = 512;
    /** A line of names: 10 high plus a gap. */
    private static final int ROW = 11;
    private static final int WHITE = 4;
    private static final int MAX_BAKES_PER_FRAME = 8;
    private static final int MAX_WIDTH = 480;
    /** Numbers kept per queued rectangle: 4 corners of x y z u v, then r g b a and the light value. */
    private static final int QUAD = 25;
    private static final float RADIANS = (float) (Math.PI / 180.0);

    private static final Map<String, Tile> TILES = new HashMap<>();
    private static final ArrayList<String> PENDING = new ArrayList<>();
    private static Framebuffer sheet;
    private static int cursorX;
    private static int cursorY;
    private static boolean listening;
    private static boolean broken;
    private static boolean logged;

    /** True between the start and the end of the world's entity drawing. */
    private static boolean collecting;
    private static float[] behind = new float[QUAD * 64];
    private static int behindSize;
    private static float[] front = new float[QUAD * 32];
    private static int frontSize;

    private static final class Tile {

        int textWidth;
        float u0;
        float u1;
        float vTop;
        float vBottom;
    }

    private NametagCache() {}

    /** Draws or queues the nametag. False means the caller should draw it the vanilla way. */
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

        // The same placement vanilla builds with translate, two rotations and a scale, worked out here so the
        // corners can wait in a list.
        float scale = 0.016666668F * 1.6F;
        float yaw = -manager.playerViewY * RADIANS;
        float pitch = manager.playerViewX * RADIANS;
        float cosYaw = (float) Math.cos(yaw);
        float sinYaw = (float) Math.sin(yaw);
        float cosPitch = (float) Math.cos(pitch);
        float sinPitch = (float) Math.sin(pitch);
        float cx = (float) x;
        float cy = (float) y + entity.height + 0.5F;
        float cz = (float) z;
        // One step right and one step down on the tag, in world directions.
        float rightX = -scale * cosYaw;
        float rightZ = scale * sinYaw;
        float downX = -scale * sinPitch * sinYaw;
        float downY = -scale * cosPitch;
        float downZ = -scale * sinPitch * cosYaw;

        int offset = name.equals("deadmau5") ? -10 : 0;
        int half = tile.textWidth / 2;
        float light = (float) (((int) OpenGlHelper.lastBrightnessY << 16)
            | ((int) OpenGlHelper.lastBrightnessX & 0xFFFF));
        float whiteU = (WHITE / 2F) / SHEET;
        float whiteV = 1F - (WHITE / 2F) / SHEET;

        if (!TexturedBackgroundsModule.skipNametagBox()) {
            behind = room(behind, behindSize);
            behindSize = quad(
                behind,
                behindSize,
                cx,
                cy,
                cz,
                rightX,
                rightZ,
                downX,
                downY,
                downZ,
                -half - 1,
                offset - 1,
                half + 1,
                offset + 8,
                whiteU,
                whiteU,
                whiteV,
                whiteV,
                0F,
                0F,
                0F,
                0.25F,
                light);
        }
        // Text area of the sheet: one pixel of margin around the text, like the vanilla box.
        float left = -half - 1;
        float right = left + tile.textWidth + 2;
        float top = offset - 1;
        float bottom = offset + 9;
        behind = room(behind, behindSize);
        behindSize = quad(
            behind,
            behindSize,
            cx,
            cy,
            cz,
            rightX,
            rightZ,
            downX,
            downY,
            downZ,
            left,
            top,
            right,
            bottom,
            tile.u0,
            tile.u1,
            tile.vTop,
            tile.vBottom,
            0.125F,
            0.125F,
            0.125F,
            0.125F,
            light);
        front = room(front, frontSize);
        frontSize = quad(
            front,
            frontSize,
            cx,
            cy,
            cz,
            rightX,
            rightZ,
            downX,
            downY,
            downZ,
            left,
            top,
            right,
            bottom,
            tile.u0,
            tile.u1,
            tile.vTop,
            tile.vBottom,
            1F,
            1F,
            1F,
            1F,
            light);
        // Outside the world's entity drawing (an entity shown in a menu, say) nothing will come back to empty the
        // list, so it is emptied right away.
        if (!collecting) flush(false);
        return true;
    }

    private static float[] room(float[] list, int size) {
        return size + QUAD <= list.length ? list : java.util.Arrays.copyOf(list, list.length * 2);
    }

    private static int quad(float[] list, int at, float cx, float cy, float cz, float rightX, float rightZ, float downX,
        float downY, float downZ, float left, float top, float right, float bottom, float u0, float u1, float vTop,
        float vBottom, float r, float g, float b, float a, float light) {
        at = corner(list, at, cx, cy, cz, rightX, rightZ, downX, downY, downZ, left, top, u0, vTop);
        at = corner(list, at, cx, cy, cz, rightX, rightZ, downX, downY, downZ, left, bottom, u0, vBottom);
        at = corner(list, at, cx, cy, cz, rightX, rightZ, downX, downY, downZ, right, bottom, u1, vBottom);
        at = corner(list, at, cx, cy, cz, rightX, rightZ, downX, downY, downZ, right, top, u1, vTop);
        list[at++] = r;
        list[at++] = g;
        list[at++] = b;
        list[at++] = a;
        list[at++] = light;
        return at;
    }

    private static int corner(float[] list, int at, float cx, float cy, float cz, float rightX, float rightZ,
        float downX, float downY, float downZ, float across, float down, float u, float v) {
        list[at++] = cx + across * rightX + down * downX;
        list[at++] = cy + down * downY;
        list[at++] = cz + across * rightZ + down * downZ;
        list[at++] = u;
        list[at++] = v;
        return at;
    }

    /** The world is about to draw its entities: nametags are collected from here on. */
    public static void beginEntities() {
        collecting = NametagCacheModule.active() && !broken;
    }

    /** The world has drawn its entities: every collected nametag goes out now. */
    public static void endEntities() {
        if (!collecting) return;
        collecting = false;
        flush(true);
    }

    private static void flush(boolean ownLightmap) {
        if (behindSize == 0 && frontSize == 0) return;
        if (sheet == null) {
            behindSize = frontSize = 0;
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (ownLightmap) mc.entityRenderer.enableLightmap(0.0D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        // The sheet is stored with colour already multiplied by alpha.
        OpenGlHelper.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, sheet.framebufferTexture);
        GL11.glNormal3f(0.0F, 1.0F, 0.0F);

        send(behind, behindSize);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        send(front, frontSize);
        behindSize = frontSize = 0;

        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        if (ownLightmap) mc.entityRenderer.disableLightmap(0.0D);
    }

    private static void send(float[] list, int size) {
        if (size == 0) return;
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        for (int at = 0; at < size; at += QUAD) {
            t.setColorRGBA_F(list[at + 20], list[at + 21], list[at + 22], list[at + 23]);
            t.setBrightness((int) list[at + 24]);
            for (int c = at; c < at + 20; c += 5) {
                t.addVertexWithUV(list[c], list[c + 1], list[c + 2], list[c + 3], list[c + 4]);
            }
        }
        t.draw();
    }

    /** Called at the start of every rendered frame, before the world is drawn. */
    public static void frameStart() {
        // Nothing should be left over; if a frame was cut short, do not carry its tags into the next one.
        behindSize = frontSize = 0;
        collecting = false;
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
            bakePending(mc.fontRenderer);
            if (!logged) {
                logged = true;
                ClientM.LOG.info("Nametag sheet is active");
            }
        } catch (Throwable t) {
            broken = true;
            PENDING.clear();
            ClientM.LOG.warn("Nametag sheet switched off after an error", t);
        } finally {
            mc.getFramebuffer()
                .bindFramebuffer(true);
        }
    }

    private static void bakePending(FontRenderer font) {
        if (sheet == null) {
            sheet = new Framebuffer(SHEET * SCALE, SHEET * SCALE, false);
            sheet.setFramebufferFilter(GL11.GL_NEAREST);
            startSheet();
        }
        sheet.bindFramebuffer(true);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0.0D, SHEET, SHEET, 0.0D, -1.0D, 1.0D);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_ALPHA_TEST);

        int count = Math.min(PENDING.size(), MAX_BAKES_PER_FRAME);
        for (int i = 0; i < count; i++) {
            String name = PENDING.remove(PENDING.size() - 1);
            if (!TILES.containsKey(name)) TILES.put(name, bake(font, name));
        }

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
    }

    /** Empties the sheet and puts the white patch in its corner. The sheet must be bound or about to be. */
    private static void startSheet() {
        TILES.clear();
        sheet.setFramebufferColor(0.0F, 0.0F, 0.0F, 0.0F);
        sheet.framebufferClear();
        sheet.bindFramebuffer(true);
        cursorX = WHITE + 1;
        cursorY = 0;
    }

    private static Tile bake(FontRenderer font, String name) {
        Tile tile = new Tile();
        tile.textWidth = Math.min(font.getStringWidth(name), MAX_WIDTH);
        int area = tile.textWidth + 2;
        if (cursorX + area > SHEET) {
            cursorX = 0;
            cursorY += ROW;
        }
        if (cursorY + 10 > SHEET) startSheet(); // full: start over, names come back as they are seen again

        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 771);
        if (cursorX == WHITE + 1 && cursorY == 0) {
            Gui.drawRect(0, 0, WHITE, WHITE, 0xFFFFFFFF);
            GL11.glEnable(GL11.GL_BLEND);
            OpenGlHelper.glBlendFunc(770, 771, 1, 771);
        }
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        font.drawString(name, cursorX + 1, cursorY + 1, 0xFFFFFFFF);

        // The sheet is upside down in texture space: its top row is v = 1.
        tile.u0 = cursorX / (float) SHEET;
        tile.u1 = (cursorX + area) / (float) SHEET;
        tile.vTop = 1F - cursorY / (float) SHEET;
        tile.vBottom = 1F - (cursorY + 10) / (float) SHEET;
        cursorX += area + 1;
        return tile;
    }

    public static void clear() {
        TILES.clear();
        PENDING.clear();
        behindSize = frontSize = 0;
        if (sheet != null) {
            sheet.deleteFramebuffer();
            sheet = null;
        }
    }

    /** A new texture pack can change the font, so every name is written again. */
    @Override
    public void onResourceManagerReload(IResourceManager manager) {
        clear();
    }
}
